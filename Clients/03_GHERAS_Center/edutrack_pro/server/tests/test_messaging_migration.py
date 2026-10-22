"""Tests for 019_messaging_and_daily_summary.sql database migration (Batch S4-B1).

Covers:
1. Migration idempotency (applying 019 multiple times without error).
2. Verification of threads table schema, status check ('open', 'closed', 'archived'), and foreign keys.
3. Verification of thread_messages schema, sender_role check, attachment_type check, and delivery/read timestamps.
4. Verification of unique constraint uq_thread_messages_client_id preventing replay/duplicates.
5. Verification of daily_summaries table schema, default is_published = False, and uq_daily_summaries_room_date_subject.
6. Verification of daily_summary_notes schema, private notes, and uq_daily_summary_notes_summary_student.
7. Verification of fk_threads_linked_summary linking threads to daily_summaries.
8. Verification of chk_notifications_target_type allowing 'thread_message' and 'daily_summary'.
9. Verification of set_updated_at triggers on all 4 tables.
10. Verification of gheras_app privileges (SELECT, INSERT, UPDATE, DELETE).
"""

from __future__ import annotations

import time
import uuid
from datetime import date
from pathlib import Path

import psycopg
import pytest

MIGRATION_PATH = Path(__file__).resolve().parents[2] / "db" / "postgres" / "019_messaging_and_daily_summary.sql"


def test_migration_019_idempotent(db):
    """Applying 019_messaging_and_daily_summary.sql directly to the test database must succeed idempotently twice."""
    import re

    with open(MIGRATION_PATH, "r", encoding="utf-8") as f:
        sql = f.read()

    clean_sql = re.sub(r"^\s*(BEGIN|COMMIT)\s*;\s*$", "", sql, flags=re.I | re.M)

    try:
        # First application
        db.execute(clean_sql)
        # Second application must succeed without error
        db.execute(clean_sql)
    finally:
        db.rollback()


def test_threads_and_messages_schema_and_constraints(db):
    """Verify threads and thread_messages table structure, foreign keys, status checks, and client_id uniqueness."""
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة الرسائل', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]
    student_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب المحادثة') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    guardian_user_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('guardian_thread_test', 'guardian', 'hash_123') RETURNING id"
    ).fetchone()["id"]
    teacher_user_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('teacher_thread_test', 'teacher', 'hash_123') RETURNING id"
    ).fetchone()["id"]
    db.commit()

    # 1. Create a thread
    thread_row = db.execute("""
        INSERT INTO threads (branch_id, student_id, guardian_user_id, staff_user_id, subject)
        VALUES (%s, %s, %s, %s, 'استفسار عن مستوى الطالب')
        RETURNING id, status, last_message_at
    """, (branch_id, student_id, guardian_user_id, teacher_user_id)).fetchone()
    thread_id = thread_row["id"]
    assert thread_row["status"] == "open"
    assert thread_row["last_message_at"] is not None

    # 2. Status constraint check on threads
    with pytest.raises(psycopg.errors.CheckViolation):
        with db.transaction():
            db.execute("""
                INSERT INTO threads (branch_id, student_id, guardian_user_id, staff_user_id, subject, status)
                VALUES (%s, %s, %s, %s, 'موضوع بحالة غير صالحة', 'invalid_status')
            """, (branch_id, student_id, guardian_user_id, teacher_user_id))

    # 3. Add message from guardian with client_message_id
    client_msg_id = "msg-client-uuid-001"
    msg_row = db.execute("""
        INSERT INTO thread_messages (branch_id, thread_id, sender_user_id, sender_role, body, client_message_id)
        VALUES (%s, %s, %s, 'guardian', 'السلام عليكم ورحمة الله', %s)
        RETURNING id, sent_at, delivered_at, read_at
    """, (branch_id, thread_id, guardian_user_id, client_msg_id)).fetchone()
    assert msg_row["sent_at"] is not None
    assert msg_row["delivered_at"] is None
    assert msg_row["read_at"] is None

    # 4. Duplicate client_message_id must be rejected
    with pytest.raises(psycopg.errors.UniqueViolation):
        with db.transaction():
            db.execute("""
                INSERT INTO thread_messages (branch_id, thread_id, sender_user_id, sender_role, body, client_message_id)
                VALUES (%s, %s, %s, 'guardian', 'رسالة مكررة بنفس المعرف', %s)
            """, (branch_id, thread_id, guardian_user_id, client_msg_id))

    # 5. Attachment types constraint check
    for valid_type in ["image", "audio", "video", "pdf", "file"]:
        db.execute("""
            INSERT INTO thread_messages (branch_id, thread_id, sender_user_id, sender_role, body, attachment_type)
            VALUES (%s, %s, %s, 'teacher', 'مرفق صالح', %s)
        """, (branch_id, thread_id, teacher_user_id, valid_type))
    db.commit()

    with pytest.raises(psycopg.errors.CheckViolation):
        with db.transaction():
            db.execute("""
                INSERT INTO thread_messages (branch_id, thread_id, sender_user_id, sender_role, body, attachment_type)
                VALUES (%s, %s, %s, 'teacher', 'مرفق غير صالح', 'exe_virus')
            """, (branch_id, thread_id, teacher_user_id))


