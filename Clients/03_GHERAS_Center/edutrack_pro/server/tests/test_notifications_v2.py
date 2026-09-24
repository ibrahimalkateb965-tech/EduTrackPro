"""Tests for Notifications v2 (FastAPI backend):
1. Teacher broadcast and idempotent replay.
2. Assignment notifications with multi-child guardian support and client-supplied ID.
3. Attendance absence notifications deduplication across re-saves and after deletion.
4. Delete notification endpoint.
5. Clear-read notifications endpoint.
"""

from __future__ import annotations

import datetime as dt
import os
import uuid

import pytest

from tests.conftest import _guardian, _room, _student, _teacher, login, make_user

TODAY = dt.date.today()
ME = "/api/v1/me"


@pytest.fixture(autouse=True)
def apply_v2_migration(db):
    """Ensure 010_notifications_v2.sql is applied to test database."""
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    migration_path = os.path.join(base_dir, "db", "postgres", "010_notifications_v2.sql")
    if os.path.exists(migration_path):
        with open(migration_path, "r", encoding="utf-8") as f:
            db.execute(f.read())
        db.commit()


def test_broadcast_and_idempotent_replay(db, client, manager):
    # Setup: 1 room, 1 teacher, 2 students with 2 guardians
    room_id = _room(client, manager, "قاعة التميز")
    t_id, t_auth = _teacher(db, client, "teacher_bcast", room_id)
    s1 = _student(client, manager, "طالب أ", room_id)
    s2 = _student(client, manager, "طالب ب", room_id)
    g1_id, g1_auth = _guardian(db, client, "ولي أمر أ", [s1])
    g2_id, g2_auth = _guardian(db, client, "ولي أمر ب", [s2])

    bcast_id = str(uuid.uuid4())
    payload = {
        "id": bcast_id,
        "room_id": room_id,
        "title": "تعميم هام بخصوص الاختبار",
        "body": "نرجو الحضور مبكراً غداً",
        "priority": "urgent",
        "include_guardians": True,
        "include_students": False,
    }

    # 1. First broadcast
    res = client.post(f"{ME}/notifications/broadcast", json=payload, headers=t_auth)
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["id"] == bcast_id
    assert data["recipient_count"] == 2

    # Verify notifications received by guardians
    n1 = client.get(f"{ME}/notifications", headers=g1_auth).json()["items"]
    assert len(n1) == 1
    assert n1[0]["title"] == "تعميم هام بخصوص الاختبار"
    assert n1[0]["priority"] == "urgent"

    n2 = client.get(f"{ME}/notifications", headers=g2_auth).json()["items"]
    assert len(n2) == 1

    # 2. Replay same broadcast -> returns same response without duplicate rows
    res_replay = client.post(f"{ME}/notifications/broadcast", json=payload, headers=t_auth)
    assert res_replay.status_code == 200, res_replay.text
    data_replay = res_replay.json()
    assert data_replay["id"] == bcast_id
    assert data_replay["recipient_count"] == 2

    # Still exactly 1 notification per guardian
    n1_after = client.get(f"{ME}/notifications", headers=g1_auth).json()["items"]
    assert len(n1_after) == 1


def test_assignment_notifications_multi_child_guardian(db, client, manager):
    # Setup: 1 room, 1 teacher, 2 children belonging to the SAME guardian
    room_id = _room(client, manager, "قاعة النور")
    t_id, t_auth = _teacher(db, client, "teacher_asgn", room_id)
    c1 = _student(client, manager, "الطفل الأول", room_id)
    c2 = _student(client, manager, "الطفل الثاني", room_id)
    g_id, g_auth = _guardian(db, client, "ولي أمرين", [c1, c2])

    assignment_id = str(uuid.uuid4())
    due_date = (TODAY + dt.timedelta(days=2)).isoformat()
    res = client.post(
        f"{ME}/assignments",
        json={
            "id": assignment_id,
            "title": "حفظ سورة الأعلى",
            "subject": "القرآن",
            "due_date": due_date,
            "student_ids": [c1, c2],
        },
        headers=t_auth,
    )
    assert res.status_code == 200, res.text

    # Guardian must get 2 notifications (one for each child), each with the child's name
    notifs = client.get(f"{ME}/notifications", headers=g_auth).json()["items"]
    assert len(notifs) == 2
    bodies = [n["body"] for n in notifs]
    assert any("الطفل الأول" in b for b in bodies)
    assert any("الطفل الثاني" in b for b in bodies)

    # Calling update on the same assignment should NOT re-emit notifications
    res_update = client.post(
        f"{ME}/assignments",
        json={
            "id": assignment_id,
            "title": "حفظ سورة الأعلى - تحديث",
            "subject": "القرآن",
            "due_date": due_date,
            "student_ids": [c1, c2],
        },
        headers=t_auth,
    )
    assert res_update.status_code == 200, res_update.text
    notifs_after = client.get(f"{ME}/notifications", headers=g_auth).json()["items"]
    assert len(notifs_after) == 2  # No duplicates added


