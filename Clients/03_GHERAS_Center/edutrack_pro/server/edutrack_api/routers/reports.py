from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, Query
from psycopg.types.json import Jsonb

from edutrack_api.auth import require_roles
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.serializers import row_to_json

router = APIRouter()
_ROLES = Depends(require_roles("manager", "supervisor"))
_TZ = ZoneInfo("Asia/Riyadh")


def _day(value: str | None, default: date | None = None) -> date:
    try:
        return date.fromisoformat(value) if value else (default or date.today())
    except ValueError as exc:
        raise ApiError(422, "validation_error", "التاريخ غير صحيح") from exc


def _month(value: str | None) -> str:
    result = value or _today().strftime("%Y-%m")
    if len(result) != 7 or result[4] != "-" or not result[:4].isdigit() or not result[5:].isdigit() or not 1 <= int(result[5:]) <= 12:
        raise ApiError(422, "validation_error", "الشهر غير صحيح")
    return result


def _today() -> date:
    return datetime.now(_TZ).date()


@router.get("/reports/daily")
def daily(date_: str | None = Query(None, alias="date"), conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    report_day = _day(date_, _today())
    base = conn.execute("""SELECT (SELECT count(*) FROM students WHERE deleted_at IS NULL) students_count,
      (SELECT count(*) FROM student_attendance WHERE date=%s AND status='حاضر' AND deleted_at IS NULL) present_today,
      (SELECT count(*) FROM student_attendance WHERE date=%s AND status='غائب' AND deleted_at IS NULL) absent_today,
      (SELECT count(*) FROM student_attendance WHERE date=%s AND status='متأخر' AND deleted_at IS NULL) late_today,
      (SELECT coalesce(sum(amount),0) FROM payments WHERE paid_on=%s AND deleted_at IS NULL) collected_today,
      (SELECT coalesce(sum(balance),0) FROM v_student_balance) outstanding_total,
      (SELECT coalesce(sum(amount),0) FROM expenses WHERE to_char(paid_on,'YYYY-MM')=to_char(%s::date,'YYYY-MM') AND deleted_at IS NULL) expenses_month""", (report_day,)*5).fetchone()
    absent = conn.execute("""SELECT a.student_id, s.name, coalesce(s.guardian_phone,s.father_phone,s.mother_phone) guardian_phone,
      r.name room_name, a.note FROM student_attendance a JOIN students s ON s.id=a.student_id
      LEFT JOIN rooms r ON r.id=s.room_id AND r.deleted_at IS NULL WHERE a.date=%s AND a.status='غائب'
      AND a.deleted_at IS NULL AND s.deleted_at IS NULL""", (report_day,)).fetchall()
    result = row_to_json(base); result["date"] = report_day.isoformat(); result["absences"] = [row_to_json(x) for x in absent]
    return result


@router.get("/reports/attendance")
def attendance(from_: str = Query(alias="from"), to: str = Query(), room_id: str | None = None, conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    start, end = _day(from_), _day(to)
    rows = conn.execute("""SELECT a.student_id,s.name student_name,r.name room_name,a.date,a.status,a.note
      FROM student_attendance a JOIN students s ON s.id=a.student_id LEFT JOIN rooms r ON r.id=s.room_id
      WHERE a.date BETWEEN %s AND %s AND a.deleted_at IS NULL AND s.deleted_at IS NULL AND (%s::uuid IS NULL OR s.room_id=%s::uuid)
      ORDER BY a.date,s.name""", (start,end,room_id,room_id)).fetchall()
    summary = conn.execute("""SELECT count(*) FILTER (WHERE a.status='حاضر') present,count(*) FILTER (WHERE a.status='غائب') absent,
      count(*) FILTER (WHERE a.status='متأخر') late,count(*) FILTER (WHERE a.status='مستأذن') excused FROM student_attendance a
      JOIN students s ON s.id=a.student_id WHERE a.date BETWEEN %s AND %s AND a.deleted_at IS NULL AND s.deleted_at IS NULL AND (%s::uuid IS NULL OR s.room_id=%s::uuid)""", (start,end,room_id,room_id)).fetchone()
    return {"from": start.isoformat(), "to": end.isoformat(), "items": [row_to_json(x) for x in rows], "summary": row_to_json(summary)}


@router.get("/reports/finance")
def finance(from_: str | None = Query(None, alias="from"), to: str | None = None, conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    start, end = _day(from_, _today().replace(day=1)), _day(to, _today())
    totals = conn.execute("""SELECT (SELECT coalesce(sum(amount),0) FROM payments WHERE paid_on BETWEEN %s AND %s AND deleted_at IS NULL) collected,
      (SELECT coalesce(sum(amount),0) FROM expenses WHERE paid_on BETWEEN %s AND %s AND deleted_at IS NULL) expenses,
      (SELECT coalesce(sum(net),0) FROM payroll_runs WHERE month BETWEEN to_char(%s::date,'YYYY-MM') AND to_char(%s::date,'YYYY-MM') AND deleted_at IS NULL) payroll,
      (SELECT coalesce(sum(balance),0) FROM v_student_balance) outstanding_total""", (start,end,start,end,start,end)).fetchone()
    data=row_to_json(totals); data["net"] = data["collected"]-data["expenses"]-data["payroll"]
    rows=conn.execute("SELECT student_id,student_name,total_planned,total_paid,balance outstanding FROM v_student_balance WHERE balance>0 ORDER BY balance DESC").fetchall()
    return {"from":start.isoformat(),"to":end.isoformat(),**data,"outstanding_by_student":[row_to_json(x) for x in rows]}


@router.get("/reports/monthly")
def monthly(month: str | None = None, conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    value=_month(month); start=date.fromisoformat(value+"-01")
    end=date(start.year+1,1,1) if start.month==12 else date(start.year,start.month+1,1)
    attendance_row=conn.execute("SELECT count(*) FILTER (WHERE status='حاضر') present,count(*) FILTER (WHERE status='غائب') absent,count(*) FILTER (WHERE status='متأخر') late,count(*) FILTER (WHERE status='مستأذن') excused FROM student_attendance WHERE date>=%s AND date<%s AND deleted_at IS NULL",(start,end)).fetchone()
    att=row_to_json(attendance_row); total=sum(att.values()); att["rate"]=(att["present"]+att["late"])/total*100 if total else 0
    fin=finance(start.isoformat(),(end - timedelta(days=1)).isoformat(),conn,user); result={"month":value,"students_count":conn.execute("SELECT count(*) FROM students WHERE deleted_at IS NULL").fetchone()["count"],"new_students":conn.execute("SELECT count(*) FROM students WHERE created_at>=%s AND created_at<%s AND deleted_at IS NULL",(start,end)).fetchone()["count"],"attendance":att,"finance":{k:fin[k] for k in ("collected","expenses","payroll","net")},"top_absent":[row_to_json(x) for x in conn.execute("SELECT a.student_id,s.name AS student_name,count(*) absent_days FROM student_attendance a JOIN students s ON s.id=a.student_id WHERE a.status='غائب' AND a.date>=%s AND a.date<%s AND a.deleted_at IS NULL AND s.deleted_at IS NULL GROUP BY a.student_id,s.name ORDER BY absent_days DESC LIMIT 10",(start,end)).fetchall()]}
    record=conn.execute("SELECT id FROM monthly_reports WHERE month=%s AND deleted_at IS NULL ORDER BY created_at LIMIT 1",(value,)).fetchone()
    if record: conn.execute("UPDATE monthly_reports SET payload_json=%s,generated_at=now(),updated_at=now() WHERE id=%s",(Jsonb(result),record["id"]))
    else: conn.execute("INSERT INTO monthly_reports (month,payload_json) VALUES (%s,%s)",(value,Jsonb(result)))
    return result


@router.get("/reports/student/{student_id}")
def student(student_id: str, conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    item=conn.execute("SELECT * FROM students WHERE id=%s AND deleted_at IS NULL",(student_id,)).fetchone()
    if not item: raise ApiError(404,"not_found","الطالب غير موجود")
    counts=conn.execute("SELECT count(*) FILTER (WHERE status='حاضر') present,count(*) FILTER (WHERE status='غائب') absent,count(*) FILTER (WHERE status='متأخر') late,count(*) FILTER (WHERE status='مستأذن') excused FROM student_attendance WHERE student_id=%s AND deleted_at IS NULL",(student_id,)).fetchone()
    balance=conn.execute("SELECT total_planned,total_paid,balance outstanding FROM v_student_balance WHERE student_id=%s",(student_id,)).fetchone()
    return {"student":row_to_json(item),"attendance_summary":row_to_json(counts),"evaluations":[row_to_json(x) for x in conn.execute("SELECT * FROM evaluations WHERE student_id=%s AND deleted_at IS NULL ORDER BY date DESC",(student_id,)).fetchall()],"skill_progress":[row_to_json(x) for x in conn.execute("SELECT * FROM skill_progress WHERE student_id=%s AND deleted_at IS NULL ORDER BY date DESC",(student_id,)).fetchall()],"balance":row_to_json(balance) if balance else {"total_planned":0,"total_paid":0,"outstanding":0},"payments":[row_to_json(x) for x in conn.execute("SELECT * FROM payments WHERE student_id=%s AND deleted_at IS NULL ORDER BY paid_on DESC",(student_id,)).fetchall()]}


@router.get("/audit-log")
def audit_log(limit:int=100,offset:int=0,entity:str|None=None,conn=Depends(get_conn),user:dict=_ROLES)->dict:
    limit=max(1,min(limit,500)); rows=conn.execute("SELECT a.*,coalesce(u.username,s.name) actor_name FROM audit_log a LEFT JOIN users u ON u.id=a.actor_user_id LEFT JOIN staff s ON s.id=u.staff_id WHERE a.deleted_at IS NULL AND (%s::text IS NULL OR a.entity=%s) ORDER BY a.at DESC LIMIT %s OFFSET %s",(entity,entity,limit,offset)).fetchall(); total=conn.execute("SELECT count(*) FROM audit_log WHERE deleted_at IS NULL AND (%s::text IS NULL OR entity=%s)",(entity,entity)).fetchone()["count"]
    return {"items":[row_to_json(x) for x in rows],"total":total,"limit":limit,"offset":offset}
