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


# =============================================================================
# b1 — profile, students, rooms, schedule, notifications, boundary
# =============================================================================


def test_b1_unauthenticated_profile_is_401(client):
    res = client.get(f"{ME}/profile")
    assert res.status_code == 401, res.text


def test_b1_manager_is_rejected_on_me_routes(client, manager):
    res = client.get(f"{ME}/profile", headers=manager)
    assert res.status_code == 403, res.text
    assert res.json()["error"] == {"code": "forbidden", "message": MSG_NOT_MOBILE}


def test_b1_guardian_without_link_is_403(client, db):
    make_user(db, "g_orphan", "guardian")

    res = client.get(f"{ME}/profile", headers=login(client, "g_orphan"))
    assert res.status_code == 403, res.text
    assert res.json()["error"]["message"] == MSG_GUARDIAN_UNLINKED


def test_b1_guardian_cannot_list_rooms(client, world):
    res = client.get(f"{ME}/rooms", headers=world.guardian)
    assert res.status_code == 403, res.text
    assert res.json()["error"]["code"] == "forbidden"


def test_b1_teacher_profile_has_staff_name_scope_and_center(client, db, manager, world):
    staff = client.post("/api/v1/staff", json={"name": "المعلم أحمد", "role_title": "معلم", "base_salary": 3000}, headers=manager).json()
    db.execute("UPDATE users SET staff_id = %s WHERE id = %s", (staff["id"], world.teacher_id))
    db.commit()

    res = client.get(f"{ME}/profile", headers=world.teacher)

    assert res.status_code == 200, res.text
    body = res.json()
    assert body["user"] == {"id": world.teacher_id, "username": "t_main", "role": "teacher", "name": "المعلم أحمد"}
    assert set(body["scope"]["room_ids"]) == {world.room_a, world.room_b}
    assert set(body["scope"]["student_ids"]) == {world.a1, world.a2, world.b1}
    assert isinstance(body["center"]["name"], str) and body["center"]["name"]
    assert isinstance(body["center"]["phone"], str)


def test_b1_guardian_profile_uses_guardian_name(client, world):
    res = client.get(f"{ME}/profile", headers=world.guardian)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["user"]["name"] == "g_main"
    assert body["user"]["role"] == "guardian"
    assert body["scope"]["room_ids"] == []
    assert set(body["scope"]["student_ids"]) == {world.a1, world.c1}


def test_b1_teacher_students_are_home_room_union_scheduled_rooms(client, world):
    res = client.get(f"{ME}/students", headers=world.teacher)
    assert res.status_code == 200, res.text
    body = res.json()
    assert {r["id"] for r in body["items"]} == {world.a1, world.a2, world.b1}
    assert body["total"] == 3 and body["limit"] == 100 and body["offset"] == 0
    names = [r["name"] for r in body["items"]]
    assert names == sorted(names)  # ORDER BY name, id


def test_b1_teacher_student_projection_keeps_contact_hides_identity(client, world):
    row = client.get(f"{ME}/students", headers=world.teacher).json()["items"][0]
    for key in ("id", "name", "room_id", "room_name", "status", "has_difficulties", "guardian_phone", "guardian_relation", "difficulty_notes", "child_notes"):
        assert key in row, key
    for key in ("national_id", "father_phone", "mother_phone", "pickup_phone", "created_at"):
        assert key not in row, key
    assert row["room_name"] in {"أ", "ب"}


def test_b1_guardian_students_include_dismissed_child_and_drop_private_columns(client, world):
    res = client.get(f"{ME}/students", headers=world.guardian)
    assert res.status_code == 200, res.text
    items = res.json()["items"]
    assert {r["id"] for r in items} == {world.a1, world.c1}
    assert {r["status"] for r in items} == {"active", "dismissed"}
    for row in items:
        for key in ("guardian_phone", "guardian_relation", "difficulty_notes", "child_notes", "national_id"):
            assert key not in row, key


def test_b1_out_of_scope_student_filter_is_empty_200(client, world):
    res = client.get(f"{ME}/students?student_id={world.c1}", headers=world.teacher)
    assert res.status_code == 200, res.text
    assert res.json()["items"] == [] and res.json()["total"] == 0


