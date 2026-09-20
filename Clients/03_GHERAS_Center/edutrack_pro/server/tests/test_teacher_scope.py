"""B-5.1 regression: teacher accounts are row-scoped on the three attendance.py routes.
B-5.2: POST /attendance/staff is manager/supervisor only.

Scope = home room (users.room_id) ∪ scheduled rooms (schedules.teacher_user_id = me).
Any student outside that set → 403 forbidden on writes, hidden on reads.
"""

from __future__ import annotations

import datetime

from tests.conftest import _room, _student, _teacher

TODAY = datetime.date.today().isoformat()


def _att(student_id: str, status: str = "حاضر") -> list[dict]:
    return [{"student_id": student_id, "date": TODAY, "status": status}]


def _eval(student_id: str, value: float = 9) -> list[dict]:
    return [{"student_id": student_id, "date": TODAY, "subject": "القرآن", "value": value}]


def test_teacher_cannot_record_attendance_outside_home_room(client, db, manager):
    room_a = _room(client, manager, "أ")
    room_b = _room(client, manager, "ب")
    other = _student(client, manager, "طالب ب", room_b)
    _, teacher = _teacher(db, client, "t_home", room_a)

    res = client.post("/api/v1/attendance/students", json=_att(other), headers=teacher)

    assert res.status_code == 403, res.text
    assert res.json()["error"]["code"] == "forbidden"
    assert db.execute("SELECT count(*) AS n FROM student_attendance").fetchone()["n"] == 0


def test_teacher_records_attendance_for_home_room_student(client, db, manager):
    room_a = _room(client, manager, "أ")
    mine = _student(client, manager, "طالب أ", room_a)
    _, teacher = _teacher(db, client, "t_home_ok", room_a)

    res = client.post("/api/v1/attendance/students", json=_att(mine), headers=teacher)

    assert res.status_code == 200, res.text
    assert res.json()["saved"] == 1


def test_teacher_records_attendance_for_scheduled_room_student(client, db, manager):
    room_a = _room(client, manager, "أ")
    room_b = _room(client, manager, "ب")
    in_b = _student(client, manager, "طالب ب", room_b)
    teacher_id, teacher = _teacher(db, client, "t_sched", room_a)
    db.execute(
        "INSERT INTO schedules (room_id, teacher_user_id, day, start_time, end_time, subject) "
        "VALUES (%s, %s, 'الأحد', '08:00', '09:00', 'القرآن')",
        (room_b, teacher_id),
    )
    db.commit()

    res = client.post("/api/v1/attendance/students", json=_att(in_b), headers=teacher)

    assert res.status_code == 200, res.text


def test_teacher_mixed_batch_is_rejected_whole(client, db, manager):
    room_a = _room(client, manager, "أ")
    room_b = _room(client, manager, "ب")
    mine = _student(client, manager, "طالب أ", room_a)
    other = _student(client, manager, "طالب ب", room_b)
    _, teacher = _teacher(db, client, "t_mixed", room_a)

    res = client.post("/api/v1/attendance/students", json=_att(mine) + _att(other), headers=teacher)

    assert res.status_code == 403, res.text
    assert db.execute("SELECT count(*) AS n FROM student_attendance").fetchone()["n"] == 0


def test_teacher_daily_evaluations_list_is_scoped(client, db, manager):
    room_a = _room(client, manager, "أ")
    room_b = _room(client, manager, "ب")
    mine = _student(client, manager, "طالب أ", room_a)
    other = _student(client, manager, "طالب ب", room_b)
    assert client.post("/api/v1/evaluations/daily", json=_eval(mine, 8) + _eval(other, 7), headers=manager).status_code == 200
    _, teacher = _teacher(db, client, "t_list", room_a)

    res = client.get(f"/api/v1/daily-evaluations?date={TODAY}", headers=teacher)
    assert res.status_code == 200, res.text
    assert [r["student_id"] for r in res.json()["items"]] == [mine]
    assert res.json()["total"] == 1

    # Explicit out-of-scope filter → empty 200, not a leak
    filtered = client.get(f"/api/v1/evaluations/daily?student_id={other}", headers=teacher)
    assert filtered.status_code == 200, filtered.text
    assert filtered.json() == {"items": [], "total": 0}

    # Manager still sees both (regression guard)
    both = client.get(f"/api/v1/daily-evaluations?date={TODAY}", headers=manager)
    assert both.json()["total"] == 2


def test_teacher_cannot_write_daily_evaluation_outside_scope(client, db, manager):
    room_a = _room(client, manager, "أ")
    room_b = _room(client, manager, "ب")
    other = _student(client, manager, "طالب ب", room_b)
    _, teacher = _teacher(db, client, "t_eval", room_a)

    for path in ("/api/v1/evaluations/daily", "/api/v1/daily-evaluations"):
        res = client.post(path, json=_eval(other), headers=teacher)
        assert res.status_code == 403, (path, res.text)
        assert res.json()["error"]["code"] == "forbidden"
    assert db.execute("SELECT count(*) AS n FROM evaluations").fetchone()["n"] == 0


def test_teacher_writes_daily_evaluation_inside_scope(client, db, manager):
    room_a = _room(client, manager, "أ")
    mine = _student(client, manager, "طالب أ", room_a)
    _, teacher = _teacher(db, client, "t_eval_ok", room_a)

    res = client.post("/api/v1/evaluations/daily", json=_eval(mine), headers=teacher)

    assert res.status_code == 200, res.text
    assert res.json()["saved"] == 1


def test_teacher_without_room_or_schedule_has_empty_scope(client, db, manager):
    room_b = _room(client, manager, "ب")
    other = _student(client, manager, "طالب ب", room_b)
    assert client.post("/api/v1/evaluations/daily", json=_eval(other), headers=manager).status_code == 200
    _, teacher = _teacher(db, client, "t_empty", None)

    listed = client.get("/api/v1/daily-evaluations", headers=teacher)
    assert listed.status_code == 200, listed.text
    assert listed.json() == {"items": [], "total": 0}

    assert client.post("/api/v1/attendance/students", json=_att(other), headers=teacher).status_code == 403
    assert client.post("/api/v1/evaluations/daily", json=_eval(other), headers=teacher).status_code == 403


def test_teacher_scope_excludes_inactive_students(client, db, manager):
    room_a = _room(client, manager, "أ")
    dismissed = _student(client, manager, "طالب مفصول", room_a)
    db.execute("UPDATE students SET status = 'dismissed' WHERE id = %s", (dismissed,))
    db.commit()
    _, teacher = _teacher(db, client, "t_inactive", room_a)

    res = client.post("/api/v1/attendance/students", json=_att(dismissed), headers=teacher)

    assert res.status_code == 403, res.text


def test_teacher_cannot_record_staff_attendance(client, db, manager):
    # B-5.2: staff attendance writes payroll deductions; only manager/supervisor may call it.
    staff = client.post("/api/v1/staff", json={"name": "موظف", "role_title": "إداري", "base_salary": 3000}, headers=manager).json()
    _, teacher = _teacher(db, client, "t_staff", None)

    res = client.post(
        "/api/v1/attendance/staff",
        json=[{"staff_id": staff["id"], "date": TODAY, "status": "غائب", "deduction": 100}],
        headers=teacher,
    )

    assert res.status_code == 403, res.text
    assert res.json()["error"]["code"] == "forbidden"
    assert db.execute("SELECT count(*) AS n FROM staff_attendance").fetchone()["n"] == 0
    assert db.execute("SELECT count(*) AS n FROM payroll_runs").fetchone()["n"] == 0
