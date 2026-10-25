from datetime import date
from decimal import Decimal, InvalidOperation
from uuid import UUID

import psycopg
from fastapi import APIRouter, Depends, Request

from edutrack_api.audit import write_audit
from edutrack_api.auth import require_roles
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError, map_db_error
from edutrack_api.repositories.generic import GenericRepository
from edutrack_api.serializers import row_to_json
from edutrack_api.services.finance import (
    PAYMENT_METHODS,
    _money,
    allocate_payment,
    apply_advance_deduction,
    close_month,
    default_cash_account_id,
    generate_installments,
    issue_receipt,
    post_ledger_entry,
    resolve_expense_category,
    transfer_between_accounts,
)
from ..services.payment_service import PaymentService

RESOURCES = {
    "students": "students", "guardians": "guardians", "student-guardians": "student_guardians",
    "staff": "staff", "users": "users",
    "rooms": "rooms", "schedules": "schedules", "fee-plans": "fee_plans", "installments": "installments",
    "payments": "payments", "receipts": "receipts", "expenses": "expenses", "expense-categories": "expense_categories",
    "payroll-runs": "payroll_runs", "staff-advances": "staff_advances", "staff-assets": "staff_assets",
    "staff-attendance": "staff_attendance",
    "ledger-accounts": "ledger_accounts", "ledger-entries": "ledger_entries", "assignments": "assignments",
    "submissions": "submissions", "lesson-logs": "lesson_logs", "study-plans": "study_plans",
    "skill-progress": "skill_progress", "evaluations": "evaluations", "tasks": "tasks", "messages": "messages",
    "message-templates": "message_templates",
    "certificates": "certificates", "dismissals": "dismissals", "branches": "branches", "month-closures": "month_closures",
    "teacher-evaluations": "teacher_evaluations", "educational-tracks": "educational_tracks",
    "student-behavior-logs": "student_behavioral_logs", "room-hourly-schedules": "room_hourly_schedules",
}

router = APIRouter()


MAIN_BRANCH_ID = "00000000-0000-0000-0000-000000000001"


def _find_or_create_guardian(conn, branch_id: UUID | str, name: str, phone: str, relation: str = "ولي الأمر") -> UUID:
    existing = conn.execute("SELECT id FROM guardians WHERE phone = %s AND deleted_at IS NULL", (phone,)).fetchone()
    if existing:
        return existing["id"]
    new_g = conn.execute(
        "INSERT INTO guardians (branch_id, name, phone, relation) VALUES (%s, %s, %s, %s) RETURNING id",
        (branch_id, name, phone, relation),
    ).fetchone()
    return new_g["id"]


def _clean_user(row: dict) -> dict:
    result = dict(row)
    result.pop("password_hash", None)
    return result


def _clean_user_with_perms(conn, row: dict) -> dict:
    result = _clean_user(row)
    perms = conn.execute(
        "SELECT attendance, daily_evaluation, monthly_evaluation, students, finance "
        "FROM user_permissions WHERE user_id = %s AND deleted_at IS NULL",
        (row["id"],),
    ).fetchone()
    result["permissions"] = {
        "attendance": bool(perms["attendance"]) if perms else False,
        "daily_evaluation": bool(perms["daily_evaluation"]) if perms else False,
        "monthly_evaluation": bool(perms["monthly_evaluation"]) if perms else False,
        "students": bool(perms["students"]) if perms else False,
        "finance": bool(perms["finance"]) if perms else False,
    }
    # Linked metadata for rich UI display
    if row.get("staff_id"):
        st = conn.execute("SELECT name FROM staff WHERE id = %s", (row["staff_id"],)).fetchone()
        result["staff_name"] = st["name"] if st else None
        if row.get("room_id"):
            rm = conn.execute("SELECT name FROM rooms WHERE id = %s", (row["room_id"],)).fetchone()
            result["room_name"] = rm["name"] if rm else None
    elif row.get("guardian_id"):
        g = conn.execute("SELECT name, phone FROM guardians WHERE id = %s", (row["guardian_id"],)).fetchone()
        result["guardian_name"] = g["name"] if g else None
        children = conn.execute(
            "SELECT s.id, s.name FROM student_guardians sg "
            "JOIN students s ON s.id = sg.student_id "
            "WHERE sg.guardian_id = %s AND sg.deleted_at IS NULL AND s.deleted_at IS NULL",
            (row["guardian_id"],),
        ).fetchall()
        result["children"] = [{"id": str(c["id"]), "name": c["name"]} for c in children]
    elif row.get("student_id"):
        s = conn.execute("SELECT name, national_id, room_id FROM students WHERE id = %s", (row["student_id"],)).fetchone()
        result["student_name"] = s["name"] if s else None
        g_link = conn.execute(
            "SELECT g.id, g.name, g.phone FROM student_guardians sg "
            "JOIN guardians g ON g.id = sg.guardian_id "
            "WHERE sg.student_id = %s AND sg.deleted_at IS NULL AND g.deleted_at IS NULL "
            "ORDER BY sg.is_primary DESC LIMIT 1",
            (row["student_id"],),
        ).fetchone()
        result["linked_guardian"] = {"id": str(g_link["id"]), "name": g_link["name"], "phone": g_link["phone"]} if g_link else None
    return result