def test_b1_teacher_rooms_are_home_and_scheduled(client, world):
    res = client.get(f"{ME}/rooms", headers=world.teacher)
    assert res.status_code == 200, res.text
    assert {r["id"] for r in res.json()["items"]} == {world.room_a, world.room_b}


def test_b1_teacher_without_room_or_schedule_sees_empty_lists(client, db, world):
    _, empty = _teacher(db, client, "t_empty", None)
    for path in ("students", "rooms", "schedule", "notifications"):
        res = client.get(f"{ME}/{path}", headers=empty)
        assert res.status_code == 200, (path, res.text)
        assert res.json()["items"] == [] and res.json()["total"] == 0, path


def test_b1_teacher_schedule_covers_both_rooms_with_names(client, world):
    res = client.get(f"{ME}/schedule", headers=world.teacher)
    assert res.status_code == 200, res.text
    items = res.json()["items"]
    assert {r["id"] for r in items} == {world.sched_a, world.sched_b}
    by_id = {r["id"]: r for r in items}
    assert by_id[world.sched_b]["room_name"] == "ب"
    assert by_id[world.sched_b]["teacher_name"] == "t_main"  # username fallback, no staff link
    assert by_id[world.sched_a]["teacher_name"] is None
    day_only = client.get(f"{ME}/schedule?day=الأحد", headers=world.teacher).json()
    assert [r["id"] for r in day_only["items"]] == [world.sched_b]


def test_b1_guardian_schedule_is_children_rooms(client, world):
    res = client.get(f"{ME}/schedule", headers=world.guardian)
    assert res.status_code == 200, res.text
    assert {r["id"] for r in res.json()["items"]} == {world.sched_a, world.sched_c}
    other = client.get(f"{ME}/schedule", headers=world.guardian2).json()
    assert [r["id"] for r in other["items"]] == [world.sched_b]


def test_b1_notifications_are_own_rows_only_and_mark_read(client, db, world):
    n_t = _notify(db, world.teacher_id, "تنبيه للمعلم")
    n_g = _notify(db, world.guardian_id, "تنبيه لولي الأمر")

    listed = client.get(f"{ME}/notifications", headers=world.teacher).json()
    assert [r["id"] for r in listed["items"]] == [n_t]
    assert listed["items"][0]["read_at"] is None

    read = client.post(f"{ME}/notifications/{n_t}/read", headers=world.teacher)
    assert read.status_code == 200, read.text
    assert read.json()["id"] == n_t and read.json()["read_at"] is not None

    unread = client.get(f"{ME}/notifications?unread=true", headers=world.teacher).json()
    assert unread["total"] == 0
    seen = client.get(f"{ME}/notifications?unread=false", headers=world.teacher).json()
    assert seen["total"] == 1

    foreign = client.post(f"{ME}/notifications/{n_g}/read", headers=world.teacher)
    assert foreign.status_code == 404, foreign.text
    assert db.execute("SELECT read_at FROM notifications WHERE id = %s", (n_g,)).fetchone()["read_at"] is None


def test_b1_bad_pagination_is_422(client, world):
    res = client.get(f"{ME}/students?limit=0", headers=world.teacher)
    assert res.status_code == 422, res.text
    assert res.json()["error"] == {"code": "validation_error", "message": "قيم التصفح غير صحيحة"}
    assert client.get(f"{ME}/students?offset=-1", headers=world.teacher).status_code == 422
    assert client.get(f"{ME}/students?limit=501", headers=world.teacher).status_code == 422


def test_b1_page_past_the_end_keeps_true_total(client, world):
    """Review follow-up: COUNT(*) OVER() rides on the rows, so an offset past the last page must not report total=0."""
    res = client.get(f"{ME}/students?limit=2&offset=10", headers=world.teacher)
    assert res.status_code == 200, res.text
    assert res.json() == {"items": [], "total": 3, "limit": 2, "offset": 10}


# =============================================================================
# b2 — attendance, evaluations, assignments, submissions, lesson-logs GET, skill-progress
# =============================================================================


def _attend(client, manager, student_id: str, day: dt.date, status: str = "حاضر") -> None:
    res = client.post("/api/v1/attendance/students", json=[{"student_id": student_id, "date": _iso(day), "status": status}], headers=manager)
    assert res.status_code == 200, res.text


