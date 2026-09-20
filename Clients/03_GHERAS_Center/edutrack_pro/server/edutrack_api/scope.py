"""Row scope for mobile roles (PHASE5_SPEC §1).

teacher  → room_ids = users.room_id ∪ schedules.room_id (teacher_user_id = me);
           student_ids = active, non-deleted students in those rooms.
Empty scope is legal (teacher with no room and no schedules): reads return
nothing, writes are refused. Guardian resolution lands with routers/me.py.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from uuid import UUID

from edutrack_api.errors import ApiError


@dataclass(frozen=True)
class Scope:
    role: str
    room_ids: frozenset[UUID]
    student_ids: frozenset[UUID]

    def assert_students(self, ids: Iterable[object]) -> None:
        for raw in ids:
            try:
                sid = UUID(str(raw))
            except ValueError:
                sid = None
            if sid not in self.student_ids:
                raise ApiError(403, "forbidden", "الطالب خارج نطاق صلاحيتك")


def resolve_scope(conn, user: dict) -> Scope:
    if user["role"] != "teacher":
        raise ApiError(403, "forbidden", "ليس لديك صلاحية")

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

    return Scope(role="teacher", room_ids=frozenset(room_ids), student_ids=frozenset(student_ids))
