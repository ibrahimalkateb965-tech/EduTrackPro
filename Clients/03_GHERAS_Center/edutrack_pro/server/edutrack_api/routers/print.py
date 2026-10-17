import os
import re
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path
from urllib.parse import parse_qs, quote
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import Response

from edutrack_api.auth import issue_token, require_roles
from edutrack_api.config import get_settings
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.serializers import _convert, row_to_json
from edutrack_api.services import pdf
from edutrack_api.services.settings import load_settings
from edutrack_api.services.words import arabic_amount_words
from edutrack_api.services.student_photos import photo_data_uri

router = APIRouter()
_TZ = ZoneInfo("Asia/Riyadh")


def _today() -> date:
    return datetime.now(_TZ).date()


_ROLES = Depends(require_roles("manager", "supervisor"))


def _date(value: object) -> str:
    """Calendar day (YYYY-MM-DD) in Riyadh; row_to_json has already turned timestamptz into ISO strings."""
    if isinstance(value, str) and value:
        try:
            value = datetime.fromisoformat(value)
        except ValueError:
            return value.split("T")[0].split(" ")[0]
    if isinstance(value, datetime):
        return (value.astimezone(_TZ) if value.tzinfo else value).date().isoformat()
    return value.isoformat() if isinstance(value, date) else str(value or "")


def _one(conn, sql: str, args: tuple, message: str):
    row = conn.execute(sql, args).fetchone()
    if not row:
        raise ApiError(404, "not_found", message)
    return row_to_json(row)


def _resp(payload: dict, conn=None) -> dict:
    # Center identity + academic year from system_settings (006), constants as fallback;
    # endpoint-specific keys win over the shared block.
    if conn is not None:
        payload = {**load_settings(conn), **payload}
    return _convert(payload)


def _check_perm(user: dict, perm: str, message: str) -> None:
    if user.get("role") == "manager":
        return
    perms = user.get("permissions", {})
    if not perms.get(perm, False):
        raise ApiError(403, "forbidden", message)


def _num(value: object) -> str:
    return f"{float(value):.2f}".rstrip("0").rstrip(".")


def _interval_label(days: int) -> str:
    if days == 30:
        return "شهري (كل 30 يوم)"
    if days == 7:
        return "أسبوعي (كل 7 أيام)"
    return f"كل {days} يوم"


def _installments_count_text(count: int) -> str:
    if count == 1:
        return "على دفعة واحدة"
    if count == 2:
        return "على دفعتين"
    if 3 <= count <= 10:
        return f"على {count} دفعات"
    return f"على {count} دفعة"


def _installment_breakdown(conn, student_id, amount, balance, plan_id=None) -> dict:
    plan = conn.execute(
        """SELECT fp.total_amount, fp.interval_days,
                  count(*) FILTER (WHERE i.status IN ('pending', 'partial')) AS remaining_count,
                  min(i.due_date) FILTER (WHERE i.status IN ('pending', 'partial')) AS next_due_date,
                  sum(i.amount - i.paid_amount) FILTER (WHERE i.status IN ('pending', 'partial')) AS remaining_amount,
                  (SELECT n.amount - n.paid_amount FROM installments n
                    WHERE n.fee_plan_id = fp.id AND n.deleted_at IS NULL AND n.status IN ('pending', 'partial')
                    ORDER BY n.seq_no ASC LIMIT 1) AS next_amount
           FROM fee_plans fp JOIN installments i ON i.fee_plan_id = fp.id
           WHERE fp.student_id=%s AND (%s::uuid IS NULL OR fp.id=%s::uuid)
             AND fp.deleted_at IS NULL AND i.deleted_at IS NULL
           GROUP BY fp.id, fp.total_amount, fp.interval_days
           ORDER BY fp.created_at DESC LIMIT 1""",
        (student_id, plan_id, plan_id),
    ).fetchone()
    remaining = (plan["remaining_count"] if plan else 0) or 0
    # Same plan as total_fee, so total, paid amount and remaining agree on the receipt.
    plan_balance = float(plan["remaining_amount"] or 0) if plan else float(balance or 0)
    next_amount = float(plan["next_amount"] or 0) if plan and remaining else 0.0
    next_due = _date(plan["next_due_date"]) if plan and remaining else "—"
    if not plan_balance:
        summary = "مسدد بالكامل"
    elif remaining:
        summary = (
            f"المتبقي {_num(plan_balance)} ر.س {_installments_count_text(remaining)}، "
            f"قيمة الدفعة القادمة {_num(next_amount)} ر.س، تستحق بتاريخ {next_due}"
        )
    else:
        summary = f"المتبقي {_num(plan_balance)} ر.س"
    return {
        "total_fee": float(plan["total_amount"]) if plan else float(amount),
        "remaining_balance": plan_balance,
        "has_installments": remaining > 0,
        "is_fully_paid": not plan_balance,
        "remaining_installments_count": remaining,
        "next_due_date": next_due,
        "next_installment_amount": next_amount,
        "interval_label": _interval_label(plan["interval_days"]) if plan and remaining else "—",
        "installment_summary_text": summary,
    }


