from datetime import date

from fastapi import APIRouter, Depends

from edutrack_api.audit import write_audit
from edutrack_api.auth import require_roles
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.scope import Scope, resolve_scope
from edutrack_api.serializers import row_to_json

router = APIRouter()
_ROLES = Depends(require_roles("manager", "supervisor", "teacher"))
_STAFF_ROLES = Depends(require_roles("manager", "supervisor"))  # staff attendance drives payroll deductions
_VALID_STATUSES = {"حاضر", "غائب", "متأخر", "مستأذن"}


def _validate(items: list[dict], key: str) -> date | None:
    dates: set[date] = set()
    for item in items:
        if not item.get(key) or item.get("status") not in _VALID_STATUSES:
            raise ApiError(422, "validation_error", "بيانات الحضور غير صحيحة")
        try:
            dates.add(date.fromisoformat(str(item["date"])))
        except ValueError as exc:
            raise ApiError(422, "validation_error", "التاريخ غير صحيح") from exc
    return next(iter(dates)) if len(dates) == 1 else None


def _save(conn, items: list[dict], key: str, table: str, actor: dict, scope: Scope | None = None) -> dict:
    report_date = _validate(items, key)
    if scope is not None:
        scope.assert_students(item[key] for item in items)
    if not items:
        return {"saved": 0, "items": []}
    sql = f"""
        INSERT INTO {table} ({key}, date, status, note{', recorded_by_user_id' if table == 'student_attendance' else ''})
        VALUES (%({key})s, %(date)s, %(status)s, %(note)s{', %(actor)s' if table == 'student_attendance' else ''})
        ON CONFLICT ({key}, date) DO UPDATE SET status = EXCLUDED.status, note = EXCLUDED.note,
          updated_at = now(), deleted_at = NULL{', recorded_by_user_id = EXCLUDED.recorded_by_user_id' if table == 'student_attendance' else ''}
        RETURNING *
    """
    saved = []
    for item in items:
        params = {key: item[key], "date": item["date"], "status": item["status"], "note": item.get("note"), "actor": actor["id"]}
        row = conn.execute(sql, params).fetchone()
        saved.append(row_to_json(row))

        # Batch B5: Emit attendance notification to guardians on absence
        if table == "student_attendance" and item["status"] == "غائب":
            st_id = item[key]
            att_date = item["date"]
            st_row = conn.execute("SELECT name, room_id, branch_id FROM students WHERE id = %s", (st_id,)).fetchone()
            if st_row:
                st_name = st_row["name"]
                br_id = st_row.get("branch_id") or actor.get("branch_id")
                g_users = conn.execute("""
                    SELECT u.id AS user_id FROM users u
                    JOIN student_guardians sg ON sg.guardian_id = u.guardian_id
                    WHERE sg.student_id = %s AND sg.deleted_at IS NULL AND u.is_active = true AND u.deleted_at IS NULL
                """, (st_id,)).fetchall()
                act_url = f"gheras://attendance?student_id={st_id}&date={att_date}"
                for gu in g_users:
                    existing_notif = conn.execute("""
                        SELECT id FROM notifications
                        WHERE user_id = %s AND action_url = %s
                    """, (gu["user_id"], act_url)).fetchone()
                    if not existing_notif:
                        conn.execute("""
                            INSERT INTO notifications (
                                branch_id, user_id, kind, title, body, target_type, target_id,
                                priority, sender_user_id, action_url, sent_at
                            ) VALUES (
                                %s, %s, 'attendance', %s, %s, 'attendance', %s,
                                'urgent', %s, %s, now()
                            )
                        """, (
                            br_id, gu["user_id"], "تسجيل غياب",
                            f"تم تسجيل غياب الطالب/ـة {st_name} بتاريخ {att_date}",
                            st_id, actor["id"],
                            act_url
                        ))

    write_audit(conn, actor["id"], "update", table, None, {"count": len(items), "date": report_date.isoformat() if report_date else None})
    return {"saved": len(saved), "items": saved}


def _teacher_scope(conn, user: dict) -> Scope | None:
    return resolve_scope(conn, user) if user["role"] == "teacher" else None


