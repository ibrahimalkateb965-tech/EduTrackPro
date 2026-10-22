"""Tests for Messaging & Threads routers and repository (Batch S4-B2).

Covers:
1. Thread creation by guardian and teacher.
2. Thread listing filtered by role, student, and unread counts.
3. Message sending with client_message_id idempotency.
4. Marking messages delivered and read with real timestamps.
5. Notification emission on thread creation and new message.
6. Authorization and scope guards (403 for foreign student/thread).
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


def _seed_m4_fixture(db):
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة المراسلة الاختبارية', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]
    
    # Guardian User & Record
    g_id = db.execute("INSERT INTO guardians (branch_id, name, phone) VALUES (%s, 'ولي أمر محادثة', '0555112233') RETURNING id", (branch_id,)).fetchone()["id"]
    guardian_user_id = db.execute(
        "INSERT INTO users (username, role, password_hash, guardian_id) VALUES ('user_g_thread', 'guardian', 'hash', %s) RETURNING id",
        (g_id,)
    ).fetchone()["id"]

    # Student linked to Guardian
    student_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب المحادثات') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    db.execute("INSERT INTO student_guardians (student_id, guardian_id) VALUES (%s, %s)", (student_id, g_id))

    # Teacher User assigned to room
    teacher_user_id = db.execute(
        "INSERT INTO users (username, role, password_hash, room_id) VALUES ('user_t_thread', 'teacher', 'hash', %s) RETURNING id",
        (room_id,)
    ).fetchone()["id"]
    db.execute("""
        INSERT INTO schedules (branch_id, room_id, teacher_user_id, day, start_time, end_time, subject, group_name)
        VALUES (%s, %s, %s, 'الأحد', '08:00', '10:00', 'القرآن', 'الصباح')
    """, (branch_id, room_id, teacher_user_id))

    # Foreign Student & Guardian
    foreign_g_id = db.execute("INSERT INTO guardians (branch_id, name, phone) VALUES (%s, 'ولي أمر أجنبي', '0555998877') RETURNING id", (branch_id,)).fetchone()["id"]
    foreign_g_user = db.execute(
        "INSERT INTO users (username, role, password_hash, guardian_id) VALUES ('user_foreign_g', 'guardian', 'hash', %s) RETURNING id",
        (foreign_g_id,)
    ).fetchone()["id"]
    foreign_student = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب أجنبي') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    db.execute("INSERT INTO student_guardians (student_id, guardian_id) VALUES (%s, %s)", (foreign_student, foreign_g_id))

    db.commit()

    return {
        "branch_id": str(branch_id),
        "room_id": str(room_id),
        "student_id": str(student_id),
        "guardian_user_id": str(guardian_user_id),
        "teacher_user_id": str(teacher_user_id),
        "foreign_student_id": str(foreign_student),
        "foreign_guardian_user_id": str(foreign_g_user),
    }


def test_thread_creation_and_messaging_flow(client, db):
    """Test full cycle: thread creation, message send, delivery confirmation, and read confirmation."""
    data = _seed_m4_fixture(db)
    g_token = _make_token(data["guardian_user_id"], "guardian")
    t_token = _make_token(data["teacher_user_id"], "teacher")

    # 1. Guardian creates thread with initial message
    create_res = client.post(
        "/api/v1/threads",
        json={
            "student_id": data["student_id"],
            "staff_user_id": data["teacher_user_id"],
            "subject": "مستوى الطالب في القراءة",
            "first_message": "السلام عليكم يا معلمة، أود الاستفسار عن تقدم الطالب في القراءة.",
            "client_message_id": "client-msg-001"
        },
        headers=_auth_header(g_token)
    )
    assert create_res.status_code == 200
    thread_data = create_res.json()
    thread_id = thread_data["id"]
    assert thread_data["subject"] == "مستوى الطالب في القراءة"
    assert thread_data["student_name"] == "طالب المحادثات"

    # 2. Verify notification sent to teacher with gheras:// action_url (B1)
    notif = db.execute(
        "SELECT * FROM notifications WHERE user_id = %s AND target_type = 'thread_message'",
        (data["teacher_user_id"],)
    ).fetchone()
    assert notif is not None
    assert str(notif["target_id"]) == thread_id
    assert notif["action_url"] == f"gheras://threads/{thread_id}"

    # 3. Teacher lists messages in thread
    msgs_res = client.get(f"/api/v1/threads/{thread_id}/messages", headers=_auth_header(t_token))
    assert msgs_res.status_code == 200
    msgs = msgs_res.json()
    assert len(msgs) == 1
    assert msgs[0]["body"] == "السلام عليكم يا معلمة، أود الاستفسار عن تقدم الطالب في القراءة."
    assert msgs[0]["delivered_at"] is None

    # 4. Teacher marks delivered
    deliv_res = client.post(f"/api/v1/threads/{thread_id}/messages/delivered", headers=_auth_header(t_token))
    assert deliv_res.status_code == 200
    assert deliv_res.json()["delivered_count"] == 1

    # 5. Teacher replies to thread
    reply_res = client.post(
        f"/api/v1/threads/{thread_id}/messages",
        json={
            "body": "وعليكم السلام ورحمة الله، الطالب مستواه ممتاز والحمد لله.",
            "client_message_id": "client-msg-reply-001"
        },
        headers=_auth_header(t_token)
    )
    assert reply_res.status_code == 200
    assert reply_res.json()["body"] == "وعليكم السلام ورحمة الله، الطالب مستواه ممتاز والحمد لله."

    # Check notification count for guardian
    notifs_before = db.execute(
        "SELECT COUNT(*) AS cnt FROM notifications WHERE user_id = %s",
        (data["guardian_user_id"],)
    ).fetchone()["cnt"]

    # 6. Replaying the reply with same client_message_id must return existing message idempotently (M2)
    replay_res = client.post(
        f"/api/v1/threads/{thread_id}/messages",
        json={
            "body": "وعليكم السلام ورحمة الله، الطالب مستواه ممتاز والحمد لله.",
            "client_message_id": "client-msg-reply-001"
        },
        headers=_auth_header(t_token)
    )
    assert replay_res.status_code == 200
    assert replay_res.json()["id"] == reply_res.json()["id"]

    # M2: Verify no duplicate notification was emitted on replay
    notifs_after = db.execute(
        "SELECT COUNT(*) AS cnt FROM notifications WHERE user_id = %s",
        (data["guardian_user_id"],)
    ).fetchone()["cnt"]
    assert notifs_after == notifs_before

    # 7. Guardian marks read
    read_res = client.post(f"/api/v1/threads/{thread_id}/messages/read", headers=_auth_header(g_token))
    assert read_res.status_code == 200
    assert read_res.json()["read_count"] == 1

    # 8. Check threads list reflects zero unread for guardian
    list_res = client.get("/api/v1/threads", headers=_auth_header(g_token))
    assert list_res.status_code == 200
    threads = list_res.json()
    assert len(threads) == 1
    assert threads[0]["unread_count"] == 0


def test_thread_authorization_guards(client, db):
    """Foreign guardian cannot create thread for someone else's child, nor access another thread."""
    data = _seed_m4_fixture(db)
    g_token = _make_token(data["guardian_user_id"], "guardian")
    foreign_token = _make_token(data["foreign_guardian_user_id"], "guardian")

    # 1. Foreign guardian tries to create thread for data['student_id'] -> 403
    forbidden_res = client.post(
        "/api/v1/threads",
        json={
            "student_id": data["student_id"],
            "subject": "محاولة اختراق",
            "first_message": "هل أستطيع رؤية هذا الطالب؟"
        },
        headers=_auth_header(foreign_token)
    )
    assert forbidden_res.status_code == 403

    # 2. Legitimate guardian creates thread
    t_res = client.post(
        "/api/v1/threads",
        json={
            "student_id": data["student_id"],
            "subject": "محادثة خاصة",
            "first_message": "مرحبا"
        },
        headers=_auth_header(g_token)
    )
    thread_id = t_res.json()["id"]

    # 3. Foreign guardian tries to read messages of this thread -> 403 (B3)
    foreign_get = client.get(f"/api/v1/threads/{thread_id}/messages", headers=_auth_header(foreign_token))
    assert foreign_get.status_code == 403

    # 4. Foreign guardian tries to mark delivered or read -> 403 (B3)
    foreign_deliv = client.post(f"/api/v1/threads/{thread_id}/messages/delivered", headers=_auth_header(foreign_token))
    assert foreign_deliv.status_code == 403
    foreign_read = client.post(f"/api/v1/threads/{thread_id}/messages/read", headers=_auth_header(foreign_token))
    assert foreign_read.status_code == 403