def _assignment(db, title: str, teacher_id: str | None, student_ids: list[str], due: dt.date = TODAY) -> str:
    aid = db.execute(
        "INSERT INTO assignments (title, due_date, teacher_user_id) VALUES (%s, %s, %s) RETURNING id",
        (title, due, teacher_id),
    ).fetchone()["id"]
    for sid in student_ids:
        db.execute("INSERT INTO assignment_students (assignment_id, student_id) VALUES (%s, %s)", (aid, sid))
    db.commit()
    return str(aid)


def _submission(db, assignment_id: str, student_id: str, storage_key: str | None = None) -> str:
    sid = db.execute(
        "INSERT INTO submissions (assignment_id, student_id) VALUES (%s, %s) RETURNING id",
        (assignment_id, student_id),
    ).fetchone()["id"]
    if storage_key:
        db.execute(
            "INSERT INTO submission_files (submission_id, storage_key, width, height) VALUES (%s, %s, 800, 600)",
            (sid, storage_key),
        )
    db.commit()
    return str(sid)


def _lesson_log(db, schedule_id: str, day: dt.date, status: str = "تمت", notes: str | None = None, teacher_id: str | None = None) -> str:
    row = db.execute(
        "INSERT INTO lesson_logs (schedule_id, date, status, notes, teacher_user_id) VALUES (%s, %s, %s, %s, %s) RETURNING id",
        (schedule_id, day, status, notes, teacher_id),
    ).fetchone()
    db.commit()
    return str(row["id"])


def _skill(db, student_id: str, subject: str = "القرآن") -> None:
    db.execute(
        "INSERT INTO skill_progress (student_id, subject, skill, level, date) VALUES (%s, %s, 'حفظ', 'متقن', %s)",
        (student_id, subject, TODAY),
    )
    db.commit()


def test_b2_attendance_is_scoped_and_filterable(client, manager, world):
    _attend(client, manager, world.a1, TODAY)
    _attend(client, manager, world.b1, TODAY)
    _attend(client, manager, world.c1, TODAY)

    teacher_view = client.get(f"{ME}/attendance", headers=world.teacher).json()
    assert {r["student_id"] for r in teacher_view["items"]} == {world.a1, world.b1}
    assert all("student_name" in r for r in teacher_view["items"])

    guardian_view = client.get(f"{ME}/attendance", headers=world.guardian).json()
    assert {r["student_id"] for r in guardian_view["items"]} == {world.a1, world.c1}

    probe = client.get(f"{ME}/attendance?student_id={world.b1}", headers=world.guardian)
    assert probe.status_code == 200 and probe.json() == {"items": [], "total": 0, "limit": 100, "offset": 0}

    window = client.get(f"{ME}/attendance?date_from={_iso(TODAY + dt.timedelta(days=1))}", headers=world.teacher).json()
    assert window["total"] == 0


def test_b2_attendance_pagination_is_stable(client, manager, world):
    for i in range(5):
        _attend(client, manager, world.a1, TODAY - dt.timedelta(days=i))

    page = client.get(f"{ME}/attendance?student_id={world.a1}&limit=2&offset=2", headers=world.teacher)
    assert page.status_code == 200, page.text
    body = page.json()
    assert body["total"] == 5 and body["limit"] == 2 and body["offset"] == 2
    assert [r["date"] for r in body["items"]] == [_iso(TODAY - dt.timedelta(days=2)), _iso(TODAY - dt.timedelta(days=3))]


def test_b2_evaluations_are_scoped_with_type_filter(client, manager, world):
    payload = [
        {"student_id": world.a1, "date": _iso(TODAY), "subject": "القرآن", "value": 9},
        {"student_id": world.b1, "date": _iso(TODAY), "subject": "القرآن", "value": 7},
    ]
    assert client.post("/api/v1/evaluations/daily", json=payload, headers=manager).status_code == 200

    mine = client.get(f"{ME}/evaluations", headers=world.guardian).json()
    assert [r["student_id"] for r in mine["items"]] == [world.a1]
    assert mine["items"][0]["value"] == 9.0  # Rule 9: float, not "9.00"
    assert client.get(f"{ME}/evaluations?eval_type=daily", headers=world.guardian).json()["total"] == 1
    assert client.get(f"{ME}/evaluations?eval_type=monthly", headers=world.guardian).json()["total"] == 0
    assert client.get(f"{ME}/evaluations", headers=world.teacher).json()["total"] == 2


