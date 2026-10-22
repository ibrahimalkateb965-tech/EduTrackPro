"""Tests for Daily Classroom Summaries and Private Student Notes (Batch S4-B3).

Covers:
1. Teacher creates draft summary with private notes.
2. Draft summary hidden from guardian (draft-isolation invariant).
3. Publishing summary broadcasts notifications and unblocks guardian visibility.
4. Strict anti-leak: Guardian receives only their child's private note, never others.
5. Classroom authorization: Foreign teacher cannot modify summary for foreign room (403).
"""

from __future__ import annotations

import uuid
import pytest
from starlette.testclient import TestClient

from edutrack_api.auth import issue_token
from edutrack_api.config import get_settings
from edutrack_api.main import create_app


@pytest.fixture
def client(db):
    app = create_app()
    with TestClient(app) as c:
        yield c


def _auth_header(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _make_token(user_id: str | uuid.UUID, role: str) -> str:
    return issue_token({"id": str(user_id), "role": role, "token_version": 1}, get_settings())


def _seed_daily_summary_fixture(db):
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة ملخص الاختبار', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]

    # Teacher 1 (assigned to room)
    t1_id = db.execute(
        "INSERT INTO users (username, role, password_hash, room_id) VALUES ('teacher_sum_1', 'teacher', 'hash', %s) RETURNING id",
        (room_id,)
    ).fetchone()["id"]
    db.execute("""
        INSERT INTO schedules (branch_id, room_id, teacher_user_id, day, start_time, end_time, subject, group_name)
        VALUES (%s, %s, %s, 'الاثنين', '08:00', '10:00', 'القرآن', 'الصباح')
    """, (branch_id, room_id, t1_id))

    # Teacher 2 (unassigned)
    t2_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('teacher_sum_foreign', 'teacher', 'hash') RETURNING id"
    ).fetchone()["id"]

    # Guardian 1 + Student 1
    g1_rec = db.execute("INSERT INTO guardians (branch_id, name, phone) VALUES (%s, 'ولي أمر 1', '0555000001') RETURNING id", (branch_id,)).fetchone()["id"]
    g1_user = db.execute(
        "INSERT INTO users (username, role, password_hash, guardian_id) VALUES ('user_g1_sum', 'guardian', 'hash', %s) RETURNING id",
        (g1_rec,)
    ).fetchone()["id"]
    s1_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب 1 ملخص') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    db.execute("INSERT INTO student_guardians (student_id, guardian_id) VALUES (%s, %s)", (s1_id, g1_rec))

    # Guardian 2 + Student 2
    g2_rec = db.execute("INSERT INTO guardians (branch_id, name, phone) VALUES (%s, 'ولي أمر 2', '0555000002') RETURNING id", (branch_id,)).fetchone()["id"]
    g2_user = db.execute(
        "INSERT INTO users (username, role, password_hash, guardian_id) VALUES ('user_g2_sum', 'guardian', 'hash', %s) RETURNING id",
        (g2_rec,)
    ).fetchone()["id"]
    s2_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب 2 ملخص') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    db.execute("INSERT INTO student_guardians (student_id, guardian_id) VALUES (%s, %s)", (s2_id, g2_rec))

    db.commit()

    return {
        "branch_id": str(branch_id),
        "room_id": str(room_id),
        "teacher_id": str(t1_id),
        "foreign_teacher_id": str(t2_id),
        "student1_id": str(s1_id),
        "guardian1_user_id": str(g1_user),
        "student2_id": str(s2_id),
        "guardian2_user_id": str(g2_user),
    }


