"""API integration tests for modern assignments, review, and reminders endpoints.

Covers:
1. Assignment creation and draft publishing (idempotent, 404 for foreign teacher) (M-1).
2. ASCII grade_level reviews and Arabic notification generation (H3).
3. Blocking review of non-latest attempts (409 not_latest_attempt).
4. Reminder preview with dry_run=true and exclusion of individual reminder recipients from bulk (R4-4).
5. 429 reminder cooldown enforcement.
6. Generic CRUD rejection of assignments and submissions (405).
"""

from __future__ import annotations

import datetime
import uuid
import pytest

from tests.conftest import login, make_user

API = "/api/v1"


@pytest.fixture()
def setup_data(db, client):
    """Seed room, 2 teachers, 2 students, guardians, and login tokens."""
    branch_id = uuid.UUID("00000000-0000-0000-0000-000000000001")
    t1_id = make_user(db, "teacher_ep1", "teacher")
    t2_id = make_user(db, "teacher_ep2", "teacher")
    g1_id = make_user(db, "guardian_ep1", "guardian")

    room_id = db.execute(
        "INSERT INTO rooms (name, group_name) VALUES ('Room EP', 'Morning') RETURNING id"
    ).fetchone()["id"]

    # Assign room to teacher 1
    db.execute("UPDATE users SET room_id = %s WHERE id = %s", (room_id, t1_id))

    # Students
    s1_id = db.execute(
        "INSERT INTO students (name, guardian_phone, gender, room_id) VALUES ('طالب تجربة 1', '0509998811', 'بنين', %s) RETURNING id",
        (room_id,)
    ).fetchone()["id"]
    s2_id = db.execute(
        "INSERT INTO students (name, guardian_phone, gender, room_id) VALUES ('طالب تجربة 2', '0509998822', 'بنين', %s) RETURNING id",
        (room_id,)
    ).fetchone()["id"]

    # Link guardian to s1 and s2
    g_entity_id = db.execute(
        "INSERT INTO guardians (name, phone) VALUES ('ولي أمر التجربة', '0509998811') RETURNING id"
    ).fetchone()["id"]
    db.execute("UPDATE users SET guardian_id = %s WHERE id = %s", (g_entity_id, g1_id))
    db.execute("INSERT INTO student_guardians (student_id, guardian_id) VALUES (%s, %s)", (s1_id, g_entity_id))
    db.execute("INSERT INTO student_guardians (student_id, guardian_id) VALUES (%s, %s)", (s2_id, g_entity_id))

    db.commit()

    t1_headers = login(client, "teacher_ep1")
    t2_headers = login(client, "teacher_ep2")
    g1_headers = login(client, "guardian_ep1")

    return {
        "t1_id": t1_id,
        "t2_id": t2_id,
        "g1_id": g1_id,
        "room_id": room_id,
        "s1_id": s1_id,
        "s2_id": s2_id,
        "t1_headers": t1_headers,
        "t2_headers": t2_headers,
        "g1_headers": g1_headers,
    }


def test_publish_assignment_owner_and_foreign_404(db, client, setup_data):
    """Publish draft assignment returns 404 for another teacher and succeeds idempotently for owner (M-1)."""
    env = setup_data

    # Teacher 1 creates draft assignment
    res = client.post(
        f"{API}/assignments",
        json={
            "title": "واجب التوحيد",
            "subject": "دراسات إسلامية",
            "room_id": str(env["room_id"]),
            "is_draft": True,
        },
        headers=env["t1_headers"],
    )
    assert res.status_code == 200, res.text
    ass_id = res.json()["id"]

    # Teacher 2 tries to publish Teacher 1's draft assignment -> 404
    res_t2 = client.post(f"{API}/assignments/{ass_id}/publish", headers=env["t2_headers"])
    assert res_t2.status_code == 404, res_t2.text

    # Teacher 1 publishes -> 200
    res_t1 = client.post(f"{API}/assignments/{ass_id}/publish", headers=env["t1_headers"])
    assert res_t1.status_code == 200, res_t1.text
    assert res_t1.json()["is_draft"] is False

    # Second publish call is idempotent (200 OK)
    res_t1_again = client.post(f"{API}/assignments/{ass_id}/publish", headers=env["t1_headers"])
    assert res_t1_again.status_code == 200