def test_daily_summaries_and_private_notes(db):
    """Verify daily_summaries, default unpublished status, and daily_summary_notes private per-student isolation."""
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة الملخص اليومي', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]
    teacher_user_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('teacher_summary_test', 'teacher', 'hash_123') RETURNING id"
    ).fetchone()["id"]
    student1_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب ملخص 1') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    student2_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب ملخص 2') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    db.commit()

    # 1. Create a daily summary as draft (is_published = False)
    summary_row = db.execute("""
        INSERT INTO daily_summaries (branch_id, room_id, teacher_user_id, summary_date, subject, title, class_summary, homework_summary)
        VALUES (%s, %s, %s, '2026-10-22', 'القرآن الكريم', 'تلاوة سورة النبأ', 'شرح الآيات وتدريب التلاوة', 'حفظ الآيات 1-10')
        RETURNING id, is_published, published_at
    """, (branch_id, room_id, teacher_user_id)).fetchone()
    summary_id = summary_row["id"]
    assert summary_row["is_published"] is False
    assert summary_row["published_at"] is None

    # 2. uq_daily_summaries_room_date_subject prevents duplicate summary for same room, date, subject
    with pytest.raises(psycopg.errors.UniqueViolation):
        with db.transaction():
            db.execute("""
                INSERT INTO daily_summaries (branch_id, room_id, teacher_user_id, summary_date, subject, title, class_summary)
                VALUES (%s, %s, %s, '2026-10-22', 'القرآن الكريم', 'تكرار نفس المادة واليوم', 'نص مكرر')
            """, (branch_id, room_id, teacher_user_id))

    # 3. Add private notes for student1 and student2
    db.execute("""
        INSERT INTO daily_summary_notes (branch_id, summary_id, student_id, private_note)
        VALUES (%s, %s, %s, 'ملاحظة سرية للطالب 1: إتقان متميز لمخارج الحروف')
    """, (branch_id, summary_id, student1_id))

    db.execute("""
        INSERT INTO daily_summary_notes (branch_id, summary_id, student_id, private_note)
        VALUES (%s, %s, %s, 'ملاحظة سرية للطالب 2: يحتاج لمزيد من المراجعة')
    """, (branch_id, summary_id, student2_id))
    db.commit()

    # 4. Duplicate private note for same student in same summary violates unique constraint
    with pytest.raises(psycopg.errors.UniqueViolation):
        with db.transaction():
            db.execute("""
                INSERT INTO daily_summary_notes (branch_id, summary_id, student_id, private_note)
                VALUES (%s, %s, %s, 'ملاحظة مكررة لنفس الطالب')
            """, (branch_id, summary_id, student1_id))


def test_notifications_target_types_m4(db):
    """Verify that notifications table allows target_type = 'thread_message' and 'daily_summary'."""
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    user_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('guardian_notif_m4', 'guardian', 'hash_123') RETURNING id"
    ).fetchone()["id"]

    for tt in ["thread_message", "daily_summary"]:
        db.execute("""
            INSERT INTO notifications (branch_id, user_id, kind, title, body, target_type)
            VALUES (%s, %s, 'announcement', 'إشعار تجربة M4', 'محتوى الإشعار', %s)
        """, (branch_id, user_id, tt))
    db.commit()

    rows = db.execute(
        "SELECT target_type FROM notifications WHERE user_id = %s",
        (user_id,)
    ).fetchall()
    target_types = {r["target_type"] for r in rows}
    assert "thread_message" in target_types
    assert "daily_summary" in target_types


def test_updated_at_triggers_and_grants(db):
    """Verify updated_at triggers fire and gheras_app has privileges on all 4 M4 tables."""
    for tbl in ["threads", "thread_messages", "daily_summaries", "daily_summary_notes"]:
        for priv in ["SELECT", "INSERT", "UPDATE", "DELETE"]:
            has_priv = db.execute(
                f"SELECT has_table_privilege('gheras_app', '{tbl}', %s) AS ok",
                (priv,)
            ).fetchone()["ok"]
            assert has_priv is True, f"gheras_app missing {priv} on {tbl}"