def test_thread_validation_and_linkage_guards(client, db):
    """Test B4, B5, and M5 validation rules (attachments, links, participant validation)."""
    data = _seed_m4_fixture(db)
    g_token = _make_token(data["guardian_user_id"], "guardian")

    # M5: Attachment too large (>25MB) -> 422
    too_large_res = client.post(
        "/api/v1/threads",
        json={
            "student_id": data["student_id"],
            "subject": "ملف كبير",
            "first_message": "مرفق كبير",
            "attachment_storage_key": "keys/large.bin",
            "attachment_type": "file",
            "attachment_size_bytes": 30 * 1024 * 1024,
        },
        headers=_auth_header(g_token)
    )
    assert too_large_res.status_code == 422

    # M5: Invalid attachment type -> 422
    invalid_type_res = client.post(
        "/api/v1/threads",
        json={
            "student_id": data["student_id"],
            "subject": "نوع غير صالح",
            "first_message": "مرفق تنفيذي",
            "attachment_storage_key": "keys/bad.exe",
            "attachment_type": "executable",
            "attachment_size_bytes": 1024,
        },
        headers=_auth_header(g_token)
    )
    assert invalid_type_res.status_code == 422

    # B4: Guardian cannot target another guardian -> 422
    g_to_g = client.post(
        "/api/v1/threads",
        json={
            "student_id": data["student_id"],
            "staff_user_id": data["foreign_guardian_user_id"],
            "subject": "مراسلة ولي آخر",
            "first_message": "غير مسموح",
        },
        headers=_auth_header(g_token)
    )
    assert g_to_g.status_code == 422

    # B5: Non-existent linked summary -> 404
    bad_summary = client.post(
        "/api/v1/threads",
        json={
            "student_id": data["student_id"],
            "subject": "ملخص وهمي",
            "linked_summary_id": str(uuid.uuid4()),
        },
        headers=_auth_header(g_token)
    )
    assert bad_summary.status_code == 404

    # M6: Message limit bounds check (limit < 1 or limit > 500 -> 422)
    bad_limit_low = client.get(
        f"/api/v1/threads/{uuid.uuid4()}/messages?limit=-1",
        headers=_auth_header(g_token)
    )
    assert bad_limit_low.status_code == 422

    bad_limit_high = client.get(
        f"/api/v1/threads/{uuid.uuid4()}/messages?limit=1000",
        headers=_auth_header(g_token)
    )
    assert bad_limit_high.status_code == 422


def test_thread_creation_idempotency_m2(client, db):
    """M2: Retrying POST /threads with identical client_message_id returns existing thread without duplicate notifications."""
    data = _seed_m4_fixture(db)
    g_token = _make_token(data["guardian_user_id"], "guardian")

    payload = {
        "student_id": data["student_id"],
        "subject": "موضوع مكرر",
        "first_message": "رسالة البداية المكررة",
        "client_message_id": "thread-client-uniq-999"
    }

    # First attempt
    res1 = client.post("/api/v1/threads", json=payload, headers=_auth_header(g_token))
    assert res1.status_code == 200
    t1 = res1.json()

    # Second attempt with same client_message_id
    res2 = client.post("/api/v1/threads", json=payload, headers=_auth_header(g_token))
    assert res2.status_code == 200
    t2 = res2.json()

    assert t1["id"] == t2["id"]

    # Verify only ONE notification was emitted
    notifs = db.execute(
        "SELECT COUNT(*) AS cnt FROM notifications WHERE target_id = %s",
        (t1["id"],)
    ).fetchone()["cnt"]
    assert notifs == 1