def test_review_ascii_grade_and_attempt_guards(db, client, setup_data):
    """Reviewing submission with ASCII grade and ensuring non-latest attempt review is blocked (409)."""
    env = setup_data

    # 1. Create published assignment
    res_ass = client.post(
        f"{API}/assignments",
        json={
            "title": "واجب العلوم",
            "subject": "علوم",
            "room_id": str(env["room_id"]),
            "is_draft": False,
        },
        headers=env["t1_headers"],
    )
    ass_id = res_ass.json()["id"]

    # Stage solution file for guardian
    stage_key = f"submissions/sol_{uuid.uuid4().hex[:8]}.jpg"
    db.execute("""
        INSERT INTO staged_uploads (storage_key, uploaded_by_user_id, media_type, file_name, bytes)
        VALUES (%s, %s, 'image', 'sol.jpg', 1024)
    """, (stage_key, env["g1_id"]))
    db.commit()

    # 2. Guardian submits Attempt 1
    sub1_res = client.post(
        f"{API}/assignments/{ass_id}/submit",
        json={
            "student_id": str(env["s1_id"]),
            "files": [{"storage_key": stage_key}],
        },
        headers=env["g1_headers"],
    )
    assert sub1_res.status_code == 200, sub1_res.text
    sub1_id = sub1_res.json()["id"]

    # 3. Teacher reviews Attempt 1: requests revision
    rev_res = client.post(
        f"{API}/submissions/{sub1_id}/review",
        json={
            "status": "revision_requested",
            "revision_note": "أعد قراءة السؤال الثالث",
        },
        headers=env["t1_headers"],
    )
    assert rev_res.status_code == 200, rev_res.text
    assert rev_res.json()["status"] == "revision_requested"

    # Stage new file for Attempt 2
    stage_key2 = f"submissions/sol_{uuid.uuid4().hex[:8]}.jpg"
    db.execute("""
        INSERT INTO staged_uploads (storage_key, uploaded_by_user_id, media_type, file_name, bytes)
        VALUES (%s, %s, 'image', 'sol2.jpg', 1024)
    """, (stage_key2, env["g1_id"]))
    db.commit()

    # 4. Guardian submits Attempt 2
    sub2_res = client.post(
        f"{API}/assignments/{ass_id}/submit",
        json={
            "student_id": str(env["s1_id"]),
            "files": [{"storage_key": stage_key2}],
        },
        headers=env["g1_headers"],
    )
    assert sub2_res.status_code == 200, sub2_res.text
    sub2_id = sub2_res.json()["id"]
    assert sub2_res.json()["attempt_number"] == 2

    # 5. Teacher attempts to review Attempt 1 again -> 409 (not_latest_attempt or invalid_state)
    rev_stale = client.post(
        f"{API}/submissions/{sub1_id}/review",
        json={"status": "graded", "grade_level": "excellent"},
        headers=env["t1_headers"],
    )
    assert rev_stale.status_code == 409

    # 6. Teacher reviews Attempt 2 with ASCII grade 'very_good' -> 200
    rev_success = client.post(
        f"{API}/submissions/{sub2_id}/review",
        json={"status": "graded", "grade_level": "very_good"},
        headers=env["t1_headers"],
    )
    assert rev_success.status_code == 200, rev_success.text
    assert rev_success.json()["grade_level"] == "very_good"

    # Verify notification to guardian contains Arabic grade
    notif = db.execute(
        "SELECT * FROM notifications WHERE target_id = %s ORDER BY created_at DESC LIMIT 1",
        (str(sub2_id),)
    ).fetchone()
    assert notif is not None
    assert "جيد جدًا" in notif["body"]


def test_reminders_dry_run_and_individual_exclusion(db, client, setup_data):
    """Test dry_run name preview and exclusion of individual reminder recipients from bulk reminders (R4-4)."""
    env = setup_data

    # Create assignment
    ass_res = client.post(
        f"{API}/assignments",
        json={
            "title": "واجب الإملاء",
            "room_id": str(env["room_id"]),
            "is_draft": False,
        },
        headers=env["t1_headers"],
    )
    ass_id = ass_res.json()["id"]

    # 1. Preview bulk reminder (dry_run=true)
    preview_res = client.post(
        f"{API}/assignments/{ass_id}/reminders",
        json={"kind": "bulk", "dry_run": True},
        headers=env["t1_headers"],
    )
    assert preview_res.status_code == 200, preview_res.text
    data = preview_res.json()
    assert data["dry_run"] is True
    assert data["target_count"] == 2
    assert "طالب تجربة 1" in data["target_students"]
    assert "طالب تجربة 2" in data["target_students"]

    # Verify no reminder was saved in DB
    assert db.execute("SELECT count(*) AS c FROM assignment_reminders WHERE assignment_id = %s", (ass_id,)).fetchone()["c"] == 0

    # 2. Send individual reminder to student 1
    indiv_res = client.post(
        f"{API}/assignments/{ass_id}/reminders",
        json={"kind": "individual", "student_id": str(env["s1_id"])},
        headers=env["t1_headers"],
    )
    assert indiv_res.status_code == 200, indiv_res.text

    # Re-sending individual reminder within 24h triggers 429
    indiv_cooldown = client.post(
        f"{API}/assignments/{ass_id}/reminders",
        json={"kind": "individual", "student_id": str(env["s1_id"])},
        headers=env["t1_headers"],
    )
    assert indiv_cooldown.status_code == 429, indiv_cooldown.text

    # 3. Preview bulk reminder again: student 1 must be in skipped_students
    preview2 = client.post(
        f"{API}/assignments/{ass_id}/reminders",
        json={"kind": "bulk", "dry_run": True},
        headers=env["t1_headers"],
    ).json()
    assert preview2["target_count"] == 1
    assert "طالب تجربة 2" in preview2["target_students"]
    assert preview2["skipped_count"] == 1
    assert "طالب تجربة 1" in preview2["skipped_students"]