def test_b2_assignments_teacher_sees_own_or_linked_guardian_sees_children_only(client, db, world):
    other_id = str(make_user(db, "t_other", "teacher"))
    asg1 = _assignment(db, "واجب 1", world.teacher_id, [world.a1, world.b1])
    asg2 = _assignment(db, "واجب 2", other_id, [world.c1])
    asg3 = _assignment(db, "واجب 3", world.teacher_id, [])  # own, no links

    teacher_view = client.get(f"{ME}/assignments", headers=world.teacher).json()
    assert {r["id"] for r in teacher_view["items"]} == {asg1, asg3}
    by_id = {r["id"]: r for r in teacher_view["items"]}
    assert sorted(by_id[asg1]["student_ids"]) == sorted([world.a1, world.b1])
    assert by_id[asg3]["student_ids"] == []

    g_view = client.get(f"{ME}/assignments", headers=world.guardian).json()
    assert {r["id"] for r in g_view["items"]} == {asg1, asg2}
    g_by_id = {r["id"]: r for r in g_view["items"]}
    assert g_by_id[asg1]["student_ids"] == [world.a1]  # b1 hidden
    assert g_by_id[asg2]["student_ids"] == [world.c1]

    g2_view = client.get(f"{ME}/assignments", headers=world.guardian2).json()
    assert [r["id"] for r in g2_view["items"]] == [asg1]
    assert g2_view["items"][0]["student_ids"] == [world.b1]

    assert client.get(f"{ME}/assignments?student_id={world.c1}", headers=world.teacher).json()["total"] == 0
    assert client.get(f"{ME}/assignments?student_id={world.b1}", headers=world.teacher).json()["total"] == 1


def test_b2_submissions_never_leak_another_childs_files(client, db, world):
    asg = _assignment(db, "واجب", world.teacher_id, [world.a1, world.b1])
    sub_a1 = _submission(db, asg, world.a1, storage_key="sub/a1.jpg")
    sub_b1 = _submission(db, asg, world.b1)

    g_view = client.get(f"{ME}/submissions", headers=world.guardian).json()
    assert [r["id"] for r in g_view["items"]] == [sub_a1]
    row = g_view["items"][0]
    assert row["assignment_title"] == "واجب" and row["student_name"] == "طالب أ1"
    assert [f["storage_key"] for f in row["files"]] == ["sub/a1.jpg"]
    assert set(row["files"][0]) == {"id", "storage_key", "width", "height"}

    g2_view = client.get(f"{ME}/submissions", headers=world.guardian2).json()
    assert [r["id"] for r in g2_view["items"]] == [sub_b1]
    assert g2_view["items"][0]["files"] == []

    teacher_view = client.get(f"{ME}/submissions?assignment_id={asg}", headers=world.teacher).json()
    assert {r["id"] for r in teacher_view["items"]} == {sub_a1, sub_b1}


def test_b2_lesson_logs_teacher_sees_notes_guardian_does_not(client, db, world):
    log_a = _lesson_log(db, world.sched_a, TODAY, notes="ملاحظة داخلية")
    log_c = _lesson_log(db, world.sched_c, TODAY, notes="ملاحظة أخرى")

    teacher_view = client.get(f"{ME}/lesson-logs", headers=world.teacher).json()
    assert [r["id"] for r in teacher_view["items"]] == [log_a]  # room A in scope, room C not
    assert teacher_view["items"][0]["notes"] == "ملاحظة داخلية"
    assert teacher_view["items"][0]["room_name"] == "أ"

    g_view = client.get(f"{ME}/lesson-logs", headers=world.guardian).json()
    assert {r["id"] for r in g_view["items"]} == {log_a, log_c}  # a1 in A, c1 in C
    for row in g_view["items"]:
        assert "notes" not in row
        assert "covered" in row and "homework" in row

    assert client.get(f"{ME}/lesson-logs?schedule_id={world.sched_c}", headers=world.teacher).json()["total"] == 0
    assert client.get(f"{ME}/lesson-logs?schedule_id={world.sched_c}", headers=world.guardian).json()["total"] == 1


