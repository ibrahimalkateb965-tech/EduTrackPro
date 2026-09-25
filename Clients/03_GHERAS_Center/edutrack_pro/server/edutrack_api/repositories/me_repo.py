"""SQL for /api/v1/me/* (PHASE5_SPEC §2–§4).

Every list function is bounded by the caller's Scope: client-supplied ids can only
narrow the result. All lists return (rows, total) with a deterministic
ORDER BY … , id so offset paging never skips or repeats (spec §4.2).
"""

from __future__ import annotations

from uuid import UUID

from edutrack_api.audit import write_audit
from edutrack_api.errors import ApiError
from edutrack_api.scope import Scope
from edutrack_api.services.settings import load_settings

Rows = tuple[list[dict], int]

# Rooms of the guardian's children (students.room_id may be NULL).
_CHILD_ROOMS = "(SELECT s.room_id FROM students s WHERE s.id = ANY(%(student_ids)s) AND s.room_id IS NOT NULL)"

_STUDENT_BASE_COLS = (
    "s.id, s.name, s.birth_date, s.nationality, s.gender, s.room_id, r.name AS room_name, "
    "s.group_name, s.status, s.has_difficulties"
)
_STUDENT_TEACHER_COLS = _STUDENT_BASE_COLS + ", s.difficulty_notes, s.child_notes, s.guardian_phone, s.guardian_relation"

_SCHEDULE_FROM = (
    "schedules sc JOIN rooms r ON r.id = sc.room_id "
    "LEFT JOIN users u ON u.id = sc.teacher_user_id LEFT JOIN staff st ON st.id = u.staff_id"
)


# ---- helpers ---------------------------------------------------------------


def _select(cols: str, from_: str, where: list[str], order_by: str) -> str:
    return (
        f"SELECT {cols}, COUNT(*) OVER() AS _total FROM {from_} "
        f"WHERE {' AND '.join(where)} ORDER BY {order_by} LIMIT %(limit)s OFFSET %(offset)s"
    )


def _run(conn, sql: str, params: dict, limit: int, offset: int) -> Rows:
    rows = conn.execute(sql, {**params, "limit": limit, "offset": offset}).fetchall()
    if not rows:
        # COUNT(*) OVER() rides on the returned rows: an offset past the last page
        # yields none, so recover the true total from the first row instead.
        if offset > 0:
            first = conn.execute(sql, {**params, "limit": 1, "offset": 0}).fetchone()
            return [], first["_total"] if first else 0
        return [], 0
    total = rows[0]["_total"]
    for row in rows:
        row.pop("_total", None)
    return rows, total


def _opt(where: list[str], params: dict, filters: dict, key: str, clause: str) -> None:
    """Append `clause` (which references %(key)s) when filters[key] is present."""
    value = filters.get(key)
    if value is not None and value != "":
        where.append(clause)
        params[key] = value


def _student_where(scope: Scope, alias: str) -> tuple[list[str], dict]:
    return (
        [f"{alias}.deleted_at IS NULL", f"{alias}.student_id = ANY(%(student_ids)s)"],
        {"student_ids": list(scope.student_ids)},
    )


# ---- batch 1: profile, students, rooms, schedule, notifications -------------


def profile(conn, scope: Scope, user: dict) -> dict:
    name = None
    if scope.role == "teacher" and user.get("staff_id"):
        row = conn.execute("SELECT name FROM staff WHERE id = %s AND deleted_at IS NULL", (user["staff_id"],)).fetchone()
        name = row["name"] if row else None
    elif scope.role == "guardian":
        row = conn.execute("SELECT name FROM guardians WHERE id = %s AND deleted_at IS NULL", (user["guardian_id"],)).fetchone()
        name = row["name"] if row else None
    elif scope.role == "student" and user.get("student_id"):
        row = conn.execute("SELECT name FROM students WHERE id = %s AND deleted_at IS NULL", (user["student_id"],)).fetchone()
        name = row["name"] if row else None
    settings = load_settings(conn)
    return {
        "user": {"id": user["id"], "username": user["username"], "role": scope.role, "name": name or user["username"]},
        "scope": {"room_ids": sorted(scope.room_ids, key=str), "student_ids": sorted(scope.student_ids, key=str)},
        "center": {"name": settings["center_name"], "phone": settings["center_phone"]},
    }


