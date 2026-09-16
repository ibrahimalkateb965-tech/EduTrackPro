from datetime import date, datetime
from zoneinfo import ZoneInfo
from decimal import Decimal

from fastapi import APIRouter, Depends, Query

from edutrack_api.auth import require_roles
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.serializers import row_to_json
from edutrack_api.services.words import arabic_amount_words

router = APIRouter()
_TZ = ZoneInfo("Asia/Riyadh")


def _today() -> date:
    return datetime.now(_TZ).date()
_ROLES = Depends(require_roles("manager", "supervisor"))


def _date(value: object) -> str:
    return value.isoformat() if hasattr(value, "isoformat") else str(value or "")


def _one(conn, sql: str, args: tuple, message: str):
    row = conn.execute(sql, args).fetchone()
    if not row:
        raise ApiError(404, "not_found", message)
    return row_to_json(row)


@router.get("/print/receipt/{payment_id}")
def receipt(payment_id: str, conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    item = _one(conn, """SELECT r.receipt_no,r.issued_on,p.amount,p.method,s.name student_name,i.seq_no,
      b.balance FROM receipts r JOIN payments p ON p.id=r.payment_id JOIN students s ON s.id=p.student_id
      LEFT JOIN installments i ON i.id=p.installment_id LEFT JOIN v_student_balance b ON b.student_id=s.id
      WHERE p.id=%s AND r.deleted_at IS NULL AND p.deleted_at IS NULL AND s.deleted_at IS NULL""", (payment_id,), "الدفعة غير موجودة")
    return {"template":"receipt","receipt_no":item["receipt_no"],"date":_date(item["issued_on"]),"student_name":item["student_name"],"method":item["method"],"amount":item["amount"],"installment_ref":item["seq_no"] or "","remaining_balance":item["balance"] or 0,"amount_words":arabic_amount_words(Decimal(str(item["amount"]))) }


@router.get("/print/guardian-card/{student_id}")
def guardian_card(student_id: str, conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    s=_one(conn,"SELECT s.*,r.name room_name FROM students s LEFT JOIN rooms r ON r.id=s.room_id WHERE s.id=%s AND s.deleted_at IS NULL",(student_id,),"الطالب غير موجود")
    return {"template":"guardian_card","card_no":s["id"],"date":_today().isoformat(),"student_name":s["name"],"group_name":s["group_name"] or "","room_name":s["room_name"] or "","father_phone":s["father_phone"] or "","mother_phone":s["mother_phone"] or "","guardian_phone":s["guardian_phone"] or "","pickup_type":s["pickup_type"] or "","pickup_name":s["pickup_name"] or "","pickup_relation":s["pickup_relation"] or "","pickup_phone":s["pickup_phone"] or ""}


@router.get("/print/certificate/{certificate_id}")
def certificate(certificate_id:str,conn=Depends(get_conn),user:dict=_ROLES)->dict:
    c=_one(conn,"SELECT c.*,s.name student_name FROM certificates c JOIN students s ON s.id=c.student_id WHERE c.id=%s AND c.deleted_at IS NULL AND s.deleted_at IS NULL",(certificate_id,),"الشهادة غير موجودة")
    return {"template":"excellence_certificate","title":c["title"],"certificate_no":c["id"],"date":_date(c["issued_on"]),"student_name":c["student_name"],"reason":c["reason"] or ""}


@router.get("/print/student-report/{student_id}")
def student_report(student_id:str,conn=Depends(get_conn),user:dict=_ROLES)->dict:
    s=_one(conn,"SELECT s.*,r.name room_name FROM students s LEFT JOIN rooms r ON r.id=s.room_id WHERE s.id=%s AND s.deleted_at IS NULL",(student_id,),"الطالب غير موجود")
    counts=row_to_json(conn.execute("SELECT count(*) FILTER(WHERE status='حاضر') present,count(*) FILTER(WHERE status='غائب') absent,count(*) FILTER(WHERE status='متأخر') late,count(*) FILTER(WHERE status='مستأذن') excused FROM student_attendance WHERE student_id=%s AND deleted_at IS NULL",(student_id,)).fetchone()); total=sum(counts.values()); teacher=""
    ev=[{"subject":x["subject"],"eval_type":x["eval_type"],"date":_date(x["date"]),"value":x["value"],"teacher":x["teacher"] or ""} for x in conn.execute("SELECT e.*,u.username teacher FROM evaluations e LEFT JOIN users u ON u.id=e.teacher_user_id WHERE e.student_id=%s AND e.deleted_at IS NULL ORDER BY e.date DESC",(student_id,)).fetchall()]
    skills=[{"subject":x["subject"],"skill":x["skill"],"level":x["level"],"date":_date(x["date"])} for x in conn.execute("SELECT * FROM skill_progress WHERE student_id=%s AND deleted_at IS NULL ORDER BY date DESC",(student_id,)).fetchall()]
    plans=[{"subject":x["subject"],"skill":x["skill"],"goal":x["goal"] or "","start_date":_date(x["start_date"]),"end_date":_date(x["end_date"])} for x in conn.execute("SELECT * FROM study_plans WHERE student_id=%s AND deleted_at IS NULL ORDER BY start_date DESC",(student_id,)).fetchall()]
    return {"template":"student_report","report_no":student_id,"date":_today().isoformat(),"student_name":s["name"],"group_name":s["group_name"] or "","room_name":s["room_name"] or "","guardian_phone":s["guardian_phone"] or "","present_count":counts["present"],"absent_count":counts["absent"],"late_count":counts["late"],"excused_count":counts["excused"],"attendance_pct":round((counts["present"]+counts["late"])*100/total,2) if total else 0,"teacher_notes":teacher,"evaluations":ev,"skills":skills,"plans":plans}


@router.get("/print/admin-report")
def admin_report(from_:str=Query(alias="from"),to:str=Query(),conn=Depends(get_conn),user:dict=_ROLES)->dict:
    students=conn.execute("SELECT count(*) count FROM students WHERE deleted_at IS NULL").fetchone()["count"]; att=conn.execute("SELECT count(*) FILTER(WHERE status IN ('حاضر','متأخر')) good,count(*) total FROM student_attendance WHERE date BETWEEN %s AND %s AND deleted_at IS NULL",(from_,to)).fetchone(); fin=conn.execute("SELECT coalesce(sum(amount),0) total FROM payments WHERE paid_on BETWEEN %s AND %s AND deleted_at IS NULL",(from_,to)).fetchone()["total"]; exp=conn.execute("SELECT coalesce(sum(amount),0) total FROM expenses WHERE paid_on BETWEEN %s AND %s AND deleted_at IS NULL",(from_,to)).fetchone()["total"]
    return {"template":"admin_report","period":f"{from_} - {to}","date":_today().isoformat(),"students_count":students,"attendance_pct":round(att["good"]*100/att["total"],2) if att["total"] else 0,"collected":fin,"outstanding":conn.execute("SELECT coalesce(sum(balance),0) total FROM v_student_balance").fetchone()["total"],"expenses":exp,"net":fin-exp,"by_group":[{"group":x["group_label"],"students":x["students"],"attendance_pct":0} for x in conn.execute("SELECT group_name AS group_label,count(*) students FROM students WHERE deleted_at IS NULL GROUP BY group_name").fetchall()],"top_outstanding":[{"student":x["student_name"],"amount":x["balance"]} for x in conn.execute("SELECT * FROM v_student_balance ORDER BY balance DESC LIMIT 10").fetchall()],"recent_expenses":[{"description":x["description"],"category":x["category"],"amount":x["amount"],"date":_date(x["paid_on"])} for x in conn.execute("SELECT e.*,c.name category FROM expenses e JOIN expense_categories c ON c.id=e.category_id WHERE e.deleted_at IS NULL ORDER BY paid_on DESC LIMIT 10").fetchall()]}


@router.get("/print/monthly-report")
def monthly_report(month:str,conn=Depends(get_conn),user:dict=_ROLES)->dict:
    start=date.fromisoformat(month+"-01"); end=date(start.year+1,1,1) if start.month==12 else date(start.year,start.month+1,1)
    pay=conn.execute("SELECT coalesce(sum(amount),0) total FROM payments WHERE paid_on>=%s AND paid_on<%s AND deleted_at IS NULL",(start,end)).fetchone()["total"]; exp=conn.execute("SELECT coalesce(sum(amount),0) total FROM expenses WHERE paid_on>=%s AND paid_on<%s AND deleted_at IS NULL",(start,end)).fetchone()["total"]; payroll=conn.execute("SELECT coalesce(sum(net),0) total FROM payroll_runs WHERE month=%s AND deleted_at IS NULL",(month,)).fetchone()["total"]
    return {"template":"monthly_report","month":month,"date":_today().isoformat(),"total_income":pay,"total_expenses":exp,"payroll_total":payroll,"net":pay-exp-payroll,"closed_by":"","closed_at":"","collections":[{"student":x["student"],"amount":x["amount"],"method":x["method"],"date":_date(x["paid_on"])} for x in conn.execute("SELECT s.name student,p.amount,p.method,p.paid_on FROM payments p JOIN students s ON s.id=p.student_id WHERE p.paid_on>=%s AND p.paid_on<%s AND p.deleted_at IS NULL",(start,end)).fetchall()],"expense_categories":[{"category":x["category"],"amount":x["amount"]} for x in conn.execute("SELECT c.name category,sum(e.amount) amount FROM expenses e JOIN expense_categories c ON c.id=e.category_id WHERE e.paid_on>=%s AND e.paid_on<%s AND e.deleted_at IS NULL GROUP BY c.name",(start,end)).fetchall()]}


@router.get("/print/schedule")
def schedule(room:str,conn=Depends(get_conn),user:dict=_ROLES)->dict:
    r=_one(conn,"SELECT * FROM rooms WHERE id=%s AND deleted_at IS NULL",(room,),"القاعة غير موجودة"); rows=conn.execute("SELECT * FROM schedules WHERE room_id=%s AND deleted_at IS NULL ORDER BY start_time",(room,)).fetchall(); periods={}
    for x in rows:
        key=(str(x["start_time"]),str(x["end_time"])); periods.setdefault(key,{"start_time":key[0],"end_time":key[1],"sun":"","mon":"","tue":"","wed":"","thu":""})[{"الأحد":"sun","الاثنين":"mon","الثلاثاء":"tue","الأربعاء":"wed","الخميس":"thu"}[x["day"]]]=x["subject"]
    return {"template":"schedule","room":r["name"],"group":r["group_name"],"periods":list(periods.values())}


@router.get("/print/attendance-report")
def attendance_report(from_:str=Query(alias="from"),to:str=Query(),conn=Depends(get_conn),user:dict=_ROLES)->dict:
    dates=[d["date"] for d in conn.execute("SELECT generate_series(%s::date,%s::date,'1 day')::date date",(from_,to)).fetchall()]; rows=conn.execute("SELECT s.id,s.name FROM students s WHERE s.deleted_at IS NULL ORDER BY s.name").fetchall(); records=conn.execute("SELECT student_id,date,status FROM student_attendance WHERE date BETWEEN %s AND %s AND deleted_at IS NULL",(from_,to)).fetchall(); lookup={(x["student_id"],x["date"]):x["status"] for x in records}; symbols={"حاضر":"✓","غائب":"✗","متأخر":"م","مستأذن":"ع"}; output=[]
    for s in rows:
        cells=[{"symbol":symbols.get(lookup.get((s["id"],d)),"")} for d in dates]; output.append({"student_name":s["name"],"present_count":sum(c["symbol"]=="✓" for c in cells),"absent_count":sum(c["symbol"]=="✗" for c in cells),"cells":cells})
    return {"template":"attendance_report","from":from_,"to":to,"date":_today().isoformat(),"dates":[{"date":_date(d)} for d in dates],"rows":output}


@router.get("/print/student-receipt/{student_id}")
def student_receipt(student_id:str,conn=Depends(get_conn),user:dict=_ROLES)->dict:
    s=_one(conn,"SELECT s.*,r.name room_name,b.total_planned,b.total_paid,b.balance FROM students s LEFT JOIN rooms r ON r.id=s.room_id LEFT JOIN v_student_balance b ON b.student_id=s.id WHERE s.id=%s AND s.deleted_at IS NULL",(student_id,),"الطالب غير موجود"); plan=conn.execute("SELECT * FROM fee_plans WHERE student_id=%s AND deleted_at IS NULL ORDER BY created_at DESC LIMIT 1",(student_id,)).fetchone(); installments=[] if not plan else [{"seq":x["seq_no"],"due_date":_date(x["due_date"]),"amount":x["amount"],"paid_amount":x["paid_amount"],"status":{"pending":"غير مدفوع","partial":"جزئي","paid":"مدفوع"}[x["status"]]} for x in conn.execute("SELECT * FROM installments WHERE fee_plan_id=%s AND deleted_at IS NULL ORDER BY seq_no",(plan["id"],)).fetchall()]
    return {"template":"student_receipt","statement_no":student_id,"date":_today().isoformat(),"student_name":s["name"],"group_name":s["group_name"] or "","room_name":s["room_name"] or "","guardian_phone":s["guardian_phone"] or "","total_amount":s["total_planned"] or 0,"installments_count":plan["count"] if plan else 0,"interval_days":plan["interval_days"] if plan else 0,"total_paid":s["total_paid"] or 0,"balance":s["balance"] or 0,"installments":installments,"payments":[{"date":_date(x["paid_on"]),"receipt_no":x["receipt_no"] or "","method":x["method"],"amount":x["amount"]} for x in conn.execute("SELECT p.*,r.receipt_no FROM payments p LEFT JOIN receipts r ON r.payment_id=p.id AND r.deleted_at IS NULL WHERE p.student_id=%s AND p.deleted_at IS NULL ORDER BY paid_on",(student_id,)).fetchall()]}


@router.get("/print/lesson-log")
def lesson_log(schedule:str,from_:str=Query(alias="from"),to:str=Query(),conn=Depends(get_conn),user:dict=_ROLES)->dict:
    s=_one(conn,"SELECT sc.*,r.name room,u.username teacher FROM schedules sc JOIN rooms r ON r.id=sc.room_id LEFT JOIN users u ON u.id=sc.teacher_user_id WHERE sc.id=%s AND sc.deleted_at IS NULL",(schedule,),"الحصة غير موجودة")
    logs=[{"date":_date(x["date"]),"status":x["status"],"covered":x["covered"] or "","homework":x["homework"] or "","notes":x["notes"] or ""} for x in conn.execute("SELECT * FROM lesson_logs WHERE schedule_id=%s AND date BETWEEN %s AND %s AND deleted_at IS NULL ORDER BY date",(schedule,from_,to)).fetchall()]
    return {"template":"lesson_log","room":s["room"],"subject":s["subject"],"teacher":s["teacher"] or "","from":from_,"to":to,"date":_today().isoformat(),"logs":logs}


@router.get("/print/statistics")
def statistics(from_:str=Query(alias="from"),to:str=Query(),conn=Depends(get_conn),user:dict=_ROLES)->dict:
    total=conn.execute("SELECT count(*) count FROM students WHERE deleted_at IS NULL").fetchone()["count"]; att=conn.execute("SELECT count(*) FILTER(WHERE status IN ('حاضر','متأخر')) good,count(*) total FROM student_attendance WHERE date BETWEEN %s AND %s AND deleted_at IS NULL",(from_,to)).fetchone(); collected=conn.execute("SELECT coalesce(sum(amount),0) total FROM payments WHERE paid_on BETWEEN %s AND %s AND deleted_at IS NULL",(from_,to)).fetchone()["total"]; planned=conn.execute("SELECT coalesce(sum(total_planned),0) total FROM v_student_balance").fetchone()["total"]; group=conn.execute("SELECT coalesce(group_name,'') AS group_label,count(*) students FROM students WHERE deleted_at IS NULL GROUP BY group_name",()).fetchall()
    return {"template":"statistics_report","period":f"{from_} - {to}","date":_today().isoformat(),"students_count":total,"attendance_pct":round(att["good"]*100/att["total"],2) if att["total"] else 0,"collection_pct":round(collected*100/planned,2) if planned else 0,"outstanding":conn.execute("SELECT coalesce(sum(balance),0) total FROM v_student_balance").fetchone()["total"],"boys_count":0,"boys_pct":0,"girls_count":0,"girls_pct":0,"notes":"","by_group":[{"group":x["group_label"],"students":x["students"],"pct":round(x["students"]*100/total,2) if total else 0} for x in group]}