def test_b2_skill_progress_is_scoped_with_subject_filter(client, db, world):
    _skill(db, world.a1, "القرآن")
    _skill(db, world.b1, "القرآن")

    g_view = client.get(f"{ME}/skill-progress", headers=world.guardian).json()
    assert [r["student_id"] for r in g_view["items"]] == [world.a1]
    assert g_view["items"][0]["student_name"] == "طالب أ1"
    assert client.get(f"{ME}/skill-progress?subject=لغتي", headers=world.guardian).json()["total"] == 0
    assert client.get(f"{ME}/skill-progress", headers=world.teacher).json()["total"] == 2


# =============================================================================
# b3 — installments, receipts, POST lesson-logs
# =============================================================================


def _fee_plan(db, student_id: str, total: int = 1000, count: int = 2) -> str:
    fp = db.execute(
        "INSERT INTO fee_plans (student_id, total_amount, count, start_date, interval_days) VALUES (%s, %s, %s, %s, 30) RETURNING id",
        (student_id, total, count, TODAY),
    ).fetchone()["id"]
    for seq in range(1, count + 1):
        db.execute(
            "INSERT INTO installments (fee_plan_id, seq_no, due_date, amount) VALUES (%s, %s, %s, %s)",
            (fp, seq, TODAY + dt.timedelta(days=30 * (seq - 1)), total / count),
        )
    db.commit()
    return str(fp)


def _receipt(db, student_id: str, amount: int = 500) -> str:
    pid = db.execute(
        "INSERT INTO payments (student_id, amount, method, paid_on) VALUES (%s, %s, 'كاش', %s) RETURNING id",
        (student_id, amount, TODAY),
    ).fetchone()["id"]
    rid = db.execute("INSERT INTO receipts (payment_id) VALUES (%s) RETURNING id", (pid,)).fetchone()["id"]
    db.commit()
    return str(rid)


def test_b3_teacher_is_rejected_on_finance_routes(client, world):
    for path in ("installments", "receipts"):
        res = client.get(f"{ME}/{path}", headers=world.teacher)
        assert res.status_code == 403, (path, res.text)
        assert res.json()["error"]["code"] == "forbidden"


def test_b3_guardian_cannot_post_lesson_log(client, world):
    res = client.post(f"{ME}/lesson-logs", json={"schedule_id": world.sched_a, "date": _iso(TODAY), "status": "تمت"}, headers=world.guardian)
    assert res.status_code == 403, res.text


def test_b3_installments_only_own_children(client, db, world):
    _fee_plan(db, world.a1)
    _fee_plan(db, world.b1)

    res = client.get(f"{ME}/installments", headers=world.guardian)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["total"] == 2 and {r["student_id"] for r in body["items"]} == {world.a1}
    assert [r["seq_no"] for r in body["items"]] == [1, 2]  # ORDER BY due_date, id
    assert body["items"][0]["amount"] == 500.0 and body["items"][0]["plan_total"] == 1000.0
    assert body["items"][0]["student_name"] == "طالب أ1"
    assert client.get(f"{ME}/installments?status=paid", headers=world.guardian).json()["total"] == 0
    assert client.get(f"{ME}/installments?student_id={world.b1}", headers=world.guardian).json()["total"] == 0


def test_b3_receipts_only_own_children(client, db, world):
    r_a1 = _receipt(db, world.a1, 500)
    r_b1 = _receipt(db, world.b1, 300)

    g_view = client.get(f"{ME}/receipts", headers=world.guardian).json()
    assert [r["id"] for r in g_view["items"]] == [r_a1]
    assert g_view["items"][0]["amount"] == 500.0 and g_view["items"][0]["student_id"] == world.a1
    assert g_view["items"][0]["method"] == "كاش"
    g2_view = client.get(f"{ME}/receipts", headers=world.guardian2).json()
    assert [r["id"] for r in g2_view["items"]] == [r_b1]


def _log_body(schedule_id: str, status: str = "تمت", **extra) -> dict:
    return {"schedule_id": schedule_id, "date": _iso(TODAY), "status": status, **extra}