def test_daily_summary_workflow_and_private_notes_isolation(client, db):
    """Test full workflow from draft creation to publication, verifying private note confidentiality."""
    data = _seed_daily_summary_fixture(db)
    t_token = _make_token(data["teacher_id"], "teacher")
    g1_token = _make_token(data["guardian1_user_id"], "guardian")
    g2_token = _make_token(data["guardian2_user_id"], "guardian")

    # 1. Teacher creates draft summary with private notes for student 1 and student 2
    draft_res = client.post(
        "/api/v1/daily-summaries",
        json={
            "room_id": data["room_id"],
            "summary_date": "2026-10-22",
            "subject": "اللغة العربية",
            "title": "درس القراءة: رحلة إلى الحديقة",
            "class_summary": "قراءة النص واستخراج الكلمات التي تبدأ باللام الشمسية والقمرية.",
            "homework_summary": "نسخ الفقرة الأولى في دفتر الواجبات.",
            "is_published": False,
            "student_notes": [
                {"student_id": data["student1_id"], "private_note": "ملاحظة سرية خاصة بسارة: ممتازة جدا"},
                {"student_id": data["student2_id"], "private_note": "ملاحظة سرية خاصة بخالد: يحتاج متابعة"}
            ]
        },
        headers=_auth_header(t_token)
    )
    assert draft_res.status_code == 200
    summary_data = draft_res.json()
    summary_id = summary_data["id"]
    assert summary_data["is_published"] is False
    assert len(summary_data["student_notes"]) == 2

    # 2. Guardian 1 queries daily summaries while draft -> MUST BE EMPTY (draft hidden)
    g1_draft_check = client.get(
        f"/api/v1/me/daily-summaries?student_id={data['student1_id']}&summary_date=2026-10-22",
        headers=_auth_header(g1_token)
    )
    assert g1_draft_check.status_code == 200
    assert len(g1_draft_check.json()) == 0

    # 3. Teacher publishes the summary
    pub_res = client.post(
        f"/api/v1/daily-summaries/{summary_id}/publish",
        headers=_auth_header(t_token)
    )
    assert pub_res.status_code == 200
    assert pub_res.json()["is_published"] is True

    # 4. Check notifications were created for both guardians with gheras:// action_url (B1)
    notifs = db.execute(
        "SELECT user_id, action_url FROM notifications WHERE target_id = %s AND target_type = 'daily_summary'",
        (summary_id,)
    ).fetchall()
    notified_users = {str(n["user_id"]) for n in notifs}
    assert data["guardian1_user_id"] in notified_users
    assert data["guardian2_user_id"] in notified_users
    for n in notifs:
        assert n["action_url"] == f"gheras://daily-summaries/{summary_id}"

    # 5. Guardian 1 queries published summary -> sees class summary + ONLY student 1's note
    g1_res = client.get(
        f"/api/v1/me/daily-summaries?student_id={data['student1_id']}&summary_date=2026-10-22",
        headers=_auth_header(g1_token)
    )
    assert g1_res.status_code == 200
    g1_list = g1_res.json()
    assert len(g1_list) == 1
    assert g1_list[0]["title"] == "درس القراءة: رحلة إلى الحديقة"
    assert g1_list[0]["student_private_note"] == "ملاحظة سرية خاصة بسارة: ممتازة جدا"

    # 6. Guardian 2 queries published summary -> sees class summary + ONLY student 2's note
    g2_res = client.get(
        f"/api/v1/me/daily-summaries?student_id={data['student2_id']}&summary_date=2026-10-22",
        headers=_auth_header(g2_token)
    )
    assert g2_res.status_code == 200
    g2_list = g2_res.json()
    assert len(g2_list) == 1
    assert g2_list[0]["title"] == "درس القراءة: رحلة إلى الحديقة"
    assert g2_list[0]["student_private_note"] == "ملاحظة سرية خاصة بخالد: يحتاج متابعة"

    # 7. B6: Teacher updates summary, removing student 2's note -> student 2's note must be deleted
    update_res = client.post(
        "/api/v1/daily-summaries",
        json={
            "room_id": data["room_id"],
            "summary_date": "2026-10-22",
            "subject": "اللغة العربية",
            "title": "درس القراءة: رحلة إلى الحديقة (معدل)",
            "class_summary": "تعديل الشرح الصفي.",
            "is_published": True,
            "student_notes": [
                {"student_id": data["student1_id"], "private_note": "ملاحظة سارة فقط"}
            ]
        },
        headers=_auth_header(t_token)
    )
    assert update_res.status_code == 200
    updated_data = update_res.json()
    assert len(updated_data["student_notes"]) == 1
    assert updated_data["student_notes"][0]["student_id"] == data["student1_id"]

    # Student 2 guardian now sees summary without private note
    g2_after = client.get(
        f"/api/v1/me/daily-summaries?student_id={data['student2_id']}&summary_date=2026-10-22",
        headers=_auth_header(g2_token)
    )
    assert g2_after.status_code == 200
    assert g2_after.json()[0]["student_private_note"] is None

    # 8. M4: Verify that updating already-published summary did not duplicate notifications
    notifs_total = db.execute(
        "SELECT COUNT(*) AS cnt FROM notifications WHERE target_id = %s AND target_type = 'daily_summary'",
        (summary_id,)
    ).fetchone()["cnt"]
    assert notifs_total == 2


def test_supervisor_access_and_teacher_room_scoping(client, db):
    """Test B2 (supervisor scope bypass) and M1 (teacher room restriction)."""
    data = _seed_daily_summary_fixture(db)

    # Seed supervisor user
    super_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('super_test_m4', 'supervisor', 'hash') RETURNING id"
    ).fetchone()["id"]
    db.commit()

    super_token = _make_token(super_id, "supervisor")
    t_token = _make_token(data["teacher_id"], "teacher")

    # B2: Supervisor can list summaries without 403 MSG_NOT_MOBILE
    sup_list = client.get("/api/v1/daily-summaries", headers=_auth_header(super_token))
    assert sup_list.status_code == 200

    # M1: Teacher calling list without room_id succeeds and scopes to their rooms
    t_list = client.get("/api/v1/daily-summaries", headers=_auth_header(t_token))
    assert t_list.status_code == 200


def test_foreign_teacher_cannot_upsert_summary(client, db):
    """Teacher not assigned to classroom cannot post summary (403)."""
    data = _seed_daily_summary_fixture(db)
    foreign_t_token = _make_token(data["foreign_teacher_id"], "teacher")

    res = client.post(
        "/api/v1/daily-summaries",
        json={
            "room_id": data["room_id"],
            "summary_date": "2026-10-23",
            "subject": "الرياضيات",
            "title": "محاولة غير مصرحة",
            "class_summary": "نص التجربة",
            "is_published": False
        },
        headers=_auth_header(foreign_t_token)
    )
    assert res.status_code == 403
