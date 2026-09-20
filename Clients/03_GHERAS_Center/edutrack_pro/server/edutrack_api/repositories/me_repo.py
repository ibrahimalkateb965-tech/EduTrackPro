"""SQL for /api/v1/me/* (PHASE5_SPEC §2–§4).

Every list function is bounded by the caller's Scope: client-supplied ids can only
narrow the result. All lists return (rows, total) with a deterministic
ORDER BY … , id so offset paging never skips or repeats (spec §4.2).
"""

from __future__ import annotations

from uuid import UUID

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


def list_notifications(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    where = ["n.deleted_at IS NULL", "n.user_id = %(user_id)s"]
    params: dict = {"user_id": scope.user_id}
    unread = filters.get("unread")
    if unread is True:
        where.append("n.read_at IS NULL")
    elif unread is False:
        where.append("n.read_at IS NOT NULL")
    return _run(conn, _select("n.*", "notifications n", where, "n.created_at DESC, n.id"), params, limit, offset)


def mark_notification_read(conn, scope: Scope, notification_id: UUID) -> dict | None:
    return conn.execute(
        "UPDATE notifications SET read_at = COALESCE(read_at, now()), updated_at = now() "
        "WHERE id = %s AND user_id = %s AND deleted_at IS NULL RETURNING *",
        (notification_id, scope.user_id),
    ).fetchone()


# ---- batch 2: attendance, evaluations, assignments, submissions, lesson-logs, skill-progress ----

_LESSON_LOG_COLS = (
    "l.id, l.branch_id, l.schedule_id, l.date, l.status, l.covered, l.homework, l.teacher_user_id, "
    "l.created_at, l.updated_at, sc.room_id, sc.day, sc.start_time, sc.end_time, sc.subject, r.name AS room_name"
)
_LESSON_FROM = "lesson_logs l JOIN schedules sc ON sc.id = l.schedule_id JOIN rooms r ON r.id = sc.room_id"

_SUBMISSION_FILES = (
    "(SELECT COALESCE(json_agg(json_build_object('id', f.id, 'storage_key', f.storage_key, "
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
    narrow = ""
    if filters.get("student_id") is not None:
        narrow = " AND x.student_id = %(student_id)s"
        params["student_id"] = filters["student_id"]
    linked = (
        "EXISTS (SELECT 1 FROM assignment_students x WHERE x.assignment_id = a.id AND x.deleted_at IS NULL "
        f"AND x.student_id = ANY(%(student_ids)s){narrow})"
    )
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