def test_b3_post_lesson_log_inserts_in_scheduled_room_and_audits(client, db, world):
    res = client.post(f"{ME}/lesson-logs", json=_log_body(world.sched_b, covered="الفاتحة", homework="حفظ", notes="داخلي"), headers=world.teacher)
    assert res.status_code == 200, res.text
    row = res.json()
    assert row["schedule_id"] == world.sched_b and row["teacher_user_id"] == world.teacher_id
    assert row["status"] == "تمت" and row["covered"] == "الفاتحة" and row["notes"] == "داخلي"
    audit = db.execute("SELECT * FROM audit_log WHERE entity = 'lesson_logs' AND entity_id = %s", (row["id"],)).fetchone()
    assert audit is not None and str(audit["actor_user_id"]) == world.teacher_id
    assert audit["details_json"]["status"] == "تمت"

    # Home-room slot owned by nobody is also writable (D2: room-based)
    home = client.post(f"{ME}/lesson-logs", json=_log_body(world.sched_a), headers=world.teacher)
    assert home.status_code == 200, home.text


def test_b3_post_lesson_log_updates_existing_pair(client, db, world):
    first = client.post(f"{ME}/lesson-logs", json=_log_body(world.sched_b, status="مؤجلة"), headers=world.teacher).json()
    second = client.post(f"{ME}/lesson-logs", json=_log_body(world.sched_b, status="تمت", covered="جزء عم"), headers=world.teacher)
    assert second.status_code == 200, second.text
    assert second.json()["id"] == first["id"]
    assert second.json()["status"] == "تمت" and second.json()["covered"] == "جزء عم"
    n = db.execute("SELECT count(*) AS n FROM lesson_logs WHERE schedule_id = %s AND date = %s", (world.sched_b, TODAY)).fetchone()["n"]
    assert n == 1


def test_b3_post_lesson_log_with_preexisting_duplicates_updates_newest(client, db, world):
    older = db.execute(
        "INSERT INTO lesson_logs (schedule_id, date, status, updated_at) VALUES (%s, %s, 'مؤجلة', now() - interval '1 day') RETURNING id",
        (world.sched_b, TODAY),
    ).fetchone()["id"]
    newer = db.execute(
        "INSERT INTO lesson_logs (schedule_id, date, status) VALUES (%s, %s, 'مؤجلة') RETURNING id",
        (world.sched_b, TODAY),
    ).fetchone()["id"]
    db.commit()

    res = client.post(f"{ME}/lesson-logs", json=_log_body(world.sched_b, status="ملغاة"), headers=world.teacher)

    assert res.status_code == 200, res.text
    assert res.json()["id"] == str(newer)
    assert db.execute("SELECT status FROM lesson_logs WHERE id = %s", (older,)).fetchone()["status"] == "مؤجلة"
    n = db.execute("SELECT count(*) AS n FROM lesson_logs WHERE schedule_id = %s AND date = %s", (world.sched_b, TODAY)).fetchone()["n"]
    assert n == 2


def test_b3_post_lesson_log_outside_rooms_is_403(client, db, world):
    res = client.post(f"{ME}/lesson-logs", json=_log_body(world.sched_c), headers=world.teacher)
    assert res.status_code == 403, res.text
    assert res.json()["error"] == {"code": "forbidden", "message": "الحلقة خارج نطاق صلاحيتك"}
    assert db.execute("SELECT count(*) AS n FROM lesson_logs").fetchone()["n"] == 0


def test_b3_post_lesson_log_unknown_schedule_is_404(client, world):
    res = client.post(f"{ME}/lesson-logs", json=_log_body(str(uuid.uuid4())), headers=world.teacher)
    assert res.status_code == 404, res.text
    assert res.json()["error"]["code"] == "not_found"


def test_b3_post_lesson_log_bad_body_is_422(client, world):
    bad_status = client.post(f"{ME}/lesson-logs", json=_log_body(world.sched_b, status="حضر"), headers=world.teacher)
    assert bad_status.status_code == 422, bad_status.text
    assert bad_status.json()["error"]["code"] == "validation_error"
    bad_date = client.post(f"{ME}/lesson-logs", json={"schedule_id": world.sched_b, "date": "20-09-2026", "status": "تمت"}, headers=world.teacher)
    assert bad_date.status_code == 422, bad_date.text
    missing = client.post(f"{ME}/lesson-logs", json={"date": _iso(TODAY), "status": "تمت"}, headers=world.teacher)
    assert missing.status_code == 422, missing.text

