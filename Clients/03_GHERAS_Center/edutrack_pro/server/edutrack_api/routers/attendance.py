from datetime import date as dt_date
from uuid import UUID

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


def _send_absence_notifications(conn, student_id: UUID | str, att_date: dt_date | str, actor_id: UUID | str, branch_id: UUID | str | None = None) -> int:
    st_row = conn.execute("SELECT name, room_id, branch_id FROM students WHERE id = %s", (student_id,)).fetchone()
    if not st_row:
        return 0
    st_name = st_row["name"]
    br_id = branch_id or st_row.get("branch_id")
    g_users = conn.execute("""
        SELECT u.id AS user_id FROM users u
        JOIN student_guardians sg ON sg.guardian_id = u.guardian_id
        WHERE sg.student_id = %s AND sg.deleted_at IS NULL AND u.is_active = true AND u.deleted_at IS NULL
    """, (student_id,)).fetchall()
    act_url = f"gheras://attendance?student_id={student_id}&date={att_date}"
    sent = 0
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
                student_id, actor_id,
                act_url
            ))
            sent += 1
    return sent


def _validate(items: list[dict], key: str) -> dt_date | None:
    dates: set[dt_date] = set()
    for item in items:
        if not item.get(key) or item.get("status") not in _VALID_STATUSES:
            raise ApiError(422, "validation_error", "بيانات الحضور غير صحيحة")
        d = item.get("date")
        if isinstance(d, dt_date):
            dates.add(d)
        else:
            try:
                dates.add(dt_date.fromisoformat(str(d)))
            except (ValueError, TypeError) as exc:
                raise ApiError(422, "validation_error", "التاريخ غير صحيح") from exc
    return next(iter(dates)) if len(dates) == 1 else None


def _save(
    conn,
    items: list[dict],
    key: str,
    table: str,
    actor: dict,
    scope: Scope | None = None,
    is_draft: bool = False,
) -> dict:
    report_date = _validate(items, key)
    if scope is not None:
        scope.assert_students(item[key] for item in items)
    if not items:
        return {"saved": 0, "items": []}

    if table == "student_attendance":
        sql = """
            INSERT INTO student_attendance (
                student_id, date, status, note, recorded_by_user_id, is_draft, excuse_id
            )
            VALUES (
                %(student_id)s, %(date)s, %(status)s, %(note)s, %(actor)s, %(is_draft)s, %(excuse_id)s
            )
            ON CONFLICT (student_id, date) DO UPDATE SET
                status = EXCLUDED.status,
                note = EXCLUDED.note,
                updated_at = now(),
                deleted_at = NULL,
                recorded_by_user_id = EXCLUDED.recorded_by_user_id,
                is_draft = student_attendance.is_draft AND EXCLUDED.is_draft,
                excuse_id = CASE WHEN EXCLUDED.status = 'غياب بعذر' THEN EXCLUDED.excuse_id ELSE NULL END
            RETURNING *
        """
    else:
        sql = f"""
            INSERT INTO {table} ({key}, date, status, note)
            VALUES (%({key})s, %(date)s, %(status)s, %(note)s)
            ON CONFLICT ({key}, date) DO UPDATE SET
                status = EXCLUDED.status,
                note = EXCLUDED.note,
                updated_at = now(),
                deleted_at = NULL
            RETURNING *
        """

    saved = []
    for item in items:
        status = item["status"]
        excuse_id = None
        if table == "student_attendance":
            # G3b / P5: Check for approved excuse if status is 'غائب'
            if status == "غائب":
                excuse = conn.execute(
                    """
                    SELECT id FROM attendance_excuses
                    WHERE student_id = %s AND excuse_date = %s AND status = 'approved' AND deleted_at IS NULL
                    LIMIT 1
                    """,
                    (item[key], item["date"]),
                ).fetchone()
                if excuse:
                    status = "غياب بعذر"
                    excuse_id = excuse["id"]

            params = {
                key: item[key],
                "date": item["date"],
                "status": status,
                "note": item.get("note"),
                "actor": actor["id"],
                "is_draft": is_draft,
                "excuse_id": excuse_id,
            }
            row = conn.execute(sql, params).fetchone()
            saved.append(row_to_json(row))

            # Emit attendance notification to guardians on absence (only if non-draft and not excused)
            if not is_draft and status == "غائب":
                _send_absence_notifications(conn, item[key], item["date"], actor["id"])
        else:
            params = {
                key: item[key],
                "date": item["date"],
                "status": status,
                "note": item.get("note"),
            }
            row = conn.execute(sql, params).fetchone()
            saved.append(row_to_json(row))

    write_audit(conn, actor["id"], "update", table, None, {
        "count": len(items),
        "date": report_date.isoformat() if report_date else None,
        "is_draft": is_draft if table == "student_attendance" else False,
    })
    return {"saved": len(saved), "items": saved}