def _normalize_guardian_whatsapp(phone: str | None) -> str:
    if not phone:
        return ""
    digits = re.sub(r"\D", "", str(phone))
    if digits.startswith("009665") and len(digits) == 14:
        return digits[2:]
    if digits.startswith("9665") and len(digits) == 12:
        return digits
    if digits.startswith("05") and len(digits) == 10:
        return "966" + digits[1:]
    if digits.startswith("5") and len(digits) == 9:
        return "966" + digits
    if digits.startswith("00") and len(digits) > 8:
        return digits[2:]
    if len(digits) >= 9:
        return digits
    return ""


@router.get("/print/receipt")
@router.get("/print/receipt/{payment_id}")
def receipt(
    payment_id: str | None = None,
    payment: str | None = None,
    id: str | None = None,
    conn=Depends(get_conn),
    user: dict = _ROLES,
) -> dict:
    _check_perm(user, "finance", "طباعة سند القبض تتطلب صلاحية المالية")
    pid = payment_id or payment or id
    if not pid:
        raise ApiError(400, "validation_error", "رقم الدفعة أو السند مطلوب")

    select_fields = """r.receipt_no, p.id AS payment_id, r.issued_on, r.issued_by_user_id, p.amount, COALESCE(p.bus_amount, 0) AS bus_amount, p.method,
               p.student_id, s.name AS student_name, s.has_bus_subscription,
               COALESCE(s.bus_fee, 0) AS bus_fee, COALESCE(s.bus_paid, 0) AS bus_paid, COALESCE(s.bus_remaining, 0) AS bus_remaining,
               s.guardian_phone, s.father_phone, s.mother_phone,
               i.seq_no, i.fee_plan_id, b.balance,
               COALESCE(st.name, u.username, '') AS receiver_name"""
    from_joins = """FROM receipts r
               JOIN payments p ON p.id = r.payment_id
               JOIN students s ON s.id = p.student_id
               LEFT JOIN users u ON u.id = r.issued_by_user_id
               LEFT JOIN staff st ON st.id = u.staff_id
               LEFT JOIN installments i ON i.id = p.installment_id
               LEFT JOIN v_student_balance b ON b.student_id = s.id"""

    if str(pid).isdigit():
        item = conn.execute(
            f"""SELECT {select_fields}
               {from_joins}
               WHERE r.receipt_no=%s AND r.deleted_at IS NULL AND p.deleted_at IS NULL AND s.deleted_at IS NULL""",
            (int(pid),),
        ).fetchone()
        if not item:
            raise ApiError(404, "not_found", "السند غير موجود")
        item = row_to_json(item)
    else:
        item = _one(
            conn,
            f"""SELECT {select_fields}
               {from_joins}
               WHERE p.id=%s AND r.deleted_at IS NULL AND p.deleted_at IS NULL AND s.deleted_at IS NULL""",
            (pid,),
            "الدفعة غير موجودة",
        )

    amount = float(item["amount"])
    bus_amount = float(item.get("bus_amount") or 0)
    reg_paid = amount - bus_amount
    has_bus = bool(item.get("has_bus_subscription") or bus_amount > 0)
    bus_fee = float(item.get("bus_fee") or 0)
    bus_paid_amount = bus_amount
    bus_remaining = float(item.get("bus_remaining") or 0)

    phone = item.get("guardian_phone") or item.get("father_phone") or item.get("mother_phone")
    guardian_whatsapp = _normalize_guardian_whatsapp(phone)
    payment_ref = item.get("payment_id") or pid
    public_pdf_url = f"https://gheras.autovem.tech/api/v1/public/receipts/{payment_ref}/pdf"

    return _resp({
        "template": "receipt",
        "receipt_no": item["receipt_no"],
        "date": _date(item["issued_on"]),
        "student_name": item["student_name"],
        "method": item["method"],
        "amount": amount,
        "registration_paid_amount": reg_paid,
        "installment_ref": item["seq_no"] or "—",
        "amount_words": arabic_amount_words(Decimal(str(item["amount"]))),
        "has_bus": has_bus,
        "bus_fee": bus_fee,
        "bus_paid_amount": bus_paid_amount,
        "bus_remaining": bus_remaining,
        "receiver_name": item.get("receiver_name") or "",
        "guardian_whatsapp": guardian_whatsapp,
        "public_pdf_url": public_pdf_url,
        **_installment_breakdown(conn, item["student_id"], reg_paid, item["balance"], item["fee_plan_id"]),
    }, conn)


