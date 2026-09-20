"""Phase 5 (b): /api/v1/me/* row scoping for teacher and guardian (PHASE5_SPEC §1–§4).

Test names carry the batch that turns them green: b0 = scope.py (Claude Code),
b1 = profile/students/rooms/schedule/notifications, b2 = attendance/evaluations/
assignments/submissions/lesson-logs GET/skill-progress, b3 = installments/receipts/
POST lesson-logs.

World: rooms A, B, C; teacher T (home A, one slot in B); students a1, a2 (A),
b1 (B), c1 (C, dismissed); guardian G → {a1, c1}; guardian G2 → {b1}.
"""

from __future__ import annotations

import datetime as dt
import uuid
from dataclasses import dataclass

import pytest

from edutrack_api.errors import ApiError
from edutrack_api.scope import MSG_GUARDIAN_UNLINKED, MSG_NOT_MOBILE, resolve_scope
from tests.conftest import _guardian, _room, _student, _teacher, login, make_user

TODAY = dt.date.today()
ME = "/api/v1/me"


def _iso(d: dt.date) -> str:
    return d.isoformat()


def _slot(db, room_id: str, teacher_id: str | None, day: str = "الأحد", start: str = "08:00", end: str = "09:00", subject: str = "القرآن") -> str:
    row = db.execute(
        "INSERT INTO schedules (room_id, teacher_user_id, day, start_time, end_time, subject) "
        "VALUES (%s, %s, %s, %s, %s, %s) RETURNING id",
        (room_id, teacher_id, day, start, end, subject),
    ).fetchone()
    db.commit()
    return str(row["id"])


def _notify(db, user_id: str, title: str) -> str:
    row = db.execute(
        "INSERT INTO notifications (user_id, kind, title) VALUES (%s, 'info', %s) RETURNING id",
        (user_id, title),
    ).fetchone()
    db.commit()
    return str(row["id"])


def _user_row(db, user_id: str) -> dict:
    return dict(db.execute("SELECT id, username, role, staff_id, guardian_id, room_id FROM users WHERE id = %s", (user_id,)).fetchone())


@dataclass
class World:
    room_a: str
    room_b: str
    room_c: str
    a1: str
    a2: str
    b1: str
    c1: str
    teacher_id: str
    teacher: dict
    sched_a: str  # room A, no teacher, الاثنين
    sched_b: str  # room B, teacher T, الأحد
    sched_c: str  # room C, no teacher, الثلاثاء
    guardian_id: str
    guardian: dict
    guardian2_id: str
    guardian2: dict
    manager_id: str


@pytest.fixture()
def world(client, db, manager) -> World:
    room_a = _room(client, manager, "أ")
    room_b = _room(client, manager, "ب")
    room_c = _room(client, manager, "ج")
    a1 = _student(client, manager, "طالب أ1", room_a)
    a2 = _student(client, manager, "طالب أ2", room_a)
    b1 = _student(client, manager, "طالب ب", room_b)
    c1 = _student(client, manager, "طالب ج", room_c)
    db.execute("UPDATE students SET status = 'dismissed' WHERE id = %s", (c1,))
    db.commit()
    teacher_id, teacher = _teacher(db, client, "t_main", room_a)
    sched_a = _slot(db, room_a, None, day="الاثنين")
    sched_b = _slot(db, room_b, teacher_id)
    sched_c = _slot(db, room_c, None, day="الثلاثاء")
    guardian_id, guardian = _guardian(db, client, "g_main", [a1, c1])
    guardian2_id, guardian2 = _guardian(db, client, "g_two", [b1])
    manager_id = str(db.execute("SELECT id FROM users WHERE username = 'manager1'").fetchone()["id"])
    return World(room_a, room_b, room_c, a1, a2, b1, c1, teacher_id, teacher, sched_a, sched_b, sched_c,
                 guardian_id, guardian, guardian2_id, guardian2, manager_id)


# =============================================================================
# b0 — scope.py (direct calls, no HTTP)
# =============================================================================


def test_b0_resolve_scope_guardian_lists_children_including_dismissed(db, world):
    scope = resolve_scope(db, _user_row(db, world.guardian_id))
    assert scope.role == "guardian"
    assert scope.room_ids == frozenset()
    assert {str(s) for s in scope.student_ids} == {world.a1, world.c1}
    assert str(scope.user_id) == world.guardian_id


def test_b0_resolve_scope_guardian_unlinked_is_403(db, world):
    uid = make_user(db, "g_unlinked", "guardian")
    with pytest.raises(ApiError) as exc:
        resolve_scope(db, _user_row(db, str(uid)))
    assert exc.value.status == 403
    assert exc.value.message == MSG_GUARDIAN_UNLINKED


def test_b0_resolve_scope_rejects_dashboard_roles(db, world):
    with pytest.raises(ApiError) as exc:
        resolve_scope(db, _user_row(db, world.manager_id))
    assert exc.value.status == 403
    assert exc.value.message == MSG_NOT_MOBILE


def test_b0_teacher_scope_carries_user_id_and_both_rooms(db, world):
    scope = resolve_scope(db, _user_row(db, world.teacher_id))
    assert scope.role == "teacher"
    assert str(scope.user_id) == world.teacher_id
    assert {str(r) for r in scope.room_ids} == {world.room_a, world.room_b}
    assert {str(s) for s in scope.student_ids} == {world.a1, world.a2, world.b1}