@router.post("/attendance/students")
def save_students(items: list[dict], conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    if user["role"] == "supervisor" and not user.get("permissions", {}).get("attendance", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية تسجيل حضور الطلاب")
    return _save(conn, items, "student_id", "student_attendance", user, scope=_teacher_scope(conn, user))


@router.post("/attendance/staff")
def save_staff(items: list[dict], conn=Depends(get_conn), user: dict = _STAFF_ROLES) -> dict:
    if user["role"] == "supervisor" and not user.get("permissions", {}).get("attendance", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية تسجيل حضور الموظفين")
    res = _save(conn, items, "staff_id", "staff_attendance", user)

    # Persist monetary deductions directly into payroll_runs
    for item in items:
        try:
            deduction = float(item.get("deduction", 0) or 0)
        except (ValueError, TypeError):
            deduction = 0
        if deduction > 0 and item.get("staff_id") and item.get("date"):
            month = str(item["date"])[:7]
            staff_row = conn.execute("SELECT base_salary, branch_id FROM staff WHERE id = %s", (item["staff_id"],)).fetchone()
            if staff_row:
                branch_id = staff_row["branch_id"]
                base = staff_row["base_salary"] or 0
                conn.execute(
                    """
                    INSERT INTO payroll_runs (branch_id, staff_id, month, base, deductions, note)
                    VALUES (%(branch_id)s, %(staff_id)s, %(month)s, %(base)s, %(deduction)s, %(note)s)
                    ON CONFLICT (staff_id, month) DO UPDATE
                    SET deductions = payroll_runs.deductions + EXCLUDED.deductions,
                        updated_at = now()
                    """,
                    {
                        "branch_id": branch_id,
                        "staff_id": item["staff_id"],
                        "month": month,
                        "base": base,
                        "deduction": deduction,
                        "note": f"خصم غياب {item['date']}",
                    },
                )
    return res


@router.get("/daily-evaluations")
@router.get("/evaluations/daily")
def list_daily_evaluations(
    date: str | None = None,
    room_id: str | None = None,
    student_id: str | None = None,
    conn=Depends(get_conn),
    user: dict = _ROLES,
) -> dict:
    if user["role"] == "supervisor" and not user.get("permissions", {}).get("daily_evaluation", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم اليومي")
    scope = _teacher_scope(conn, user)
    if scope is not None and not scope.student_ids:
        return {"items": [], "total": 0}

    query = """
        SELECT e.*, s.name as student_name, s.group_name
        FROM evaluations e
        JOIN students s ON s.id = e.student_id AND s.deleted_at IS NULL
        WHERE e.eval_type = 'daily' AND e.deleted_at IS NULL
    """
    params = []
    if date:
        query += " AND e.date = %s"
        params.append(date)
    if student_id:
        query += " AND e.student_id = %s"
        params.append(student_id)
    if room_id:
        query += " AND s.room_id = %s"
        params.append(room_id)
    if scope is not None:
        query += " AND e.student_id = ANY(%s)"
        params.append(list(scope.student_ids))
    query += " ORDER BY s.name ASC"

    rows = conn.execute(query, params).fetchall()
    return {"items": [row_to_json(r) for r in rows], "total": len(rows)}


@router.post("/daily-evaluations")
@router.post("/evaluations/daily")
def save_daily_evaluations(
    items: list[dict],
    conn=Depends(get_conn),
    user: dict = _ROLES,
) -> dict:
    if user["role"] == "supervisor" and not user.get("permissions", {}).get("daily_evaluation", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم اليومي")

    if not items:
        return {"saved": 0, "items": []}
    scope = _teacher_scope(conn, user)
    if scope is not None:
        scope.assert_students(item["student_id"] for item in items if item.get("student_id"))

    saved = []

    for item in items:
        student_id = item.get("student_id")
        eval_date = item.get("date")
        subject = item.get("subject") or "القرآن"
        raw_val = item.get("value", 0)

        if not student_id or not eval_date:
            raise ApiError(422, "validation_error", "بيانات التقييم غير مكتملة")
        try:
            val = float(raw_val)
            if val < 0:
                raise ValueError()
        except (ValueError, TypeError):
            raise ApiError(422, "validation_error", "درجة التقييم يجب أن تكون رقماً أكبر من أو يساوي الصفر")

        # Resolve branch_id from student
        st_row = conn.execute("SELECT branch_id FROM students WHERE id = %s", (student_id,)).fetchone()
        branch_id = st_row["branch_id"] if st_row else (user.get("branch_id") or "00000000-0000-0000-0000-000000000001")

        # Check existing evaluation for (student_id, date, subject, eval_type='daily')
        existing = conn.execute(
            "SELECT id FROM evaluations WHERE student_id = %s AND date = %s AND subject = %s AND eval_type = 'daily' AND deleted_at IS NULL",
            (student_id, eval_date, subject),
        ).fetchone()

        if existing:
            row = conn.execute(
                """
                UPDATE evaluations
                SET value = %s, teacher_user_id = %s, updated_at = now()
                WHERE id = %s
                RETURNING *
                """,
                (val, user["id"], existing["id"]),
            ).fetchone()
        else:
            row = conn.execute(
                """
                INSERT INTO evaluations (branch_id, student_id, subject, eval_type, date, value, teacher_user_id)
                VALUES (%s, %s, %s, 'daily', %s, %s, %s)
                RETURNING *
                """,
                (branch_id, student_id, subject, eval_date, val, user["id"]),
            ).fetchone()

        saved.append(row_to_json(row))

    write_audit(conn, user["id"], "update", "evaluations", None, {"count": len(items), "type": "daily"})
    return {"saved": len(saved), "items": saved}