@router.get("/print/guardian-card")
@router.get("/print/guardian-card/{student_id}")
def guardian_card(
    student_id: str | None = None,
    student: str | None = None,
    id: str | None = None,
    conn=Depends(get_conn),
    user: dict = _ROLES,
) -> dict:
    _check_perm(user, "students", "طباعة كرت ولي الأمر تتطلب صلاحية شؤون الطلاب")
    sid = student_id or student or id
    if not sid:
        raise ApiError(400, "validation_error", "معرف الطالب مطلوب")
    s = _one(
        conn,
        "SELECT s.*, r.name room_name FROM students s LEFT JOIN rooms r ON r.id=s.room_id WHERE s.id=%s AND s.deleted_at IS NULL",
        (sid,),
        "الطالب غير موجود",
    )
    return _resp({
        "template": "guardian_card",
        "card_no": f"GHR-{str(s['id'])[:6].upper()}",
        "national_id": s.get("national_id") or "—",
        "date": _today().isoformat(),
        "student_name": s["name"],
        "photo_url": photo_data_uri(s["id"]),
        "group_name": s["group_name"] or "—",
        "room_name": s["room_name"] or "—",
        "father_phone": s["father_phone"] or "—",
        "mother_phone": s["mother_phone"] or "—",
        "guardian_phone": s["guardian_phone"] or "—",
        "pickup_type": s["pickup_type"] or "ولي الأمر",
        "pickup_name": s["pickup_name"] or "—",
        "pickup_relation": s["pickup_relation"] or "—",
        "pickup_phone": s["pickup_phone"] or "—",
    }, conn)


@router.get("/print/certificate")
@router.get("/print/certificate/{certificate_id}")
def certificate(
    certificate_id: str | None = None,
    student_id: str | None = None,
    student: str | None = None,
    reason: str = "التفوق والتميز الدراسي",
    conn=Depends(get_conn),
    user: dict = _ROLES,
) -> dict:
    _check_perm(user, "students", "طباعة شهادة التميز تتطلب صلاحية شؤون الطلاب")
    cid = certificate_id
    sid = student_id or student
    if cid and cid != "null":
        c = conn.execute(
            "SELECT c.*, s.name student_name FROM certificates c JOIN students s ON s.id=c.student_id WHERE c.id=%s AND c.deleted_at IS NULL AND s.deleted_at IS NULL",
            (cid,),
        ).fetchone()
        if c:
            c = row_to_json(c)
            return _resp({
                "template": "excellence_certificate",
                "title": c["title"],
                "certificate_no": c["id"],
                "date": _date(c["issued_on"]),
                "student_name": c["student_name"],
                "photo_url": photo_data_uri(c["student_id"]),
                "reason": c["reason"] or reason,
            }, conn)
        if not sid:
            raise ApiError(404, "not_found", "الشهادة غير موجودة")
    if sid:
        s = _one(conn, "SELECT name FROM students WHERE id = %s AND deleted_at IS NULL", (sid,), "الطالب غير موجود")
        short_id = str(sid)[:8] if len(str(sid)) > 8 else str(sid)
        return _resp({
            "template": "excellence_certificate",
            "title": "شهادة تفوق وتميز",
            "certificate_no": f"CERT-{short_id}",
            "date": _today().isoformat(),
            "student_name": s["name"],
            "photo_url": photo_data_uri(sid),
            "reason": reason or "التفوق والتميز الدراسي",
        }, conn)
    raise ApiError(400, "validation_error", "معرف الشهادة أو الطالب مطلوب")