def _teacher_scope(conn, user: dict) -> Scope | None:
    return resolve_scope(conn, user) if user["role"] == "teacher" else None


@router.post("/attendance/students")
def save_students(items: list[dict], conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    if user["role"] == "supervisor" and not user.get("permissions", {}).get("attendance", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية تسجيل حضور الطلاب")
    return _save(conn, items, "student_id", "student_attendance", user, scope=_teacher_scope(conn, user), is_draft=False)


@router.put("/attendance/students/draft")
def draft_students(
    payload: dict,
    conn=Depends(get_conn),
    user: dict = _ROLES,
) -> dict:
    if user["role"] == "supervisor" and not user.get("permissions", {}).get("attendance", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية تسجيل حضور الطلاب")

    room_id_raw = payload.get("room_id")
    date_raw = payload.get("date")
    items_raw = payload.get("items")

    if not room_id_raw or not date_raw or items_raw is None:
        raise ApiError(422, "validation_error", "بيانات المسودة غير مكتملة")

    if not isinstance(items_raw, list):
        raise ApiError(422, "validation_error", "قائمة الطلاب غير صحيحة")

    try:
        room_id = UUID(str(room_id_raw))
    except (ValueError, TypeError) as exc:
        raise ApiError(422, "validation_error", "معرف القاعة غير صحيح") from exc

    try:
        draft_date = dt_date.fromisoformat(str(date_raw))
    except (ValueError, TypeError) as exc:
        raise ApiError(422, "validation_error", "التاريخ غير صحيح") from exc

    if user["role"] == "teacher":
        scope = resolve_scope(conn, user)
        scope.assert_rooms([room_id])

    if not items_raw:
        return {"saved": 0, "items": [], "is_draft": True}

    items = []
    student_ids = []
    for item in items_raw:
        if not isinstance(item, dict):
            raise ApiError(422, "validation_error", "بيانات الحضور غير صحيحة")
        sid = item.get("student_id")
        st = item.get("status")
        if not sid or st not in _VALID_STATUSES:
            raise ApiError(422, "validation_error", "بيانات الحضور غير صحيحة")
        try:
            student_uuid = UUID(str(sid))
        except (ValueError, TypeError) as exc:
            raise ApiError(422, "validation_error", "معرف الطالب غير صحيح") from exc
        student_ids.append(student_uuid)
        items.append({
            "student_id": student_uuid,
            "date": draft_date,
            "status": st,
            "note": item.get("note"),
        })

    # G3c: Verify all students in items belong to this room and are active
    valid_students = conn.execute(
        """
        SELECT id FROM students
        WHERE id = ANY(%s) AND room_id = %s AND status = 'active' AND deleted_at IS NULL
        """,
        (student_ids, room_id),
    ).fetchall()
    valid_ids = {r["id"] for r in valid_students}
    for sid in student_ids:
        if sid not in valid_ids:
            raise ApiError(422, "validation_error", f"الطالب {sid} غير موجود أو غير نشط في هذا الفصل")

    res = _save(conn, items, "student_id", "student_attendance", user, is_draft=True)
    res["is_draft"] = True
    return res


@router.post("/attendance/rooms/{room_id}/days/{date}/approve")
def approve_room_attendance(
    room_id: UUID,
    date: str,
    conn=Depends(get_conn),
    user: dict = _ROLES,
) -> dict:
    if user["role"] == "supervisor" and not user.get("permissions", {}).get("attendance", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية تسجيل حضور الطلاب")

    if user["role"] == "teacher":
        scope = resolve_scope(conn, user)
        scope.assert_rooms([room_id])

    try:
        report_date = dt_date.fromisoformat(str(date))
    except (ValueError, TypeError) as exc:
        raise ApiError(422, "validation_error", "التاريخ غير صحيح") from exc

    with conn.transaction():
        # 1. Fetch all active students in the room
        active_students = conn.execute(
            """
            SELECT id, name, branch_id
            FROM students
            WHERE room_id = %s AND status = 'active' AND deleted_at IS NULL
            ORDER BY name ASC
            """,
            (room_id,),
        ).fetchall()

        if not active_students:
            raise ApiError(422, "validation_error", "لا توجد طالبات نشطات في هذا الفصل")

        active_student_ids = [s["id"] for s in active_students]

        # 2. Lock attendance rows for active students on this date (m5)
        attendance_rows = conn.execute(
            """
            SELECT id, student_id, status, is_draft, excuse_id
            FROM student_attendance
            WHERE student_id = ANY(%s) AND date = %s AND deleted_at IS NULL
            FOR UPDATE
            """,
            (active_student_ids, report_date),
        ).fetchall()

        # 3. Check complete room attendance (P1, P2)
        recorded_student_ids = {r["student_id"] for r in attendance_rows}
        missing_student_ids = set(active_student_ids) - recorded_student_ids
        if missing_student_ids:
            raise ApiError(422, "validation_error", "لا يمكن اعتماد الحضور قبل اكتمال رصد كافة طلاب الفصل")

        # 4. Check for approved excuses for absent students, convert to 'غياب بعذر', or send notifications
        notifications_sent = 0
        student_map = {s["id"]: s for s in active_students}

        for att in attendance_rows:
            sid = att["student_id"]
            current_status = att["status"]

            if current_status == "غائب":
                excuse = conn.execute(
                    """
                    SELECT id FROM attendance_excuses
                    WHERE student_id = %s AND excuse_date = %s AND status = 'approved' AND deleted_at IS NULL
                    LIMIT 1
                    """,
                    (sid, report_date),
                ).fetchone()

                if excuse:
                    conn.execute(
                        """
                        UPDATE student_attendance
                        SET status = 'غياب بعذر', excuse_id = %s, is_draft = FALSE, updated_at = now()
                        WHERE id = %s
                        """,
                        (excuse["id"], att["id"]),
                    )
                else:
                    conn.execute(
                        """
                        UPDATE student_attendance
                        SET is_draft = FALSE, updated_at = now()
                        WHERE id = %s
                        """,
                        (att["id"],),
                    )
                    st_info = student_map.get(sid)
                    br_id = st_info["branch_id"] if st_info else (user.get("branch_id") or "00000000-0000-0000-0000-000000000001")
                    notifications_sent += _send_absence_notifications(conn, sid, report_date, user["id"], br_id)
            else:
                conn.execute(
                    """
                    UPDATE student_attendance
                    SET is_draft = FALSE, updated_at = now()
                    WHERE id = %s
                    """,
                    (att["id"],),
                )

        write_audit(conn, user["id"], "approve", "student_attendance", None, {
            "room_id": str(room_id),
            "date": report_date.isoformat(),
            "total_students": len(active_students),
            "notifications_sent": notifications_sent,
        })

    return {
        "approved": True,
        "room_id": str(room_id),
        "date": report_date.isoformat(),
        "total_students": len(active_students),
        "notifications_sent": notifications_sent,
    }


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
        raw_val = item.get("value")
        if raw_val is None:
            raw_val = item.get("score")
        if raw_val is None:
            raise ApiError(422, "validation_error", "درجة التقييم مفقودة")

        if not student_id or not eval_date:
            raise ApiError(422, "validation_error", "بيانات التقييم غير مكتملة")
        try:
            val = float(raw_val)
            if val < 0 or val > 10:
                raise ValueError()
        except (ValueError, TypeError):
            raise ApiError(422, "validation_error", "درجة التقييم يجب أن تكون رقماً بين 0 و 10")

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