def test_attendance_absence_dedup_and_delete(db, client, manager):
    room_id = _room(client, manager, "قاعة الصفوة")
    t_id, t_auth = _teacher(db, client, "teacher_att", room_id)
    s_id = _student(client, manager, "طالب غائب", room_id)
    g_id, g_auth = _guardian(db, client, "ولي الغائب", [s_id])

    att_date = TODAY.isoformat()

    # 1. Mark absent
    save_res = client.post(
        "/api/v1/attendance/students",
        json=[{"student_id": s_id, "date": att_date, "status": "غائب"}],
        headers=t_auth,
    )
    assert save_res.status_code == 200, save_res.text

    # Guardian received 1 urgent absence alert
    notifs = client.get(f"{ME}/notifications", headers=g_auth).json()["items"]
    assert len(notifs) == 1
    notif = notifs[0]
    assert notif["kind"] == "attendance"
    assert notif["priority"] == "urgent"
    assert notif["target_id"] == s_id

    # 2. Re-save attendance -> must not create duplicate
    client.post(
        "/api/v1/attendance/students",
        json=[{"student_id": s_id, "date": att_date, "status": "غائب"}],
        headers=t_auth,
    )
    notifs2 = client.get(f"{ME}/notifications", headers=g_auth).json()["items"]
    assert len(notifs2) == 1

    # 3. Guardian deletes the notification
    del_res = client.delete(f"{ME}/notifications/{notif['id']}", headers=g_auth)
    assert del_res.status_code == 200, del_res.text

    notifs_empty = client.get(f"{ME}/notifications", headers=g_auth).json()["items"]
    assert len(notifs_empty) == 0

    # 4. Teacher re-saves attendance -> still must NOT create new alert because it was already sent
    client.post(
        "/api/v1/attendance/students",
        json=[{"student_id": s_id, "date": att_date, "status": "غائب"}],
        headers=t_auth,
    )
    notifs_still_empty = client.get(f"{ME}/notifications", headers=g_auth).json()["items"]
    assert len(notifs_still_empty) == 0


def test_clear_read_notifications(db, client, manager):
    room_id = _room(client, manager, "قاعة الاختبار")
    t_id, t_auth = _teacher(db, client, "teacher_clear", room_id)
    s1 = _student(client, manager, "طالب أ", room_id)
    g_id, g_auth = _guardian(db, client, "ولي إشعارات", [s1])

    # Insert 3 notifications directly
    n_ids = []
    for i in range(3):
        nid = str(uuid.uuid4())
        n_ids.append(nid)
        db.execute(
            "INSERT INTO notifications (id, user_id, kind, title, read_at) VALUES (%s, %s, 'info', %s, %s)",
            (nid, g_id, f"إشعار {i}", dt.datetime.now() if i < 2 else None),
        )
    db.commit()

    all_notifs = client.get(f"{ME}/notifications", headers=g_auth).json()["items"]
    assert len(all_notifs) == 3

    # Clear read (first 2)
    clear_res = client.post(f"{ME}/notifications/clear-read", json={"ids": n_ids[:2]}, headers=g_auth)
    assert clear_res.status_code == 200, clear_res.text

    remaining = client.get(f"{ME}/notifications", headers=g_auth).json()["items"]
    assert len(remaining) == 1
    assert remaining[0]["id"] == n_ids[2]


def test_broadcast_out_of_scope_forbidden(db, client, manager):
    # Two teachers in two different rooms
    room1 = _room(client, manager, "قاعة الأولى")
    room2 = _room(client, manager, "قاعة الثانية")
    t1_id, t1_auth = _teacher(db, client, "teacher_one", room1)
    t2_id, t2_auth = _teacher(db, client, "teacher_two", room2)
    s2 = _student(client, manager, "طالب الغرفة 2", room2)
    g2_id, g2_auth = _guardian(db, client, "ولي الغرفة 2", [s2])

    # 1. Teacher 1 tries to broadcast to room 2 (out of scope) -> 403
    res1 = client.post(
        f"{ME}/notifications/broadcast",
        json={"id": str(uuid.uuid4()), "room_id": room2, "title": "اختراق"},
        headers=t1_auth,
    )
    assert res1.status_code == 403, res1.text

    # 2. Teacher 1 tries to broadcast to student 2 directly (out of scope) -> 403
    res2 = client.post(
        f"{ME}/notifications/broadcast",
        json={"id": str(uuid.uuid4()), "student_ids": [s2], "title": "اختراق طالب"},
        headers=t1_auth,
    )
    assert res2.status_code == 403, res2.text

    # 3. Guardian tries to broadcast -> 403
    res3 = client.post(
        f"{ME}/notifications/broadcast",
        json={"id": str(uuid.uuid4()), "student_ids": [s2], "title": "ولي يبث"},
        headers=g2_auth,
    )
    assert res3.status_code == 403, res3.text

    # 4. No notification reached guardian 2
    n_g2 = client.get(f"{ME}/notifications", headers=g2_auth).json()["items"]
    assert len(n_g2) == 0