@router.get("/print/student-report")
@router.get("/print/student-report/{student_id}")
def student_report(
    student_id: str | None = None,
    student: str | None = None,
    id: str | None = None,
    conn=Depends(get_conn),
    user: dict = _ROLES,
) -> dict:
    _check_perm(user, "students", "طباعة تقرير الطالب تتطلب صلاحية شؤون الطلاب")
    sid = student_id or student or id
    if not sid:
        raise ApiError(400, "validation_error", "معرف الطالب مطلوب")
    s = _one(
        conn,
        "SELECT s.*, r.name room_name FROM students s LEFT JOIN rooms r ON r.id=s.room_id WHERE s.id=%s AND s.deleted_at IS NULL",
        (sid,),
        "الطالب غير موجود",
    )
    counts = row_to_json(
        conn.execute(
            "SELECT count(*) FILTER(WHERE status='حاضر') present, count(*) FILTER(WHERE status='غائب') absent, "
            "count(*) FILTER(WHERE status='متأخر') late, count(*) FILTER(WHERE status='مستأذن') excused "
            "FROM student_attendance WHERE student_id=%s AND deleted_at IS NULL",
            (sid,),
        ).fetchone()
    )
    total = sum(counts.values())
    teacher = ""
    ev = [
        {"subject": x["subject"], "eval_type": x["eval_type"], "date": _date(x["date"]), "value": x["value"], "teacher": x["teacher"] or ""}
        for x in conn.execute(
            "SELECT e.*, u.username teacher FROM evaluations e LEFT JOIN users u ON u.id=e.teacher_user_id WHERE e.student_id=%s AND e.deleted_at IS NULL ORDER BY e.date DESC",
            (sid,),
        ).fetchall()
    ]
    skills = [
        {"subject": x["subject"], "skill": x["skill"], "level": x["level"], "date": _date(x["date"])}
        for x in conn.execute(
            "SELECT * FROM skill_progress WHERE student_id=%s AND deleted_at IS NULL ORDER BY date DESC",
            (sid,),
        ).fetchall()
    ]
    plans = [
        {"subject": x["subject"], "skill": x["skill"], "goal": x["goal"] or "", "start_date": _date(x["start_date"]), "end_date": _date(x["end_date"])}
        for x in conn.execute(
            "SELECT * FROM study_plans WHERE student_id=%s AND deleted_at IS NULL ORDER BY start_date DESC",
            (sid,),
        ).fetchall()
    ]
    return _resp({
        "template": "student_report",
        "report_no": str(sid),
        "date": _today().isoformat(),
        "student_name": s["name"],
        "photo_url": photo_data_uri(sid),
        "group_name": s["group_name"] or "",
        "room_name": s["room_name"] or "",
        "guardian_phone": s["guardian_phone"] or "",
        "present_count": counts["present"],
        "absent_count": counts["absent"],
        "late_count": counts["late"],
        "excused_count": counts["excused"],
        "attendance_pct": round((counts["present"] + counts["late"]) * 100 / total, 2) if total else 0,
        "teacher_notes": teacher,
        "evaluations": ev,
        "skills": skills,
        "plans": plans,
    }, conn)


