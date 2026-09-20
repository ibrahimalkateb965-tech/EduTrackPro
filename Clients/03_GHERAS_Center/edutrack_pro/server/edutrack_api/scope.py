"""Row scope for mobile roles (PHASE5_SPEC §1).

teacher  → room_ids = users.room_id ∪ schedules.room_id (teacher_user_id = me);
           student_ids = active, non-deleted students in those rooms.
guardian → room_ids = ∅; student_ids = children via student_guardians
           (dismissed/archived children stay visible for history; soft-deleted do not).
Empty scope is legal (teacher with no room and no schedules): reads return
nothing, writes are refused. A guardian whose users.guardian_id is NULL is a
misconfigured account → 403. Any other role → 403 (the dashboard uses the CRUD routes).
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from uuid import UUID

from fastapi import Depends

from edutrack_api.auth import current_user
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError

MSG_NOT_MOBILE = "هذه الواجهة مخصصة لتطبيق المعلم وولي الأمر"
MSG_GUARDIAN_UNLINKED = "حساب ولي الأمر غير مرتبط بطالب — راجع إدارة المركز"
MSG_STUDENT_OUT_OF_SCOPE = "الطالب خارج نطاق صلاحيتك"


@dataclass(frozen=True)
class Scope:
    role: str
    room_ids: frozenset[UUID]
    student_ids: frozenset[UUID]
    user_id: UUID

    def assert_students(self, ids: Iterable[object]) -> None:
        for raw in ids:
            try:
                sid = UUID(str(raw))
            except ValueError:
                sid = None
            if sid not in self.student_ids:
                raise ApiError(403, "forbidden", MSG_STUDENT_OUT_OF_SCOPE)


def _teacher_scope(conn, user: dict) -> Scope:
    room_ids: set[UUID] = set()
    if user.get("room_id"):
        room_ids.add(user["room_id"])
    for row in conn.execute(
        "SELECT room_id FROM schedules WHERE teacher_user_id = %s AND deleted_at IS NULL",
        (user["id"],),
    ).fetchall():
        room_ids.add(row["room_id"])

    student_ids: set[UUID] = set()
    if room_ids:
        for row in conn.execute(
            "SELECT id FROM students WHERE room_id = ANY(%s) AND status = 'active' AND deleted_at IS NULL",
            (list(room_ids),),
        ).fetchall():
            student_ids.add(row["id"])

    return Scope(role="teacher", room_ids=frozenset(room_ids), student_ids=frozenset(student_ids), user_id=user["id"])


def _guardian_scope(conn, user: dict) -> Scope:
    if not user.get("guardian_id"):
        raise ApiError(403, "forbidden", MSG_GUARDIAN_UNLINKED)
    rows = conn.execute(
        "SELECT sg.student_id FROM student_guardians sg "
        "JOIN students s ON s.id = sg.student_id AND s.deleted_at IS NULL "
        "WHERE sg.guardian_id = %s AND sg.deleted_at IS NULL",
        (user["guardian_id"],),
    ).fetchall()
    return Scope(
        role="guardian",
        room_ids=frozenset(),
        student_ids=frozenset(r["student_id"] for r in rows),
        user_id=user["id"],
    )


def resolve_scope(conn, user: dict) -> Scope:
    if user["role"] == "teacher":
        return _teacher_scope(conn, user)
    if user["role"] == "guardian":
        return _guardian_scope(conn, user)
    raise ApiError(403, "forbidden", MSG_NOT_MOBILE)


def require_scope(user: dict = Depends(current_user), conn=Depends(get_conn)) -> Scope:
    """FastAPI dependency for /api/v1/me/*: teacher or guardian only, scope resolved once per request."""
    return resolve_scope(conn, user)