def _clean_staff(row: dict, user: dict) -> dict:
    result = dict(row)
    if user.get("role") == "supervisor":
        result["base_salary"] = None
    return result


def _format_row(conn, row: dict, table: str, user: dict) -> dict:
    json_row = row_to_json(row)
    if table == "users":
        return _clean_user_with_perms(conn, json_row)
    if table == "staff":
        return _clean_staff(json_row, user)
    return json_row


def _repo(request: Request, conn, table: str) -> GenericRepository:
    columns = request.app.state.columns.get(table)
    if columns is None:
        raise ApiError(422, "validation_error", "المورد غير متاح")
    return GenericRepository(conn, table, columns)


def _allowed(user: dict, table: str, write: bool = False, action: str = "read") -> None:
    if user["role"] == "manager":
        return

    # Branch management is strictly manager-only
    if table == "branches":
        raise ApiError(403, "forbidden", "إدارة الفروع مخصصة للمدير العام فقط")

    # User management: manager has full access; supervisor can read only
    if table == "users":
        if write and user["role"] != "manager":
            raise ApiError(403, "forbidden", "إدارة المستخدمين مخصصة للمدير العام فقط")
        if not write and user["role"] not in {"manager", "supervisor"}:
            raise ApiError(403, "forbidden", "إدارة المستخدمين مخصصة للمدير العام فقط")
        return

    if user["role"] == "supervisor":
        perms = user.get("permissions", {})

        # Staff and Rooms access
        staff_and_rooms = {"staff", "rooms", "schedules"}
        if table in staff_and_rooms:
            if write:
                raise ApiError(403, "forbidden", "إدارة الموظفين والقاعات مخصصة للمدير العام فقط")
            if not perms.get("students", False) and not perms.get("attendance", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية الوصول إلى الموظفين والقاعات")

        # General Finance & Ledgers - Supervisor is strictly quarantined from overhead, expenses, and accounts
        general_finance_tables = {
            "expenses", "expense_categories", "payroll_runs", "staff_advances",
            "ledger_accounts", "ledger_entries", "month_closures"
        }
        if table in general_finance_tables:
            raise ApiError(403, "forbidden", "ليس لديك صلاحية الوصول إلى البيانات والعمليات المالية (محظورة على المشرفة)")

        # Student Fees & Receipts - Decoupled: accessible if supervisor has students or finance permission
        student_fee_tables = {"payments", "receipts", "fee_plans", "installments"}
        if table in student_fee_tables:
            if not (perms.get("finance", False) or perms.get("students", False)):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية الوصول إلى رسوم وسندات الطلاب")
            if action in {"update", "delete"}:
                raise ApiError(403, "forbidden", "تعديل أو حذف الرسوم وسندات القبض مخصص للمدير العام فقط")
            if action == "create" and table in {"receipts", "fee_plans", "installments"}:
                raise ApiError(403, "forbidden", "إنشاء سجلات الخطط والأقساط والسندات المباشرة مخصص للمدير العام فقط")

        # Student resources & Behavioral logs
        student_tables = {"students", "guardians", "student_guardians", "dismissals", "student_behavioral_logs", "educational_tracks"}
        if table in student_tables and not perms.get("students", False):
            raise ApiError(403, "forbidden", "ليس لديك صلاحية الوصول إلى سجلات الطلاب")

        # Attendance resources
        attendance_tables = {"student_attendance", "staff_attendance"}
        if table in attendance_tables and not perms.get("attendance", False):
            raise ApiError(403, "forbidden", "ليس لديك صلاحية الوصول إلى الحضور والغياب")

        # Evaluation & tracking resources (Student & Teacher evaluations)
        evaluation_tables = {"evaluations", "skill_progress", "study_plans", "teacher_evaluations", "room_hourly_schedules"}
        if table in evaluation_tables and not (perms.get("daily_evaluation", False) or perms.get("monthly_evaluation", False) or table == "teacher_evaluations"):
            raise ApiError(403, "forbidden", "ليس لديك صلاحية الوصول إلى التقييمات")

        # Write restrictions on sensitive ledger closures
        if write and table in {"ledger_accounts", "ledger_entries", "payroll_runs", "month_closures"}:
            raise ApiError(403, "forbidden", "ليس لديك صلاحية التعديل على الحسابات والإقفالات المالية")

        return

    raise ApiError(403, "forbidden", "ليس لديك صلاحية")


def _permissions(conn, user_id, branch_id, values: dict) -> None:
    flags = {key: bool(values.get(key, False)) for key in ("attendance", "daily_evaluation", "monthly_evaluation", "students", "finance")}
    u = conn.execute("SELECT role FROM users WHERE id = %s", (user_id,)).fetchone()
    if u and u["role"] == "supervisor":
        flags["finance"] = False
    conn.execute(
        "INSERT INTO user_permissions (branch_id, user_id, attendance, daily_evaluation, monthly_evaluation, students, finance) "
        "VALUES (%(branch_id)s, %(user_id)s, %(attendance)s, %(daily_evaluation)s, %(monthly_evaluation)s, %(students)s, %(finance)s) "
        "ON CONFLICT (user_id) DO UPDATE SET attendance = EXCLUDED.attendance, daily_evaluation = EXCLUDED.daily_evaluation, "
        "monthly_evaluation = EXCLUDED.monthly_evaluation, students = EXCLUDED.students, finance = EXCLUDED.finance, deleted_at = NULL",
        flags | {"branch_id": branch_id, "user_id": user_id},
    )


def _audit_details(data: dict) -> dict:
    details = dict(data)
    details.pop("password", None)
    details.pop("password_hash", None)
    return details


def _registration_finance(data: dict) -> dict | None:
    """Parse registration-fee plan inputs (request-only keys, never student columns).

    Returns None when no fee applies (fee empty/0), otherwise a dict with:
    fee, paid, installments_count, next_due_date, interval_days.
    Raises 400 validation errors before anything is created.
    """
    method = data.get("payment_method")
    if method not in (None, ""):
        if method not in PAYMENT_METHODS:
            raise ApiError(400, "validation_error", "طريقة الدفع غير مدعومة")
    try:
        fee = _money(data.get("registration_fee"))
        paid = _money(data.get("initial_payment_amount"))
    except InvalidOperation:
        raise ApiError(400, "validation_error", "مبلغ الرسوم أو الدفعة الأولى غير صالح")
    if fee <= 0 and paid <= 0:
        return None
    if paid > fee:
        raise ApiError(400, "validation_error", "المبلغ المسدد أكبر من رسوم التسجيل المقررة")
    installments_count = data.get("installments_count")
    next_due_date = data.get("next_due_date")
    interval_days = data.get("interval_days")
    if paid < fee:
        raw_count = installments_count if installments_count not in (None, "") else 0
        try:
            count_value = int(raw_count)
        except (TypeError, ValueError):
            raise ApiError(400, "validation_error", "يجب تحديد عدد الدفعات المتبقية")
        if count_value < 1:
            raise ApiError(400, "validation_error", "يجب تحديد عدد الدفعات المتبقية")
        if not next_due_date:
            raise ApiError(400, "validation_error", "يجب تحديد تاريخ الدفعة القادمة بعد تاريخ اليوم")
        try:
            due_value = date.fromisoformat(str(next_due_date)[:10])
        except ValueError:
            raise ApiError(400, "validation_error", "يجب تحديد تاريخ الدفعة القادمة بعد تاريخ اليوم")
        if due_value <= date.today():
            raise ApiError(400, "validation_error", "يجب تحديد تاريخ الدفعة القادمة بعد تاريخ اليوم")
        try:
            interval_value = int(interval_days if interval_days not in (None, "") else 30)
        except (TypeError, ValueError):
            raise ApiError(400, "validation_error", "الفاصل بين الدفعات يجب أن يكون 7 أو 14 أو 30 يوماً")
        if interval_value not in (7, 14, 30):
            raise ApiError(400, "validation_error", "الفاصل بين الدفعات يجب أن يكون 7 أو 14 أو 30 يوماً")
        installments_count, next_due_date, interval_days = count_value, due_value, interval_value
    else:
        installments_count, next_due_date, interval_days = None, None, 30
    return {"fee": fee, "paid": paid, "installments_count": installments_count,
            "next_due_date": next_due_date, "interval_days": interval_days}


def _students_finance_flow(conn, row: dict, plan: dict | None, actor: dict = None, payment_method: str = "كاش") -> None:
    """Create the fee plan, generate installments, and when a down payment or bus payment exists
    run payment -> allocate -> receipt -> ledger entry, all on the same conn."""
    bus_paid = _money(row.get("bus_paid", 0)) if row.get("has_bus_subscription") else Decimal("0.00")
    reg_paid = _money(plan["paid"]) if plan else Decimal("0.00")
    total_paid = reg_paid + bus_paid

    if plan:
        count = (1 if plan["paid"] > 0 else 0) + (plan["installments_count"] if plan["paid"] < plan["fee"] else 0)
        plan_row = conn.execute(
            "INSERT INTO fee_plans (branch_id, student_id, total_amount, count, start_date, interval_days) "
            "VALUES (%s, %s, %s, %s, CURRENT_DATE, %s) RETURNING *",
            (row["branch_id"], row["id"], plan["fee"], count, plan["interval_days"]),
        ).fetchone()
        row["fee_plan"] = dict(plan_row)
        row["installments"] = generate_installments(
            conn,
            dict(plan_row),
            down_payment=plan["paid"] or None,
            first_due=plan["next_due_date"],
        )

    if total_paid > 0:
        method = (plan.get("method") if plan else None) or payment_method or "كاش"
        issued_by = actor.get("id") if actor else None
        pay_row = conn.execute(
            "INSERT INTO payments (branch_id, student_id, amount, bus_amount, paid_on, method, note) "
            "VALUES (%s, %s, %s, %s, CURRENT_DATE, %s, %s) RETURNING *",
            (row["branch_id"], row["id"], total_paid, bus_paid, method, "رسوم تسجيل الطالب / دفعة أولى"),
        ).fetchone()
        allocate_payment(conn, dict(pay_row), pay_row.get("installment_id"))
        row["initial_payment"] = dict(pay_row)
        row["initial_receipt"] = issue_receipt(conn, pay_row["id"], issued_by=issued_by)
        cash_acc = default_cash_account_id(conn, row["branch_id"])
        post_ledger_entry(conn, cash_acc, "in", total_paid, "payments", pay_row["id"], pay_row["paid_on"], row["branch_id"])


def _create_hook(conn, repo, table: str, data: dict, actor: dict) -> tuple[dict, dict]:
    details = _audit_details(data)
    if table == "users":
        from edutrack_api.auth import hash_password
        password = data.pop("password", None)
        permissions = data.pop("permissions", None)
        child_student_ids = data.pop("child_student_ids", None)
        legacy_student_ids = data.pop("student_ids", None)
        if child_student_ids is None:
            child_student_ids = legacy_student_ids
        guardian_name = data.pop("guardian_name", None)
        guardian_relation = data.pop("guardian_relation", None)
        if password:
            data["password_hash"] = hash_password(password)

        # For guardian role: auto-link or auto-create guardian identity
        if data.get("role") == "guardian":
            if not data.get("guardian_id"):
                phone = data.get("phone") or data.get("username")
                name = guardian_name or data.get("name") or data.get("username")
                relation = guardian_relation or "ولي الأمر"
                if phone:
                    branch_id = actor.get("branch_id") or MAIN_BRANCH_ID
                    data["guardian_id"] = _find_or_create_guardian(conn, branch_id, name, phone, relation)

        # Auto-inherit phone and national_id if not explicitly provided
        if not data.get("phone"):
            if data.get("staff_id"):
                s = conn.execute("SELECT phone FROM staff WHERE id = %s", (data["staff_id"],)).fetchone()
                if s and s["phone"]:
                    data["phone"] = s["phone"]
            elif data.get("guardian_id"):
                g = conn.execute("SELECT phone FROM guardians WHERE id = %s", (data["guardian_id"],)).fetchone()
                if g and g["phone"]:
                    data["phone"] = g["phone"]
            elif data.get("student_id"):
                st = conn.execute("SELECT national_id, guardian_phone, father_phone, mother_phone FROM students WHERE id = %s", (data["student_id"],)).fetchone()
                if st:
                    data["phone"] = st["guardian_phone"] or st["father_phone"] or st["mother_phone"]
                    if not data.get("national_id") and st["national_id"]:
                        data["national_id"] = st["national_id"]
        row = repo.create(data)

        # Relational linkage post-creation
        if row.get("role") == "guardian" and row.get("guardian_id") and child_student_ids:
            branch_id = row.get("branch_id") or MAIN_BRANCH_ID
            valid_ids = []
            for sid in child_student_ids:
                try:
                    valid_ids.append(UUID(str(sid)))
                except (ValueError, TypeError):
                    continue
            if valid_ids:
                existing_students = conn.execute(
                    "SELECT id FROM students WHERE id = ANY(%s) AND deleted_at IS NULL",
                    (valid_ids,),
                ).fetchall()
                for s in existing_students:
                    conn.execute(
                        "INSERT INTO student_guardians (branch_id, student_id, guardian_id, is_primary) "
                        "VALUES (%s, %s, %s, true) ON CONFLICT (student_id, guardian_id) DO NOTHING",
                        (branch_id, s["id"], row["guardian_id"]),
                    )
        elif row.get("role") == "student" and row.get("student_id"):
            st = conn.execute(
                "SELECT id, name, guardian_phone, guardian_relation, father_name, father_phone, mother_name, mother_phone "
                "FROM students WHERE id = %s",
                (row["student_id"],),
            ).fetchone()
            if st:
                g_phone = st.get("guardian_phone") or st.get("father_phone") or st.get("mother_phone")
                g_name = st.get("father_name") or st.get("mother_name") or f"ولي أمر {st['name']}"
                g_rel = st.get("guardian_relation") or "ولي الأمر"
                if g_phone:
                    branch_id = row.get("branch_id") or MAIN_BRANCH_ID
                    gid = _find_or_create_guardian(conn, branch_id, g_name, g_phone, g_rel)
                    conn.execute(
                        "INSERT INTO student_guardians (branch_id, student_id, guardian_id, is_primary) "
                        "VALUES (%s, %s, %s, true) ON CONFLICT (student_id, guardian_id) DO NOTHING",
                        (branch_id, st["id"], gid),
                    )

        if permissions is not None:
            _permissions(conn, row["id"], row["branch_id"], permissions)
        return row, details
    if table == "expenses":
        category = data.pop("category", None)
        if category is not None and not data.get("category_id"):
            data["category_id"] = resolve_expense_category(conn, category, actor.get("branch_id"))
        data["account_id"] = data.get("account_id") or default_cash_account_id(conn, actor.get("branch_id"))
    if table == "month_closures":
        row = close_month(conn, data["month"], actor["id"], actor.get("branch_id"))
        return row, details
    if table == "ledger_entries" and data.get("from_account_id") and data.get("to_account_id"):
        # dashboard transfer form: one request -> transfer_out on the source + transfer_in on the target
        row = transfer_between_accounts(conn, data, actor.get("branch_id"))
        return row, details
    if table == "students":
        bus_fee_raw = data.get("bus_fee")
        bus_paid_raw = data.get("bus_paid")
        has_finance_action = bool(
            data.get("registration_fee") or data.get("initial_payment_amount") or
            (bus_fee_raw not in (None, "", 0, "0", 0.0)) or
            (bus_paid_raw not in (None, "", 0, "0", 0.0))
        )
        if has_finance_action:
            perms = actor.get("permissions") or {}
            if actor.get("role") != "manager" and not perms.get("finance", False) and not perms.get("students", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية تسجيل العمليات المالية أو شؤون الطلاب")

        # Allergies validation
        has_allergies = data.get("has_allergies")
        if has_allergies is not None:
            has_allergies = bool(has_allergies)
            data["has_allergies"] = has_allergies
            if has_allergies:
                notes = (data.get("allergy_notes") or "").strip()
                if not notes:
                    raise ApiError(400, "validation_error", "يرجى توضيح نوع الحساسية (غذائية أو دوائية)")
                data["allergy_notes"] = notes
            else:
                data["allergy_notes"] = None

        # Bus subscription validation
        has_bus = bool(data.get("has_bus_subscription"))
        data["has_bus_subscription"] = has_bus
        bus_fee = _money(data.get("bus_fee", 0)) if data.get("bus_fee") is not None else Decimal("0.00")
        bus_paid = _money(data.get("bus_paid", 0)) if data.get("bus_paid") is not None else Decimal("0.00")

        if not has_bus:
            if bus_fee > 0 or bus_paid > 0:
                raise ApiError(400, "validation_error", "يجب تفعيل اشتراك الباص قبل إدخال رسومه")
            data["bus_fee"] = Decimal("0.00")
            data["bus_paid"] = Decimal("0.00")
        else:
            if bus_fee < 0 or bus_paid < 0:
                raise ApiError(400, "validation_error", "رسوم اشتراك الباص غير صالحة")
            if bus_paid > bus_fee:
                raise ApiError(400, "validation_error", "المبلغ المسدد للباص أكبر من رسوم الاشتراك")
            data["bus_fee"] = bus_fee
            data["bus_paid"] = bus_paid

        raw_method = data.get("payment_method")
        chosen_method = str(raw_method).strip() if raw_method not in (None, "") else "كاش"
        if chosen_method not in PAYMENT_METHODS:
            raise ApiError(400, "validation_error", "طريقة الدفع غير مدعومة")
        registration_plan = _registration_finance(data)
        if registration_plan:
            registration_plan["method"] = chosen_method
        for request_key in ("installments_count", "next_due_date", "interval_days", "payment_method"):
            data.pop(request_key, None)
    elif table == "teacher_evaluations":
        if "staff_id" in data:
            data["teacher_id"] = data.pop("staff_id")
        if "ethics_score" in data:
            data["discipline_score"] = data.pop("ethics_score")
        if "notes" in data:
            data["supervisor_notes"] = data.pop("notes")
        if not data.get("supervisor_id"):
            supervisor_id = actor.get("staff_id")
            if not supervisor_id:
                s_row = conn.execute(
                    "SELECT id FROM staff WHERE deleted_at IS NULL ORDER BY created_at, id LIMIT 1"
                ).fetchone()
                if s_row:
                    supervisor_id = s_row["id"]
                else:
                    branch_id = actor.get("branch_id") or MAIN_BRANCH_ID
                    new_staff = conn.execute(
                        "INSERT INTO staff (branch_id, name, role_title, base_salary) "
                        "VALUES (%s, %s, %s, 0) RETURNING id",
                        (branch_id, actor.get("name") or actor.get("username") or "إدارة المركز", "إداري"),
                    ).fetchone()
                    supervisor_id = new_staff["id"]
            if supervisor_id:
                data["supervisor_id"] = supervisor_id
    elif table == "student_behavioral_logs":
        if "type" in data:
            data["log_type"] = data.pop("type")
        if "guardian_notified" in data:
            data["notified_guardian"] = data.pop("guardian_notified")
        title = data.pop("title", None)
        if title:
            note = data.get("note")
            data["note"] = f"{title}: {note}" if note else title
        if not data.get("author_id"):
            author_id = actor.get("staff_id")
            if not author_id:
                s_row = conn.execute(
                    "SELECT id FROM staff WHERE deleted_at IS NULL ORDER BY created_at, id LIMIT 1"
                ).fetchone()
                if s_row:
                    author_id = s_row["id"]
                else:
                    branch_id = actor.get("branch_id") or MAIN_BRANCH_ID
                    new_staff = conn.execute(
                        "INSERT INTO staff (branch_id, name, role_title, base_salary) "
                        "VALUES (%s, %s, %s, 0) RETURNING id",
                        (branch_id, actor.get("name") or actor.get("username") or "إدارة المركز", "إداري"),
                    ).fetchone()
                    author_id = new_staff["id"]
            if author_id:
                data["author_id"] = author_id
    elif table == "payments":
        PaymentService.validate_and_prepare_payment(conn, data, actor=actor)
    row = repo.create(data)
    if table == "students":
        g_phone = data.get("guardian_phone") or data.get("father_phone") or data.get("mother_phone")
        g_name = data.get("father_name") or data.get("mother_name") or f"ولي أمر {data.get('name', '')}"
        g_rel = data.get("guardian_relation") or "ولي الأمر"
        if g_phone:
            branch_id = row.get("branch_id") or MAIN_BRANCH_ID
            gid = _find_or_create_guardian(conn, branch_id, g_name, g_phone, g_rel)
            conn.execute(
                "INSERT INTO student_guardians (branch_id, student_id, guardian_id, is_primary) "
                "VALUES (%s, %s, %s, true) ON CONFLICT (student_id, guardian_id) DO NOTHING",
                (branch_id, row["id"], gid),
            )
        # Automatic registration fee / installment plan / bus payment processing
        if registration_plan or (row.get("has_bus_subscription") and _money(row.get("bus_paid", 0)) > 0):
            _students_finance_flow(conn, row, registration_plan, actor=actor, payment_method=chosen_method)
    elif table == "fee_plans":
        row["installments"] = generate_installments(conn, row)
    elif table == "payments":
        row = PaymentService.finalize_payment_settlement(conn, row, actor=actor)
    elif table == "expenses":
        post_ledger_entry(conn, row["account_id"], "out", row["amount"], "expenses", row["id"], row["paid_on"], row["branch_id"])
    elif table == "payroll_runs":
        deduction = apply_advance_deduction(conn, row["staff_id"], row["month"], data)
        if deduction != row["advance_deducted"]:
            row = conn.execute("UPDATE payroll_runs SET advance_deducted = %s WHERE id = %s RETURNING *", (deduction, row["id"])).fetchone()
            row = dict(row)
    return row, details


def _update_hook(conn, repo, table: str, record_id: UUID, data: dict, actor: dict = None) -> tuple[dict | None, dict]:
    details = _audit_details(data)
    if table == "students":
        if "has_bus_subscription" in data or "bus_fee" in data or "bus_paid" in data:
            curr = conn.execute(
                "SELECT branch_id, has_bus_subscription, bus_fee, bus_paid FROM students WHERE id = %s",
                (record_id,)
            ).fetchone()
            if curr:
                raw_has_bus = data.get("has_bus_subscription", curr["has_bus_subscription"])
                if isinstance(raw_has_bus, str):
                    has_bus = raw_has_bus.lower() in ("true", "1")
                else:
                    has_bus = bool(raw_has_bus)

                # G4 Lock: Non-managers cannot cancel bus subscription or lower fees/paid
                if actor and actor.get("role") != "manager":
                    if curr.get("has_bus_subscription") and not has_bus:
                        raise ApiError(403, "forbidden", "إلغاء اشتراك الباص مخصص للمدير العام فقط")
                    if "bus_fee" in data and data["bus_fee"] not in (None, ""):
                        if _money(data["bus_fee"]) < _money(curr.get("bus_fee", 0)):
                            raise ApiError(403, "forbidden", "تعديل أو تخفيض رسوم الباص مخصص للمدير العام فقط")
                    if "bus_paid" in data and data["bus_paid"] not in (None, ""):
                        if _money(data["bus_paid"]) < _money(curr.get("bus_paid", 0)):
                            raise ApiError(403, "forbidden", "تخفيض المبلغ المسدد للباص مخصص للمدير العام فقط")

                data["has_bus_subscription"] = has_bus

                if not has_bus:
                    data["bus_fee"] = Decimal("0.00")
                    data["bus_paid"] = Decimal("0.00")
                else:
                    bus_fee = _money(data["bus_fee"]) if "bus_fee" in data and data["bus_fee"] not in (None, "") else _money(curr["bus_fee"])
                    bus_paid = _money(data["bus_paid"]) if "bus_paid" in data and data["bus_paid"] not in (None, "") else _money(curr["bus_paid"])
                    if bus_fee < 0 or bus_paid < 0:
                        raise ApiError(400, "validation_error", "رسوم اشتراك الباص غير صالحة")
                    if bus_paid > bus_fee:
                        raise ApiError(400, "validation_error", "المبلغ المسدد للباص أكبر من رسوم الاشتراك")
                    data["bus_fee"] = bus_fee
                    data["bus_paid"] = bus_paid

                    old_bus_paid = _money(curr["bus_paid"])
                    if bus_paid > old_bus_paid:
                        diff = bus_paid - old_bus_paid
                        branch_id = curr["branch_id"] or MAIN_BRANCH_ID
                        cash_acc = default_cash_account_id(conn, branch_id)
                        pay_row = conn.execute(
                            "INSERT INTO payments (branch_id, student_id, amount, bus_amount, paid_on, method, note) "
                            "VALUES (%s, %s, %s, %s, CURRENT_DATE, %s, %s) RETURNING *",
                            (branch_id, record_id, diff, diff, "كاش", "سداد اشتراك الباص / دفعة إضافية"),
                        ).fetchone()
                        issued_by = actor.get("id") if actor else None
                        issue_receipt(conn, pay_row["id"], issued_by=issued_by)
                        post_ledger_entry(conn, cash_acc, "in", diff, "payments", pay_row["id"], pay_row["paid_on"], branch_id)

        if "has_allergies" in data:
            has_allergies = bool(data["has_allergies"])
            data["has_allergies"] = has_allergies
            if has_allergies:
                if "allergy_notes" in data:
                    notes = (data.get("allergy_notes") or "").strip()
                    if not notes:
                        raise ApiError(400, "validation_error", "يرجى توضيح نوع الحساسية (غذائية أو دوائية)")
                    data["allergy_notes"] = notes
                else:
                    curr = conn.execute("SELECT allergy_notes FROM students WHERE id = %s", (record_id,)).fetchone()
                    if not (curr and (curr["allergy_notes"] or "").strip()):
                        raise ApiError(400, "validation_error", "يرجى توضيح نوع الحساسية (غذائية أو دوائية)")
            else:
                data["allergy_notes"] = None
        elif "allergy_notes" in data:
            notes = (data.get("allergy_notes") or "").strip()
            curr = conn.execute("SELECT has_allergies FROM students WHERE id = %s", (record_id,)).fetchone()
            if curr and curr["has_allergies"]:
                if not notes:
                    raise ApiError(400, "validation_error", "يرجى توضيح نوع الحساسية (غذائية أو دوائية)")
                data["allergy_notes"] = notes
            else:
                data["allergy_notes"] = None
    if table == "users":
        from edutrack_api.auth import hash_password
        password = data.pop("password", None)
        permissions = data.pop("permissions", None)
        if password:
            data["password_hash"] = hash_password(password)
        row = repo.update(record_id, data)
        if row and permissions is not None:
            _permissions(conn, row["id"], row["branch_id"], permissions)
        return row, details
    row = repo.update(record_id, data)
    if table == "students" and row and "national_id" in data:
        conn.execute(
            "UPDATE users SET national_id = %s WHERE student_id = %s AND role = 'student' AND deleted_at IS NULL",
            (row.get("national_id"), record_id),
        )
    return row, details


def _register(path: str, table: str) -> None:
    def list_rows(request: Request, limit: int = 100, offset: int = 0, q: str | None = None,
                  user: dict = Depends(require_roles("manager", "supervisor")), conn=Depends(get_conn)):
        _allowed(user, table, False, action="read")
        if not 1 <= limit <= 500 or offset < 0:
            raise ApiError(422, "validation_error", "قيم التصفح غير صحيحة")
        filters = {key: value for key, value in request.query_params.items() if key not in {"limit", "offset", "q"}}
        if table == "evaluations" and user.get("role") == "supervisor":
            perms = user.get("permissions", {})
            has_daily = perms.get("daily_evaluation", False)
            has_monthly = perms.get("monthly_evaluation", False)
            requested_type = filters.get("eval_type")
            if requested_type == "daily" and not has_daily:
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم اليومي")
            if requested_type in {"monthly", "weekly"} and not has_monthly:
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم الشهري والنهائي")
            if not requested_type:
                if has_daily and not has_monthly:
                    filters["eval_type"] = "daily"
                elif has_monthly and not has_daily:
                    filters["eval_type"] = "monthly"
        try:
            rows, total = _repo(request, conn, table).list(filters=filters, limit=limit, offset=offset, q=q)
        except ValueError as exc:
            raise ApiError(422, "validation_error", "حقل التصفية غير صحيح") from exc
        return {"items": [_format_row(conn, row, table, user) for row in rows], "total": total, "limit": limit, "offset": offset}

    def get_row(record_id: UUID, request: Request, user: dict = Depends(require_roles("manager", "supervisor")), conn=Depends(get_conn)):
        _allowed(user, table, False, action="read")
        row = _repo(request, conn, table).get(record_id)
        if not row:
            raise ApiError(404, "not_found", "السجل غير موجود")
        if table == "evaluations" and user.get("role") == "supervisor":
            perms = user.get("permissions", {})
            eval_type = row.get("eval_type")
            if eval_type == "daily" and not perms.get("daily_evaluation", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم اليومي")
            if eval_type in {"monthly", "weekly"} and not perms.get("monthly_evaluation", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم الشهري والنهائي")
        return _format_row(conn, row, table, user)

    def create_row(body: dict, request: Request, user: dict = Depends(require_roles("manager", "supervisor")), conn=Depends(get_conn)):
        _allowed(user, table, True, action="create")
        if table in ("assignments", "submissions"):
            raise ApiError(405, "method_not_allowed", f"لا يمكن إنشاء سجلات {table} عبر الواجهة العامة المباشرة")
        if table == "students":
            body = {k: v for k, v in body.items() if k != "photo_url"}  # written only by POST /students/{id}/photo
        if table == "evaluations" and user.get("role") == "supervisor":
            perms = user.get("permissions", {})
            eval_type = body.get("eval_type", "daily")
            if eval_type == "daily" and not perms.get("daily_evaluation", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم اليومي")
            if eval_type in {"monthly", "weekly"} and not perms.get("monthly_evaluation", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم الشهري والنهائي")
        if table == "staff" and user.get("role") == "supervisor":
            if "base_salary" in body:
                raise ApiError(403, "forbidden", "ليس لديك صلاحية تحديد أو تعديل الراتب الأساسي")
        try:
            row, details = _create_hook(conn, _repo(request, conn, table), table, dict(body), user)
            write_audit(conn, user["id"], "create", table, row["id"], details)
            return _format_row(conn, row, table, user)
        except psycopg.Error as exc:
            raise map_db_error(exc) from exc

    def update_row(record_id: UUID, body: dict, request: Request, user: dict = Depends(require_roles("manager", "supervisor")), conn=Depends(get_conn)):
        _allowed(user, table, True, action="update")
        if table in ("assignments", "submissions"):
            raise ApiError(405, "method_not_allowed", f"لا يمكن تعديل سجلات {table} عبر الواجهة العامة المباشرة")
        if table == "students":
            body = {k: v for k, v in body.items() if k != "photo_url"}  # written only by POST /students/{id}/photo
        if table == "evaluations" and user.get("role") == "supervisor":
            perms = user.get("permissions", {})
            existing = _repo(request, conn, table).get(record_id)
            if not existing:
                raise ApiError(404, "not_found", "السجل غير موجود")
            eval_type = body.get("eval_type", existing.get("eval_type"))
            if eval_type == "daily" and not perms.get("daily_evaluation", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم اليومي")
            if eval_type in {"monthly", "weekly"} and not perms.get("monthly_evaluation", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم الشهري والنهائي")
        if table == "staff" and user.get("role") == "supervisor":
            if "base_salary" in body:
                raise ApiError(403, "forbidden", "ليس لديك صلاحية تحديد أو تعديل الراتب الأساسي")
        try:
            row, details = _update_hook(conn, _repo(request, conn, table), table, record_id, dict(body), actor=user)
            if not row:
                raise ApiError(404, "not_found", "السجل غير موجود")
            write_audit(conn, user["id"], "update", table, record_id, details)
            return _format_row(conn, row, table, user)
        except psycopg.Error as exc:
            raise map_db_error(exc) from exc

    def delete_row(record_id: UUID, request: Request, user: dict = Depends(require_roles("manager", "supervisor")), conn=Depends(get_conn)):
        _allowed(user, table, True, action="delete")
        if table == "users" and record_id == user["id"]:
            raise ApiError(400, "bad_request", "لا يمكن للمستخدم حذف حسابه الحالي")
        if table == "staff" and user.get("staff_id") and record_id == user["staff_id"]:
            raise ApiError(400, "bad_request", "لا يمكن للمستخدم حذف حسابه الوظيفي الحالي")
        if table == "evaluations" and user.get("role") == "supervisor":
            perms = user.get("permissions", {})
            existing = _repo(request, conn, table).get(record_id)
            if existing:
                eval_type = existing.get("eval_type")
                if eval_type == "daily" and not perms.get("daily_evaluation", False):
                    raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم اليومي")
                if eval_type in {"monthly", "weekly"} and not perms.get("monthly_evaluation", False):
                    raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم الشهري والنهائي")
        try:
            if not _repo(request, conn, table).soft_delete(record_id):
                raise ApiError(404, "not_found", "السجل غير موجود")
            if table == "staff":
                conn.execute(
                    "UPDATE users SET deleted_at = now(), is_active = false "
                    "WHERE staff_id = %s AND deleted_at IS NULL",
                    (record_id,)
                )
            write_audit(conn, user["id"], "delete", table, record_id, {})
        except psycopg.Error as exc:
            raise map_db_error(exc) from exc

    router.add_api_route(f"/{path}", list_rows, methods=["GET"])
    router.add_api_route(f"/{path}/{{record_id}}", get_row, methods=["GET"])
    router.add_api_route(f"/{path}", create_row, methods=["POST"])
    router.add_api_route(f"/{path}/{{record_id}}", update_row, methods=["PATCH"])
    router.add_api_route(f"/{path}/{{record_id}}", delete_row, methods=["DELETE"], status_code=204)


for _path, _table in RESOURCES.items():
    _register(_path, _table)


@router.post("/rooms/{room_id}/bulk-enroll")
def bulk_enroll_students(
    room_id: UUID,
    payload: dict,
    request: Request,
    conn=Depends(get_conn),
    user=Depends(require_roles("manager", "supervisor")),
):
    _allowed(user, "rooms", True)
    _allowed(user, "students", True)
    student_ids = payload.get("student_ids") or []
    if not student_ids:
        raise ApiError(400, "validation_error", "يجب تحديد طالب واحد على الأقل")

    room = conn.execute("SELECT id, name, branch_id FROM rooms WHERE id = %s AND deleted_at IS NULL", (room_id,)).fetchone()
    if not room:
        raise ApiError(404, "not_found", "القاعة غير موجودة")

    updated = []
    for sid in student_ids:
        try:
            uid = UUID(str(sid))
            res = conn.execute(
                "UPDATE students SET room_id = %s, updated_at = NOW() WHERE id = %s AND deleted_at IS NULL RETURNING id, name",
                (room_id, uid),
            ).fetchone()
            if res:
                updated.append(dict(res))
        except (ValueError, TypeError):
            continue

    count_row = conn.execute("SELECT COUNT(*) AS count FROM students WHERE room_id = %s AND deleted_at IS NULL", (room_id,)).fetchone()
    count = count_row["count"] if count_row else len(updated)

    write_audit(conn, user["id"], "bulk_enroll", "rooms", room_id, {"count": len(updated), "student_ids": [str(s["id"]) for s in updated], "branch_id": str(room["branch_id"])})
    return {"ok": True, "room_id": str(room_id), "room_name": room["name"], "enrolled_count": len(updated), "total_in_room": count}