@router.get("/print/admin-report")
def admin_report(from_: str = Query(alias="from"), to: str = Query(), conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    _check_perm(user, "finance", "طباعة التقرير الإداري تتطلب صلاحية المالية")
    students = conn.execute("SELECT count(*) count FROM students WHERE deleted_at IS NULL").fetchone()["count"]
    att = conn.execute(
        "SELECT count(*) FILTER(WHERE status IN ('حاضر','متأخر')) good, count(*) total FROM student_attendance WHERE date BETWEEN %s AND %s AND deleted_at IS NULL",
        (from_, to),
    ).fetchone()
    fin = conn.execute("SELECT coalesce(sum(amount),0) total FROM payments WHERE paid_on BETWEEN %s AND %s AND deleted_at IS NULL", (from_, to)).fetchone()["total"]
    exp = conn.execute("SELECT coalesce(sum(amount),0) total FROM expenses WHERE paid_on BETWEEN %s AND %s AND deleted_at IS NULL", (from_, to)).fetchone()["total"]
    outstanding_row = conn.execute("SELECT coalesce(sum(balance),0) total FROM v_student_balance").fetchone()
    return _resp({
        "template": "admin_report",
        "period": f"{from_} - {to}",
        "date": _today().isoformat(),
        "students_count": students,
        "attendance_pct": round(att["good"] * 100 / att["total"], 2) if att["total"] else 0,
        "collected": fin,
        "outstanding": outstanding_row["total"] if outstanding_row else 0,
        "expenses": exp,
        "net": fin - exp,
        "by_group": [
            {"group": x["group_label"], "students": x["students"], "attendance_pct": 0}
            for x in conn.execute("SELECT group_name AS group_label, count(*) students FROM students WHERE deleted_at IS NULL GROUP BY group_name").fetchall()
        ],
        "top_outstanding": [
            {"student": x["student_name"], "amount": x["balance"]}
            for x in conn.execute("SELECT * FROM v_student_balance ORDER BY balance DESC LIMIT 10").fetchall()
        ],
        "recent_expenses": [
            {"description": x["description"], "category": x["category"], "amount": x["amount"], "date": _date(x["paid_on"])}
            for x in conn.execute(
                "SELECT e.*, c.name category FROM expenses e JOIN expense_categories c ON c.id=e.category_id WHERE e.deleted_at IS NULL ORDER BY paid_on DESC LIMIT 10"
            ).fetchall()
        ],
    }, conn)


@router.get("/print/monthly-report")
def monthly_report(month: str, conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    _check_perm(user, "finance", "طباعة التقرير الشهري تتطلب صلاحية المالية")
    start = date.fromisoformat(month + "-01")
    end = date(start.year + 1, 1, 1) if start.month == 12 else date(start.year, start.month + 1, 1)
    pay = conn.execute("SELECT coalesce(sum(amount),0) total FROM payments WHERE paid_on>=%s AND paid_on<%s AND deleted_at IS NULL", (start, end)).fetchone()["total"]
    exp = conn.execute("SELECT coalesce(sum(amount),0) total FROM expenses WHERE paid_on>=%s AND paid_on<%s AND deleted_at IS NULL", (start, end)).fetchone()["total"]
    payroll = conn.execute("SELECT coalesce(sum(net),0) total FROM payroll_runs WHERE month=%s AND deleted_at IS NULL", (month,)).fetchone()["total"]
    return _resp({
        "template": "monthly_report",
        "month": month,
        "date": _today().isoformat(),
        "total_income": pay,
        "total_expenses": exp,
        "payroll_total": payroll,
        "net": pay - exp - payroll,
        "closed_by": "",
        "closed_at": "",
        "collections": [
            {"student": x["student"], "amount": x["amount"], "method": x["method"], "date": _date(x["paid_on"])}
            for x in conn.execute(
                "SELECT s.name student, p.amount, p.method, p.paid_on FROM payments p JOIN students s ON s.id=p.student_id WHERE p.paid_on>=%s AND p.paid_on<%s AND p.deleted_at IS NULL",
                (start, end),
            ).fetchall()
        ],
        "expense_categories": [
            {"category": x["category"], "amount": x["amount"]}
            for x in conn.execute(
                "SELECT c.name category, sum(e.amount) amount FROM expenses e JOIN expense_categories c ON c.id=e.category_id WHERE e.paid_on>=%s AND e.paid_on<%s AND e.deleted_at IS NULL GROUP BY c.name",
                (start, end),
            ).fetchall()
        ],
    }, conn)


@router.get("/print/schedule")
def schedule(room: str | None = None, room_id: str | None = None, conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    rid = room or room_id
    if not rid:
        raise ApiError(400, "validation_error", "معرف القاعة مطلوب")
    r = _one(conn, "SELECT * FROM rooms WHERE id=%s AND deleted_at IS NULL", (rid,), "القاعة غير موجودة")
    rows = conn.execute(
        "SELECT sc.*, COALESCE(st.name, u.username) AS teacher FROM schedules sc "
        "LEFT JOIN users u ON u.id = sc.teacher_user_id "
        "LEFT JOIN staff st ON st.id = u.staff_id "
        "WHERE sc.room_id = %s AND sc.deleted_at IS NULL ORDER BY sc.start_time",
        (rid,),
    ).fetchall()
    periods = {}
    for x in rows:
        key = (str(x["start_time"]), str(x["end_time"]))
        periods.setdefault(key, {"start_time": key[0], "end_time": key[1], "sat": "", "sun": "", "mon": "", "tue": "", "wed": "", "thu": ""})[
            {"السبت": "sat", "الأحد": "sun", "الاثنين": "mon", "الثلاثاء": "tue", "الأربعاء": "wed", "الخميس": "thu"}.get(x["day"], "sat")
        ] = f"{x['subject']} — {x['teacher']}" if x["teacher"] else x["subject"]
    return _resp({"template": "schedule", "room": r["name"], "group": r["group_name"] or "—", "periods": list(periods.values())}, conn)


@router.get("/print/attendance-report")
def attendance_report(from_: str = Query(alias="from"), to: str = Query(), conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    _check_perm(user, "attendance", "طباعة تقرير الحضور والغياب تتطلب صلاحية الحضور والغياب")
    dates = [d["date"] for d in conn.execute("SELECT generate_series(%s::date, %s::date, '1 day')::date date", (from_, to)).fetchall()]
    rows = conn.execute("SELECT s.id, s.name FROM students s WHERE s.deleted_at IS NULL ORDER BY s.name").fetchall()
    records = conn.execute("SELECT student_id, date, status FROM student_attendance WHERE date BETWEEN %s AND %s AND deleted_at IS NULL", (from_, to)).fetchall()
    lookup = {(x["student_id"], x["date"]): x["status"] for x in records}
    symbols = {"حاضر": "✓", "غائب": "✗", "متأخر": "م", "مستأذن": "ع"}
    output = []
    for s in rows:
        cells = [{"symbol": symbols.get(lookup.get((s["id"], d)), "")} for d in dates]
        output.append({
            "student_name": s["name"],
            "present_count": sum(c["symbol"] == "✓" for c in cells),
            "absent_count": sum(c["symbol"] == "✗" for c in cells),
            "cells": cells,
        })
    return _resp({"template": "attendance_report", "from": from_, "to": to, "date": _today().isoformat(), "dates": [{"date": _date(d)} for d in dates], "rows": output}, conn)


@router.get("/print/student-receipt")
@router.get("/print/student-receipt/{student_id}")
def student_receipt(
    student_id: str | None = None,
    student: str | None = None,
    id: str | None = None,
    conn=Depends(get_conn),
    user: dict = _ROLES,
) -> dict:
    _check_perm(user, "finance", "طباعة كشف حساب الطالب تتطلب صلاحية المالية")
    sid = student_id or student or id
    if not sid:
        raise ApiError(400, "validation_error", "معرف الطالب مطلوب")
    s = _one(
        conn,
        "SELECT s.*, r.name room_name, b.total_planned, b.total_paid, b.balance FROM students s LEFT JOIN rooms r ON r.id=s.room_id LEFT JOIN v_student_balance b ON b.student_id=s.id WHERE s.id=%s AND s.deleted_at IS NULL",
        (sid,),
        "الطالب غير موجود",
    )
    plan = conn.execute("SELECT * FROM fee_plans WHERE student_id=%s AND deleted_at IS NULL ORDER BY created_at DESC LIMIT 1", (sid,)).fetchone()
    installments = (
        []
        if not plan
        else [
            {
                "seq": x["seq_no"],
                "due_date": _date(x["due_date"]),
                "amount": x["amount"],
                "paid_amount": x["paid_amount"],
                "status": {"pending": "غير مدفوع", "partial": "جزئي", "paid": "مدفوع"}.get(x["status"], x["status"]),
            }
            for x in conn.execute("SELECT * FROM installments WHERE fee_plan_id=%s AND deleted_at IS NULL ORDER BY seq_no", (plan["id"],)).fetchall()
        ]
    )
    return _resp({
        "template": "student_receipt",
        "statement_no": str(sid),
        "date": _today().isoformat(),
        "student_name": s["name"],
        "group_name": s["group_name"] or "",
        "room_name": s["room_name"] or "",
        "guardian_phone": s["guardian_phone"] or "",
        "total_amount": s["total_planned"] or 0,
        "installments_count": plan["count"] if plan else 0,
        "interval_days": plan["interval_days"] if plan else 0,
        "total_paid": s["total_paid"] or 0,
        "balance": s["balance"] or 0,
        "installments": installments,
        "payments": [
            {"date": _date(x["paid_on"]), "receipt_no": x["receipt_no"] or "—", "method": x["method"], "amount": x["amount"]}
            for x in conn.execute(
                "SELECT p.*, r.receipt_no FROM payments p LEFT JOIN receipts r ON r.payment_id=p.id AND r.deleted_at IS NULL WHERE p.student_id=%s AND p.deleted_at IS NULL ORDER BY paid_on",
                (sid,),
            ).fetchall()
        ],
    }, conn)


@router.get("/print/lesson-log")
def lesson_log(
    schedule: str | None = None,
    room: str | None = None,
    from_: str = Query(alias="from"),
    to: str = Query(),
    conn=Depends(get_conn),
    user: dict = _ROLES,
) -> dict:
    _check_perm(user, "attendance", "طباعة سجل الحصص تتطلب صلاحية الحضور والغياب")
    if room:
        r = _one(conn, "SELECT name, group_name FROM rooms WHERE id=%s AND deleted_at IS NULL", (room,), "القاعة غير موجودة")
        logs_rows = conn.execute(
            """SELECT l.*, sc.subject, u.username teacher
               FROM lesson_logs l
               JOIN schedules sc ON sc.id = l.schedule_id
               LEFT JOIN users u ON u.id = sc.teacher_user_id
               WHERE sc.room_id = %s AND l.date BETWEEN %s AND %s AND l.deleted_at IS NULL
               ORDER BY l.date""",
            (room, from_, to),
        ).fetchall()
        logs = [
            {"date": _date(x["date"]), "status": x["status"], "covered": x["covered"] or "—", "homework": x["homework"] or "—", "notes": x["notes"] or "—"}
            for x in logs_rows
        ]
        return _resp({
            "template": "lesson_log",
            "room": r["name"],
            "subject": "سجل حصص القاعة",
            "teacher": "كادر المركز",
            "from": from_,
            "to": to,
            "date": _today().isoformat(),
            "logs": logs,
        }, conn)
    if schedule:
        s = _one(
            conn,
            "SELECT sc.*, r.name room, coalesce(st.name, u.username) teacher FROM schedules sc JOIN rooms r ON r.id=sc.room_id LEFT JOIN users u ON u.id=sc.teacher_user_id LEFT JOIN staff st ON st.id=u.staff_id WHERE sc.id=%s AND sc.deleted_at IS NULL",
            (schedule,),
            "الحصة غير موجودة",
        )
        logs = [
            {"date": _date(x["date"]), "status": x["status"], "covered": x["covered"] or "", "homework": x["homework"] or "", "notes": x["notes"] or ""}
            for x in conn.execute(
                "SELECT * FROM lesson_logs WHERE schedule_id=%s AND date BETWEEN %s AND %s AND deleted_at IS NULL ORDER BY date",
                (schedule, from_, to),
            ).fetchall()
        ]
        return _resp({
            "template": "lesson_log",
            "room": s["room"],
            "subject": s["subject"],
            "teacher": s["teacher"] or "",
            "from": from_,
            "to": to,
            "date": _today().isoformat(),
            "logs": logs,
        }, conn)
    raise ApiError(400, "validation_error", "معرف القاعة أو الحصة مطلوب")


@router.get("/print/statistics")
def statistics(from_: str = Query(alias="from"), to: str = Query(), conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    has_finance = user.get("role") == "manager" or user.get("permissions", {}).get("finance", False)
    total = conn.execute("SELECT count(*) count FROM students WHERE deleted_at IS NULL").fetchone()["count"]
    att = conn.execute(
        "SELECT count(*) FILTER(WHERE status IN ('حاضر','متأخر')) good, count(*) total FROM student_attendance WHERE date BETWEEN %s AND %s AND deleted_at IS NULL",
        (from_, to),
    ).fetchone()
    collected = conn.execute("SELECT coalesce(sum(amount),0) total FROM payments WHERE paid_on BETWEEN %s AND %s AND deleted_at IS NULL", (from_, to)).fetchone()["total"] if has_finance else 0
    planned = conn.execute("SELECT coalesce(sum(total_planned),0) total FROM v_student_balance").fetchone()["total"] if has_finance else 0
    outstanding_row = conn.execute("SELECT coalesce(sum(balance),0) total FROM v_student_balance").fetchone() if has_finance else None
    group = conn.execute("SELECT coalesce(group_name,'') AS group_label, count(*) students FROM students WHERE deleted_at IS NULL GROUP BY group_name").fetchall()
    boys = conn.execute("SELECT count(*) count FROM students WHERE gender='بنين' AND deleted_at IS NULL").fetchone()["count"]
    girls = conn.execute("SELECT count(*) count FROM students WHERE gender='بنات' AND deleted_at IS NULL").fetchone()["count"]
    return _resp({
        "template": "statistics_report",
        "period": f"{from_} - {to}",
        "date": _today().isoformat(),
        "students_count": total,
        "attendance_pct": round(att["good"] * 100 / att["total"], 2) if att["total"] else 0,
        "collection_pct": round(collected * 100 / planned, 2) if planned else 0,
        "outstanding": outstanding_row["total"] if outstanding_row else 0,
        "boys_count": boys,
        "boys_pct": round(boys * 100 / total, 2) if total else 0,
        "girls_count": girls,
        "girls_pct": round(girls * 100 / total, 2) if total else 0,
        "notes": "",
        "by_group": [{"group": x["group_label"] or "عام", "students": x["students"], "pct": round(x["students"] * 100 / total, 2) if total else 0} for x in group],
    }, conn)


def _receipt_cache_dir() -> Path:
    p = Path(os.getenv("RECEIPT_PDF_CACHE_DIR", "/app/uploads/receipt_pdfs"))
    try:
        p.mkdir(parents=True, exist_ok=True)
        return p
    except Exception:
        fallback = Path("/tmp/edutrack_receipt_pdfs")
        fallback.mkdir(parents=True, exist_ok=True)
        return fallback


@router.post("/print/pdf/{template}")
async def print_pdf(template: str, request: Request) -> dict:
    """Render the template to PDF; the query string is the print page's own query string.

    Returns a short-lived download URL (see services/pdf.py for why it is not the response body).
    No DB dependency here: the in-process data call made by the renderer authenticates the
    bearer token and applies the template endpoint's role/permission checks.
    """
    authorization = request.headers.get("authorization", "")
    if not authorization.lower().startswith("bearer "):
        raise ApiError(401, "unauthorized", "غير مصرح")
    doc = await pdf.render_pdf(request.app, template, request.url.query, authorization)
    token = pdf.store_download(doc)

    # Persist receipt PDF to disk cache for instantaneous public link delivery
    if template == "receipt":
        try:
            qs = parse_qs(request.url.query)
            pid = qs.get("payment", [""])[0] or qs.get("payment_id", [""])[0] or qs.get("id", [""])[0]
            if pid:
                cache_dir = _receipt_cache_dir()
                (cache_dir / f"receipt_{pid}.pdf").write_bytes(doc.content)
        except Exception:
            pass

    return {
        "url": f"/api/v1/print/pdf/download/{token}",
        "filename": f"{doc.title}.pdf",
        "expires_in": pdf.DOWNLOAD_TTL_SECONDS,
    }


@router.get("/print/pdf/download/{token}")
async def print_pdf_download(token: str) -> Response:
    """The rendered file as an attachment. The 256-bit token is the authorization (capability URL)."""
    doc = pdf.get_download(token)
    if doc is None:
        raise ApiError(404, "not_found", "انتهت صلاحية رابط التنزيل، اضغط زر التنزيل مرة أخرى")
    return Response(
        doc.content,
        media_type="application/pdf",
        headers={"Content-Disposition": pdf.content_disposition(doc), "Cache-Control": "no-store"},
    )


@router.get("/public/receipts/{payment_ref}/pdf")
async def public_receipt_pdf(payment_ref: str, request: Request, conn=Depends(get_conn)) -> Response:
    """Public capability endpoint to view/download official receipt PDF without login."""
    item = conn.execute(
        """SELECT r.receipt_no, p.id AS payment_id, s.name AS student_name
           FROM payments p
           JOIN receipts r ON r.payment_id = p.id
           JOIN students s ON s.id = p.student_id
           WHERE (p.id::text = %s OR r.receipt_no::text = %s)
             AND p.deleted_at IS NULL AND r.deleted_at IS NULL AND s.deleted_at IS NULL
           LIMIT 1""",
        (payment_ref, payment_ref),
    ).fetchone()
    if not item:
        raise ApiError(404, "not_found", "سند القبض غير موجود أو تم حذفه")

    item = dict(item)
    receipt_no = item["receipt_no"]
    payment_id = str(item["payment_id"])
    filename = f"سند_قبض_{receipt_no}.pdf"

    cache_dir = _receipt_cache_dir()
    cache_candidates = [
        cache_dir / f"receipt_{payment_id}.pdf",
        cache_dir / f"receipt_{receipt_no}.pdf",
        cache_dir / f"receipt_{payment_ref}.pdf",
    ]

    for c in cache_candidates:
        if c.is_file() and c.stat().st_size > 0:
            return Response(
                c.read_bytes(),
                media_type="application/pdf",
                headers={
                    "Content-Disposition": f"inline; filename*=UTF-8''{quote(filename, safe='')}",
                    "Cache-Control": "public, max-age=86400",
                },
            )

    # Render on demand using internal system token
    mgr = conn.execute(
        "SELECT id, role, token_version FROM users WHERE role = 'manager' AND is_active = true AND deleted_at IS NULL LIMIT 1"
    ).fetchone()
    if not mgr:
        mgr = {"id": payment_id, "role": "manager", "token_version": 1}
    settings = get_settings()
    internal_token = issue_token(dict(mgr), settings)
    auth_header = f"Bearer {internal_token}"

    doc = await pdf.render_pdf(request.app, "receipt", f"payment={payment_id}", auth_header)
    try:
        (cache_dir / f"receipt_{payment_id}.pdf").write_bytes(doc.content)
    except Exception:
        pass

    return Response(
        doc.content,
        media_type="application/pdf",
        headers={
            "Content-Disposition": f"inline; filename*=UTF-8''{quote(filename, safe='')}",
            "Cache-Control": "public, max-age=86400",
        },
    )