def list_students(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    cols = _STUDENT_TEACHER_COLS if scope.role == "teacher" else _STUDENT_BASE_COLS
    where = ["s.deleted_at IS NULL", "s.id = ANY(%(student_ids)s)"]
    params: dict = {"student_ids": list(scope.student_ids)}
    _opt(where, params, filters, "student_id", "s.id = %(student_id)s")
    _opt(where, params, filters, "status", "s.status = %(status)s")
    return _run(conn, _select(cols, "students s LEFT JOIN rooms r ON r.id = s.room_id", where, "s.name, s.id"), params, limit, offset)


def list_rooms(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.room_ids:
        return [], 0
    where = ["r.deleted_at IS NULL", "r.id = ANY(%(room_ids)s)"]
    return _run(conn, _select("r.*", "rooms r", where, "r.name, r.id"), {"room_ids": list(scope.room_ids)}, limit, offset)


def list_schedule(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    where = ["sc.deleted_at IS NULL"]
    if scope.role == "teacher":
        if not scope.room_ids:
            return [], 0
        where.append("sc.room_id = ANY(%(room_ids)s)")
        params: dict = {"room_ids": list(scope.room_ids)}
    else:
        if not scope.student_ids:
            return [], 0
        where.append(f"sc.room_id IN {_CHILD_ROOMS}")
        params = {"student_ids": list(scope.student_ids)}
    _opt(where, params, filters, "day", "sc.day = %(day)s")
    cols = "sc.*, r.name AS room_name, COALESCE(st.name, u.username) AS teacher_name"
    return _run(conn, _select(cols, _SCHEDULE_FROM, where, "sc.day, sc.start_time, sc.id"), params, limit, offset)


_NOTIFICATIONS_FROM = (
    "notifications n LEFT JOIN users u ON u.id = n.sender_user_id "
    "LEFT JOIN staff st ON st.id = u.staff_id"
)


def list_notifications(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    where = ["n.deleted_at IS NULL", "n.user_id = %(user_id)s"]
    params: dict = {"user_id": scope.user_id}
    unread = filters.get("unread")
    if unread is True:
        where.append("n.read_at IS NULL")
    elif unread is False:
        where.append("n.read_at IS NOT NULL")
    cols = "n.*, COALESCE(st.name, u.username) AS sender_name"
    return _run(conn, _select(cols, _NOTIFICATIONS_FROM, where, "COALESCE(n.sent_at, n.created_at) DESC, n.id"), params, limit, offset)


def mark_notification_read(conn, scope: Scope, notification_id: UUID) -> dict | None:
    return conn.execute(
        "UPDATE notifications SET read_at = COALESCE(read_at, now()), updated_at = now() "
        "WHERE id = %s AND user_id = %s AND deleted_at IS NULL RETURNING *",
        (notification_id, scope.user_id),
    ).fetchone()


def delete_notification(conn, scope: Scope, notification_id: UUID) -> dict | None:
    row = conn.execute(
        "UPDATE notifications SET deleted_at = COALESCE(deleted_at, now()), updated_at = now() "
        "WHERE id = %s AND user_id = %s RETURNING id",
        (notification_id, scope.user_id),
    ).fetchone()
    if not row:
        return None
    return {"id": str(row["id"])}


def clear_read_notifications(conn, scope: Scope, ids: list[UUID]) -> int:
    if len(ids) > 500:
        raise ApiError(422, "too_many_ids", "لا يمكن مسح أكثر من 500 إشعار دفعة واحدة")
    if not ids:
        return 0
    cur = conn.execute(
        "UPDATE notifications SET deleted_at = COALESCE(deleted_at, now()), updated_at = now() "
        "WHERE user_id = %s AND id = ANY(%s) AND read_at IS NOT NULL AND deleted_at IS NULL "
        "RETURNING id",
        (scope.user_id, ids),
    )
    return len(cur.fetchall())


def create_broadcast(conn, scope: Scope, body: dict) -> dict:
    if scope.role != "teacher":
        raise ApiError(403, "forbidden", "صلاحية البث متاحة للمعلمين فقط")

    broadcast_id = body.get("id")
    if not broadcast_id:
        raise ApiError(422, "id_required", "معرف الإعلان مطلوب")

    title = (body.get("title") or "").strip()
    if not title or len(title) > 120:
        raise ApiError(422, "invalid_title", "عنوان الإعلان يجب أن يكون بين 1 و 120 حرفاً")

    body_text = body.get("body")
    if body_text is not None:
        body_text = body_text.strip()
        if len(body_text) > 2000:
            raise ApiError(422, "invalid_body", "نص الإعلان لا يجب أن يتجاوز 2000 حرف")

    priority = body.get("priority", "normal")
    if priority not in ("normal", "urgent"):
        raise ApiError(422, "invalid_priority", "درجة الأهمية غير صالحة")

    room_id = body.get("room_id")
    student_ids = body.get("student_ids") or []
    if not room_id and not student_ids:
        raise ApiError(422, "target_required", "يجب اختيار قاعة أو طلاب")

    if len(student_ids) > 200:
        raise ApiError(422, "too_many_students", "لا يمكن تحديد أكثر من 200 طالب")

    include_guardians = bool(body.get("include_guardians", True))
    include_students = bool(body.get("include_students", True))
    if not include_guardians and not include_students:
        raise ApiError(422, "target_audience_required", "يجب تحديد فئة مستهدفة (أولياء الأمور أو الطلاب)")

    # Scope assertions
    if room_id:
        scope.assert_rooms([room_id])
    if student_ids:
        scope.assert_students(student_ids)

    # 4. Idempotent replay: INSERT INTO notification_broadcasts
    branch_id = None
    if scope.user_id:
        u_row = conn.execute("SELECT branch_id FROM users WHERE id = %s", (scope.user_id,)).fetchone()
        if u_row:
            branch_id = u_row.get("branch_id")
    if not branch_id and room_id:
        r_row = conn.execute("SELECT branch_id FROM rooms WHERE id = %s", (room_id,)).fetchone()
        if r_row:
            branch_id = r_row.get("branch_id")
    if not branch_id:
        branch_id = UUID("00000000-0000-0000-0000-000000000001")

    ins_broadcast = """
        INSERT INTO notification_broadcasts (
            id, branch_id, sender_user_id, room_id, student_ids,
            include_guardians, include_students, title, body, priority, recipient_count
        ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 0)
        ON CONFLICT (id) DO NOTHING RETURNING *
    """
    row = conn.execute(ins_broadcast, (
        broadcast_id, branch_id, scope.user_id, room_id, student_ids,
        include_guardians, include_students, title, body_text, priority
    )).fetchone()

    if not row:
        existing = conn.execute("SELECT * FROM notification_broadcasts WHERE id = %s", (broadcast_id,)).fetchone()
        if existing and existing["sender_user_id"] == scope.user_id:
            return {
                "id": str(existing["id"]),
                "recipient_count": existing["recipient_count"],
                "created_at": existing["created_at"].isoformat()
            }
        raise ApiError(422, "id_conflict", "معرف الإعلان مستخدم مسبقاً")

    # 5. Resolve target students
    target_students_set = set(student_ids)
    if room_id:
        room_st_rows = conn.execute(
            "SELECT id FROM students WHERE room_id = %s AND deleted_at IS NULL",
            (room_id,)
        ).fetchall()
        for r in room_st_rows:
            target_students_set.add(r["id"])

    if not target_students_set:
        raise ApiError(422, "no_students", "لا يوجد طلاب في القاعة المحددة")

    target_student_list = list(target_students_set)

    user_children_map: dict[UUID, set[UUID]] = {}

    if include_guardians:
        g_rows = conn.execute("""
            SELECT u.id AS user_id, sg.student_id
            FROM users u
            JOIN student_guardians sg ON sg.guardian_id = u.guardian_id
            WHERE sg.student_id = ANY(%s)
              AND sg.deleted_at IS NULL
              AND u.is_active = true
              AND u.deleted_at IS NULL
              AND u.id <> %s
        """, (target_student_list, scope.user_id)).fetchall()
        for r in g_rows:
            user_children_map.setdefault(r["user_id"], set()).add(r["student_id"])

    if include_students:
        s_rows = conn.execute("""
            SELECT u.id AS user_id, u.student_id
            FROM users u
            WHERE u.student_id = ANY(%s)
              AND u.is_active = true
              AND u.deleted_at IS NULL
              AND u.id <> %s
        """, (target_student_list, scope.user_id)).fetchall()
        for r in s_rows:
            user_children_map.setdefault(r["user_id"], set()).add(r["student_id"])

    recipient_count = len(user_children_map)
    if recipient_count == 0:
        raise ApiError(422, "no_recipients", "لا يوجد مستلمون لهذا الإعلان")
    if recipient_count > 500:
        raise ApiError(422, "too_many_recipients", "عدد المستلمين يتجاوز الحد الأقصى (500)")

    # Fan out insert
    notif_sql = """
        INSERT INTO notifications (
            branch_id, user_id, kind, title, body, target_type, target_id,
            priority, sender_user_id, broadcast_id, action_url, sent_at
        ) VALUES (
            %s, %s, 'announcement', %s, %s, 'announcement', %s,
            %s, %s, %s, %s, now()
        ) ON CONFLICT (broadcast_id, user_id) WHERE broadcast_id IS NOT NULL DO NOTHING
    """
    for u_id, children in user_children_map.items():
        action_url = None
        if len(children) == 1:
            child_id = next(iter(children))
            action_url = f"gheras://announcement?student_id={child_id}"
        conn.execute(notif_sql, (
            branch_id, u_id, title, body_text, broadcast_id,
            priority, scope.user_id, broadcast_id, action_url
        ))

    conn.execute(
        "UPDATE notification_broadcasts SET recipient_count = %s, updated_at = now() WHERE id = %s",
        (recipient_count, broadcast_id)
    )

    write_audit(
        conn, scope.user_id, "broadcast", "notification_broadcasts", broadcast_id,
        {"title": title, "recipient_count": recipient_count}
    )

    return {
        "id": str(broadcast_id),
        "recipient_count": recipient_count,
        "created_at": row["created_at"].isoformat()
    }


# ---- batch 2: attendance, evaluations, assignments, submissions, lesson-logs, skill-progress ----

_LESSON_LOG_COLS = (
    "l.id, l.branch_id, l.schedule_id, l.date, l.status, l.covered, l.homework, l.teacher_user_id, "
    "l.created_at, l.updated_at, sc.room_id, sc.day, sc.start_time, sc.end_time, sc.subject, r.name AS room_name"
)
_LESSON_FROM = "lesson_logs l JOIN schedules sc ON sc.id = l.schedule_id JOIN rooms r ON r.id = sc.room_id"

_SUBMISSION_FILES = (
    "(SELECT COALESCE(json_agg(json_build_object('id', f.id, 'storage_key', f.storage_key, "
    "'url', CASE WHEN starts_with(f.storage_key, 'http') THEN f.storage_key ELSE '/api/v1/static/uploads/' || f.storage_key END, "
    "'width', f.width, 'height', f.height) ORDER BY f.created_at, f.id), '[]'::json) "
    "FROM submission_files f WHERE f.submission_id = sb.id AND f.deleted_at IS NULL) AS files"
)

# student ids linked to an assignment, intersected with the caller's scope
_ASSIGNMENT_STUDENTS = (
    "(SELECT COALESCE(array_agg(x.student_id ORDER BY x.student_id), '{}') FROM assignment_students x "
    "WHERE x.assignment_id = a.id AND x.deleted_at IS NULL AND x.student_id = ANY(%(student_ids)s)) AS student_ids"
)


def list_attendance(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    where, params = _student_where(scope, "a")
    _opt(where, params, filters, "student_id", "a.student_id = %(student_id)s")
    _opt(where, params, filters, "date_from", "a.date >= %(date_from)s")
    _opt(where, params, filters, "date_to", "a.date <= %(date_to)s")
    sql = _select("a.*, s.name AS student_name", "student_attendance a JOIN students s ON s.id = a.student_id", where, "a.date DESC, a.id")
    return _run(conn, sql, params, limit, offset)


def list_evaluations(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    where, params = _student_where(scope, "e")
    _opt(where, params, filters, "student_id", "e.student_id = %(student_id)s")
    _opt(where, params, filters, "eval_type", "e.eval_type = %(eval_type)s")
    _opt(where, params, filters, "date_from", "e.date >= %(date_from)s")
    _opt(where, params, filters, "date_to", "e.date <= %(date_to)s")
    sql = _select("e.*, s.name AS student_name", "evaluations e JOIN students s ON s.id = e.student_id", where, "e.date DESC, e.id")
    return _run(conn, sql, params, limit, offset)


def list_assignments(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    where = ["a.deleted_at IS NULL"]
    params: dict = {"student_ids": list(scope.student_ids)}
    # `narrow` tightens the link subquery to one student; it is only ever applied on
    # top of the scope set, so an out-of-scope student_id yields an empty page, not a leak.
    narrow = ""
    if filters.get("student_id") is not None:
        narrow = " AND x.student_id = %(student_id)s"
        params["student_id"] = filters["student_id"]
    linked = (
        "EXISTS (SELECT 1 FROM assignment_students x WHERE x.assignment_id = a.id AND x.deleted_at IS NULL "
        f"AND x.student_id = ANY(%(student_ids)s){narrow})"
    )
    # Teacher sees own assignments (authored, even with no students linked yet) OR those
    # linked to a scoped student. With a student_id filter the "own" branch drops out:
    # the question becomes "what was assigned to this child", not "what did I author".
    # Guardian only ever sees assignments linked to their children.
    if scope.role == "teacher" and not narrow:
        where.append(f"(a.teacher_user_id = %(user_id)s OR {linked})")
        params["user_id"] = scope.user_id
    else:
        where.append(linked)
    _opt(where, params, filters, "due_from", "a.due_date >= %(due_from)s")
    _opt(where, params, filters, "due_to", "a.due_date <= %(due_to)s")
    sql = _select(f"a.*, {_ASSIGNMENT_STUDENTS}", "assignments a", where, "a.due_date DESC, a.id")
    return _run(conn, sql, params, limit, offset)


def list_submissions(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    where, params = _student_where(scope, "sb")
    _opt(where, params, filters, "assignment_id", "sb.assignment_id = %(assignment_id)s")
    _opt(where, params, filters, "student_id", "sb.student_id = %(student_id)s")
    sql = _select(
        f"sb.*, a.title AS assignment_title, s.name AS student_name, {_SUBMISSION_FILES}",
        "submissions sb JOIN assignments a ON a.id = sb.assignment_id JOIN students s ON s.id = sb.student_id",
        where,
        "sb.submitted_at DESC, sb.id",
    )
    return _run(conn, sql, params, limit, offset)


def list_lesson_logs(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    where = ["l.deleted_at IS NULL"]
    if scope.role == "teacher":
        if not scope.room_ids:
            return [], 0
        where.append("(l.teacher_user_id = %(user_id)s OR sc.room_id = ANY(%(room_ids)s))")
        params: dict = {"user_id": scope.user_id, "room_ids": list(scope.room_ids)}
        cols = _LESSON_LOG_COLS + ", l.notes"  # internal notes: teacher only (§3.4)
    else:
        if not scope.student_ids:
            return [], 0
        where.append(f"sc.room_id IN {_CHILD_ROOMS}")
        params = {"student_ids": list(scope.student_ids)}
        cols = _LESSON_LOG_COLS
    _opt(where, params, filters, "schedule_id", "l.schedule_id = %(schedule_id)s")
    _opt(where, params, filters, "date_from", "l.date >= %(date_from)s")
    _opt(where, params, filters, "date_to", "l.date <= %(date_to)s")
    return _run(conn, _select(cols, _LESSON_FROM, where, "l.date DESC, l.id"), params, limit, offset)


def list_skill_progress(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    where, params = _student_where(scope, "sp")
    _opt(where, params, filters, "student_id", "sp.student_id = %(student_id)s")
    _opt(where, params, filters, "subject", "sp.subject = %(subject)s")
    sql = _select("sp.*, s.name AS student_name", "skill_progress sp JOIN students s ON s.id = sp.student_id", where, "sp.date DESC, sp.id")
    return _run(conn, sql, params, limit, offset)


# ---- batch 3: installments, receipts, POST lesson-logs ----------------------

MSG_ROOM_OUT_OF_SCOPE = "الحلقة خارج نطاق صلاحيتك"


def list_installments(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    where = ["i.deleted_at IS NULL", "fp.deleted_at IS NULL", "fp.student_id = ANY(%(student_ids)s)"]
    params: dict = {"student_ids": list(scope.student_ids)}
    _opt(where, params, filters, "student_id", "fp.student_id = %(student_id)s")
    _opt(where, params, filters, "status", "i.status = %(status)s")
    cols = "i.*, fp.student_id, s.name AS student_name, fp.total_amount AS plan_total, fp.count AS plan_count"
    from_ = "installments i JOIN fee_plans fp ON fp.id = i.fee_plan_id JOIN students s ON s.id = fp.student_id"
    return _run(conn, _select(cols, from_, where, "i.due_date, i.id"), params, limit, offset)


def list_receipts(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    where = ["rc.deleted_at IS NULL", "p.deleted_at IS NULL", "p.student_id = ANY(%(student_ids)s)"]
    params: dict = {"student_ids": list(scope.student_ids)}
    _opt(where, params, filters, "student_id", "p.student_id = %(student_id)s")
    cols = "rc.*, p.student_id, p.installment_id, p.amount, p.method, p.paid_on, s.name AS student_name"
    from_ = "receipts rc JOIN payments p ON p.id = rc.payment_id JOIN students s ON s.id = p.student_id"
    return _run(conn, _select(cols, from_, where, "rc.issued_on DESC, rc.id"), params, limit, offset)


def upsert_lesson_log(conn, scope: Scope, body: dict) -> dict:
    """SELECT-then-write on (schedule_id, date); no unique index exists (§3.2).

    Pre-existing duplicates are tolerated: the most recently updated open row wins.
    """
    sched = conn.execute(
        "SELECT room_id, branch_id FROM schedules WHERE id = %s AND deleted_at IS NULL",
        (body["schedule_id"],),
    ).fetchone()
    if sched is None:
        raise ApiError(404, "not_found")
    if sched["room_id"] not in scope.room_ids:
        raise ApiError(403, "forbidden", MSG_ROOM_OUT_OF_SCOPE)

    existing = conn.execute(
        "SELECT id FROM lesson_logs WHERE schedule_id = %s AND date = %s AND deleted_at IS NULL "
        "ORDER BY updated_at DESC, id LIMIT 1",
        (body["schedule_id"], body["date"]),
    ).fetchone()
    params = {
        "schedule_id": body["schedule_id"],
        "date": body["date"],
        "status": body["status"],
        "covered": body.get("covered"),
        "homework": body.get("homework"),
        "notes": body.get("notes"),
        "user_id": scope.user_id,
        "branch_id": sched["branch_id"],
    }
    if existing:
        row = conn.execute(
            "UPDATE lesson_logs SET status = %(status)s, covered = %(covered)s, homework = %(homework)s, "
            "notes = %(notes)s, teacher_user_id = %(user_id)s, updated_at = now() "
            "WHERE id = %(id)s RETURNING *",
            {**params, "id": existing["id"]},
        ).fetchone()
    else:
        row = conn.execute(
            "INSERT INTO lesson_logs (branch_id, schedule_id, date, status, covered, homework, notes, teacher_user_id) "
            "VALUES (%(branch_id)s, %(schedule_id)s, %(date)s, %(status)s, %(covered)s, %(homework)s, %(notes)s, %(user_id)s) "
            "RETURNING *",
            params,
        ).fetchone()
    write_audit(
        conn,
        scope.user_id,
        "update",
        "lesson_logs",
        row["id"],
        {"schedule_id": str(body["schedule_id"]), "date": body["date"].isoformat(), "status": body["status"]},
    )
    return row


def create_assignment(conn, scope: Scope, body: dict) -> dict:
    if scope.role != "teacher":
        raise ApiError(403, "forbidden", "إضافة التكليفات متاحة للمعلمين فقط")

    student_ids = body.get("student_ids") or []
    if student_ids:
        scope.assert_students(student_ids)

    valid_subjects = ("القرآن", "لغتي", "الإنجليزي", "الرياضيات")
    subject = body.get("subject")
    if subject is not None and subject not in valid_subjects:
        raise ApiError(422, "invalid_subject", f"المادة غير صالحة. المواد المسموحة هي: {', '.join(valid_subjects)}")

    user_row = conn.execute("SELECT branch_id FROM users WHERE id = %s", (scope.user_id,)).fetchone()
    branch_id = user_row["branch_id"] if user_row and user_row.get("branch_id") else None
    if not branch_id and student_ids:
        s_row = conn.execute("SELECT branch_id FROM students WHERE id = %s", (student_ids[0],)).fetchone()
        if s_row and s_row.get("branch_id"):
            branch_id = s_row["branch_id"]
    if not branch_id:
        raise ApiError(422, "branch_missing", "لا يوجد فرع مرتبط بهذا المعلم أو بطلاب التكليف")

    params = {
        "branch_id": branch_id,
        "title": body["title"],
        "subject": subject,
        "kind": body.get("kind") or "homework",
        "due_date": body["due_date"],
        "teacher_user_id": scope.user_id,
        "instructions": body.get("instructions"),
        "page_ref": body.get("page_ref"),
    }
    assignment_id = body.get("id")
    if assignment_id:
        params["id"] = assignment_id
        sql = """
            INSERT INTO assignments (id, branch_id, title, subject, kind, due_date, teacher_user_id, instructions, page_ref)
            VALUES (%(id)s, %(branch_id)s, %(title)s, %(subject)s, %(kind)s, %(due_date)s, %(teacher_user_id)s, %(instructions)s, %(page_ref)s)
            ON CONFLICT (id) DO UPDATE SET
                title = EXCLUDED.title,
                subject = EXCLUDED.subject,
                due_date = EXCLUDED.due_date,
                instructions = EXCLUDED.instructions,
                page_ref = EXCLUDED.page_ref,
                updated_at = now()
            WHERE assignments.teacher_user_id = EXCLUDED.teacher_user_id
            RETURNING *, (xmax = 0) AS is_inserted
        """
    else:
        sql = """
            INSERT INTO assignments (branch_id, title, subject, kind, due_date, teacher_user_id, instructions, page_ref)
            VALUES (%(branch_id)s, %(title)s, %(subject)s, %(kind)s, %(due_date)s, %(teacher_user_id)s, %(instructions)s, %(page_ref)s)
            RETURNING *, true AS is_inserted
        """
    row = conn.execute(sql, params).fetchone()
    if not row:
        raise ApiError(403, "forbidden", "لا يمكنك تعديل تكليف لمعلم آخر أو التكليف غير موجود")

    is_inserted = bool(row.get("is_inserted", False))
    action = "create" if is_inserted else "update"

    for sid in student_ids:
        existing = conn.execute(
            "SELECT id FROM assignment_students WHERE assignment_id = %s AND student_id = %s AND deleted_at IS NULL",
            (row["id"], sid),
        ).fetchone()
        if not existing:
            conn.execute(
                "INSERT INTO assignment_students (branch_id, assignment_id, student_id) VALUES (%s, %s, %s)",
                (branch_id, row["id"], sid),
            )

    write_audit(
        conn,
        scope.user_id,
        action,
        "assignments",
        row["id"],
        {"title": body["title"], "due_date": body["due_date"].isoformat()},
    )

    # Batch B5: Assignment notification producer (on creation)
    if is_inserted and student_ids:
        # 1. Guardians of target students
        g_rows = conn.execute("""
            SELECT u.id AS user_id, sg.student_id, s.name AS student_name
            FROM users u
            JOIN student_guardians sg ON sg.guardian_id = u.guardian_id
            JOIN students s ON s.id = sg.student_id
            WHERE sg.student_id = ANY(%s)
              AND sg.deleted_at IS NULL
              AND u.is_active = true
              AND u.deleted_at IS NULL
              AND u.id <> %s
        """, (student_ids, scope.user_id)).fetchall()

        # 2. Student users
        s_rows = conn.execute("""
            SELECT u.id AS user_id, u.student_id, s.name AS student_name
            FROM users u
            JOIN students s ON s.id = u.student_id
            WHERE u.student_id = ANY(%s)
              AND u.is_active = true
              AND u.deleted_at IS NULL
              AND u.id <> %s
        """, (student_ids, scope.user_id)).fetchall()

        ins_notif = """
            INSERT INTO notifications (
                branch_id, user_id, kind, title, body, target_type, target_id,
                priority, sender_user_id, action_url, sent_at
            ) VALUES (
                %s, %s, 'assignment', %s, %s, 'assignment', %s,
                'normal', %s, %s, now()
            )
        """
        seen_targets = set()
        for r in list(g_rows) + list(s_rows):
            u_id = r["user_id"]
            st_id = r["student_id"]
            st_name = r["student_name"]
            key = (u_id, st_id)
            if key in seen_targets:
                continue
            seen_targets.add(key)

            notif_title = f"واجب جديد: {body['title']}"
            notif_body = f"تم إضافة واجب جديد للطالب/ـة {st_name} لمادة {subject}، موعد التسليم: {body['due_date']}"
            act_url = f"gheras://assignment?id={row['id']}&student_id={st_id}"
            conn.execute(ins_notif, (
                branch_id, u_id, notif_title, notif_body, str(row["id"]),
                scope.user_id, act_url
            ))

    return row


def create_submission(
    conn,
    scope: Scope,
    assignment_id: UUID,
    student_id: UUID,
    files_data: list[dict],
    notes: str | None = None
) -> dict:
    scope.assert_students([student_id])

    a_row = conn.execute(
        "SELECT id, branch_id, title, subject, teacher_user_id FROM assignments WHERE id = %s AND deleted_at IS NULL",
        (assignment_id,)
    ).fetchone()
    if not a_row:
        raise ApiError(404, "not_found", "التكليف غير موجود")

    branch_id = a_row["branch_id"]

    existing_link = conn.execute(
        "SELECT id FROM assignment_students WHERE assignment_id = %s AND student_id = %s AND deleted_at IS NULL",
        (assignment_id, student_id)
    ).fetchone()
    if not existing_link:
        conn.execute(
            "INSERT INTO assignment_students (branch_id, assignment_id, student_id) VALUES (%s, %s, %s)",
            (branch_id, assignment_id, student_id)
        )

    sub = conn.execute(
        "SELECT id FROM submissions WHERE assignment_id = %s AND student_id = %s AND deleted_at IS NULL",
        (assignment_id, student_id)
    ).fetchone()

    if sub:
        sub_id = sub["id"]
        conn.execute(
            "UPDATE submissions SET submitted_at = now(), status = 'submitted', feedback = NULL, updated_at = now() WHERE id = %s",
            (sub_id,)
        )
        conn.execute(
            "UPDATE submission_files SET deleted_at = now(), updated_at = now() WHERE submission_id = %s AND deleted_at IS NULL",
            (sub_id,)
        )
    else:
        sub_row = conn.execute(
            "INSERT INTO submissions (branch_id, assignment_id, student_id, submitted_at, status) "
            "VALUES (%s, %s, %s, now(), 'submitted') RETURNING id",
            (branch_id, assignment_id, student_id)
        ).fetchone()
        sub_id = sub_row["id"]

    for f in files_data:
        conn.execute(
            """
            INSERT INTO submission_files (branch_id, submission_id, storage_key, width, height, bytes, sha256)
            VALUES (%s, %s, %s, %s, %s, %s, %s)
            """,
            (branch_id, sub_id, f["storage_key"], f.get("width"), f.get("height"), f.get("bytes"), f.get("sha256"))
        )

    teacher_user_id = a_row.get("teacher_user_id")
    if teacher_user_id and teacher_user_id != scope.user_id:
        st_row = conn.execute("SELECT name FROM students WHERE id = %s", (student_id,)).fetchone()
        st_name = st_row["name"] if st_row else "الطالب"
        ins_notif = """
            INSERT INTO notifications (
                branch_id, user_id, kind, title, body, target_type, target_id,
                priority, sender_user_id, action_url, sent_at
            ) VALUES (
                %s, %s, 'assignment', %s, %s, 'assignment', %s,
                'normal', %s, %s, now()
            )
        """
        conn.execute(ins_notif, (
            branch_id,
            teacher_user_id,
            "تسليم واجب جديد",
            f"قام الطالب {st_name} بتسليم حل: {a_row['title']}",
            assignment_id,
            scope.user_id,
            f"gheras://assignment?id={assignment_id}&student_id={student_id}"
        ))

    write_audit(
        conn,
        scope.user_id,
        "submit_homework",
        "submissions",
        sub_id,
        {"assignment_id": str(assignment_id), "student_id": str(student_id), "pages": len(files_data)}
    )

    sql = f"""
        SELECT sb.*, a.title AS assignment_title, s.name AS student_name, {_SUBMISSION_FILES}
        FROM submissions sb
        JOIN assignments a ON a.id = sb.assignment_id
        JOIN students s ON s.id = sb.student_id
        WHERE sb.id = %s
    """
    return conn.execute(sql, (sub_id,)).fetchone()


def grade_submission(
    conn,
    scope: Scope,
    submission_id: UUID,
    grade: float,
    feedback: str | None = None
) -> dict:
    if scope.role != "teacher":
        raise ApiError(403, "forbidden")

    sub = conn.execute(
        """
        SELECT sb.id, sb.assignment_id, sb.student_id, sb.branch_id, a.title AS assignment_title, a.teacher_user_id, s.name AS student_name
        FROM submissions sb
        JOIN assignments a ON a.id = sb.assignment_id
        JOIN students s ON s.id = sb.student_id
        WHERE sb.id = %s AND sb.deleted_at IS NULL
        """,
        (submission_id,)
    ).fetchone()
    if not sub:
        raise ApiError(404, "not_found", "التسليم غير موجود")

    if sub["teacher_user_id"] != scope.user_id and sub["student_id"] not in scope.student_ids:
        raise ApiError(403, "forbidden", "لا تملك صلاحية تقييم هذا التسليم")

    conn.execute(
        "UPDATE submissions SET grade = %s, feedback = %s, status = 'graded', updated_at = now() WHERE id = %s",
        (grade, feedback, submission_id)
    )

    st_id = sub["student_id"]
    g_rows = conn.execute("""
        SELECT u.id AS user_id FROM users u
        JOIN student_guardians sg ON sg.guardian_id = u.guardian_id
        WHERE sg.student_id = %s AND sg.deleted_at IS NULL AND u.is_active = true AND u.deleted_at IS NULL
    """, (st_id,)).fetchall()

    s_rows = conn.execute("""
        SELECT u.id AS user_id FROM users u
        WHERE u.student_id = %s AND u.is_active = true AND u.deleted_at IS NULL
    """, (st_id,)).fetchall()

    ins_notif = """
        INSERT INTO notifications (
            branch_id, user_id, kind, title, body, target_type, target_id,
            priority, sender_user_id, action_url, sent_at
        ) VALUES (
            %s, %s, 'assignment', %s, %s, 'assignment', %s,
            'normal', %s, %s, now()
        )
    """
    for r in list(g_rows) + list(s_rows):
        conn.execute(ins_notif, (
            sub["branch_id"],
            r["user_id"],
            "تم تصحيح الواجب",
            f"تم رصد درجة الواجب: {grade} للطالب {sub['student_name']} في واجب: {sub['assignment_title']}",
            sub["assignment_id"],
            scope.user_id,
            f"gheras://assignment?id={sub['assignment_id']}&student_id={st_id}"
        ))

    write_audit(
        conn,
        scope.user_id,
        "grade_homework",
        "submissions",
        submission_id,
        {"grade": grade, "feedback": feedback}
    )

    sql = f"""
        SELECT sb.*, a.title AS assignment_title, s.name AS student_name, {_SUBMISSION_FILES}
        FROM submissions sb
        JOIN assignments a ON a.id = sb.assignment_id
        JOIN students s ON s.id = sb.student_id
        WHERE sb.id = %s
    """
    return conn.execute(sql, (submission_id,)).fetchone()


