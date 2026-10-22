"""Tests for 018_attendance_excuses.sql database migration (Batch S3-B1).

Covers:
1. Migration idempotency (applying 018 multiple times without error).
2. Verification of chk_student_attendance_status allowing 'غياب بعذر' and rejecting invalid statuses.
3. Verification of student_attendance is_draft column and excuse_id foreign key (ON DELETE SET NULL).
4. Verification of attendance_excuses table schema, status check, and default 'pending'.
5. Verification of unique constraints (uq_attendance_excuses_active_day and uq_attendance_excuses_client_request_id).
6. Verification of set_updated_at trigger on attendance_excuses.
7. Verification of gheras_app table privileges (SELECT, INSERT, UPDATE).
8. Verification of chk_notifications_target_type allowing target_type = 'excuse'.
"""

from __future__ import annotations

import time
import uuid
from datetime import date
from pathlib import Path

import psycopg
import pytest

MIGRATION_PATH = Path(__file__).resolve().parents[2] / "db" / "postgres" / "018_attendance_excuses.sql"


def test_migration_018_idempotent(db):
    """Applying 018_attendance_excuses.sql directly to the test database must succeed idempotently twice."""
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


def test_student_attendance_status_check(db):
    """Verify that 'غياب بعذر' is accepted by chk_student_attendance_status, and junk statuses are rejected."""
    # Seed required parent rows
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة الاختبار', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]
    student_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب الاختبار') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    db.commit()

    # 1. 'غياب بعذر' must succeed
    db.execute("""
        INSERT INTO student_attendance (branch_id, student_id, date, status)
        VALUES (%s, %s, '2026-10-21', 'غياب بعذر')
    """, (branch_id, student_id))
    db.commit()

    row = db.execute(
        "SELECT status FROM student_attendance WHERE student_id = %s AND date = '2026-10-21'",
        (student_id,)
    ).fetchone()
    assert row["status"] == "غياب بعذر"

    # 2. Standard statuses must also succeed
    for idx, st in enumerate(["حاضر", "غائب", "متأخر", "مستأذن"]):
        d = f"2026-10-{idx + 1:02d}"
        db.execute("""
            INSERT INTO student_attendance (branch_id, student_id, date, status)
            VALUES (%s, %s, %s, %s)
        """, (branch_id, student_id, d, st))
    db.commit()

    # 3. Invalid status must fail CHECK constraint
    with pytest.raises(psycopg.errors.CheckViolation):
        with db.transaction():
            db.execute("""
                INSERT INTO student_attendance (branch_id, student_id, date, status)
                VALUES (%s, %s, '2026-10-29', 'مرفوض_غير_صالح')
            """, (branch_id, student_id))


def test_student_attendance_is_draft_and_excuse_id(db):
    """Verify is_draft default and excuse_id column with SET NULL on excuse deletion."""
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة المسودة', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]
    student_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب المسودة') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    guardian_user_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('guardian_test_018', 'guardian', 'hash_123') RETURNING id"
    ).fetchone()["id"]
    db.commit()

    # 1. is_draft defaults to False
    att_id = db.execute("""
        INSERT INTO student_attendance (branch_id, student_id, date, status)
        VALUES (%s, %s, '2026-10-22', 'حاضر')
        RETURNING id, is_draft
    """, (branch_id, student_id)).fetchone()
    assert att_id["is_draft"] is False

    # 2. Insert excuse and link excuse_id
    excuse_id = db.execute("""
        INSERT INTO attendance_excuses (branch_id, student_id, excuse_date, submitted_by_user_id, reason_text)
        VALUES (%s, %s, '2026-10-23', %s, 'مرض الطالب')
        RETURNING id
    """, (branch_id, student_id, guardian_user_id)).fetchone()["id"]

    db.execute("""
        INSERT INTO student_attendance (branch_id, student_id, date, status, excuse_id)
        VALUES (%s, %s, '2026-10-23', 'غياب بعذر', %s)
    """, (branch_id, student_id, excuse_id))
    db.commit()

    linked = db.execute(
        "SELECT excuse_id FROM student_attendance WHERE student_id = %s AND date = '2026-10-23'",
        (student_id,)
    ).fetchone()
    assert linked["excuse_id"] == excuse_id

    # 3. ON DELETE SET NULL on student_attendance.excuse_id
    db.execute("DELETE FROM attendance_excuses WHERE id = %s", (excuse_id,))
    db.commit()

    nullified = db.execute(
        "SELECT excuse_id FROM student_attendance WHERE student_id = %s AND date = '2026-10-23'",
        (student_id,)
    ).fetchone()
    assert nullified["excuse_id"] is None


def test_attendance_excuses_schema_and_constraints(db):
    """Verify attendance_excuses status values, default, and unique active day index."""
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة الأعذار', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]
    student_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب الأعذار') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    guardian_user_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('guardian_exc_018', 'guardian', 'hash_123') RETURNING id"
    ).fetchone()["id"]
    db.commit()

    # 1. Default status is 'pending'
    exc_row = db.execute("""
        INSERT INTO attendance_excuses (branch_id, student_id, excuse_date, submitted_by_user_id, reason_text)
        VALUES (%s, %s, '2026-10-24', %s, 'عذر طبي')
        RETURNING status
    """, (branch_id, student_id, guardian_user_id)).fetchone()
    assert exc_row["status"] == "pending"

    # 2. Duplicate active excuse on same day violates uq_attendance_excuses_active_day
    with pytest.raises(psycopg.errors.UniqueViolation):
        with db.transaction():
            db.execute("""
                INSERT INTO attendance_excuses (branch_id, student_id, excuse_date, submitted_by_user_id, reason_text)
                VALUES (%s, %s, '2026-10-24', %s, 'عذر مكرر')
            """, (branch_id, student_id, guardian_user_id))

    # 3. Status check allows 'approved', 'rejected', 'needs_info'
    for s in ["approved", "rejected", "needs_info"]:
        test_date = f"2026-11-{len(s):02d}"
        db.execute("""
            INSERT INTO attendance_excuses (branch_id, student_id, excuse_date, submitted_by_user_id, reason_text, status)
            VALUES (%s, %s, %s, %s, 'عذر اختباري', %s)
        """, (branch_id, student_id, test_date, guardian_user_id, s))
    db.commit()

    # 4. Invalid status violates chk_attendance_excuses_status
    with pytest.raises(psycopg.errors.CheckViolation):
        with db.transaction():
            db.execute("""
                INSERT INTO attendance_excuses (branch_id, student_id, excuse_date, submitted_by_user_id, reason_text, status)
                VALUES (%s, %s, '2026-11-20', %s, 'عذر بحالة خاطئة', 'invalid_status')
            """, (branch_id, student_id, guardian_user_id))


def test_attendance_excuses_client_request_id_unique(db):
    """Verify unique constraint uq_attendance_excuses_client_request_id protects replay."""
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة الأعذار', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]
    student_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب الـ Client Req') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    guardian_user_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('guardian_replay_018', 'guardian', 'hash_123') RETURNING id"
    ).fetchone()["id"]
    req_id = "client-req-unique-999"
    db.commit()

    # First insert succeeds
    db.execute("""
        INSERT INTO attendance_excuses (branch_id, student_id, excuse_date, submitted_by_user_id, reason_text, client_request_id)
        VALUES (%s, %s, '2026-10-25', %s, 'عذر تجربة الإعادة', %s)
    """, (branch_id, student_id, guardian_user_id, req_id))
    db.commit()

    # Second insert with same client_request_id violates unique constraint
    with pytest.raises(psycopg.errors.UniqueViolation):
        with db.transaction():
            db.execute("""
                INSERT INTO attendance_excuses (branch_id, student_id, excuse_date, submitted_by_user_id, reason_text, client_request_id)
                VALUES (%s, %s, '2026-10-26', %s, 'إعادة طلب مكرر', %s)
            """, (branch_id, student_id, guardian_user_id, req_id))


def test_attendance_excuses_updated_at_trigger(db):
    """Verify trg_attendance_excuses_updated_at updates updated_at on record update."""
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة الأعذار', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]
    student_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب التريجر') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    guardian_user_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('guardian_trg_018', 'guardian', 'hash_123') RETURNING id"
    ).fetchone()["id"]
    db.commit()

    row = db.execute("""
        INSERT INTO attendance_excuses (branch_id, student_id, excuse_date, submitted_by_user_id, reason_text)
        VALUES (%s, %s, '2026-10-27', %s, 'عذر تريجر التحديث')
        RETURNING id, created_at, updated_at
    """, (branch_id, student_id, guardian_user_id)).fetchone()
    excuse_id = row["id"]
    orig_updated_at = row["updated_at"]
    db.commit()  # now() is frozen per transaction; the UPDATE must run in a new one

    # Sleep slightly so timestamps differ
    time.sleep(0.05)

    db.execute("""
        UPDATE attendance_excuses
        SET supervisor_notes = 'ملاحظة محدثة'
        WHERE id = %s
    """, (excuse_id,))
    db.commit()

    new_row = db.execute("SELECT updated_at FROM attendance_excuses WHERE id = %s", (excuse_id,)).fetchone()
    assert new_row["updated_at"] > orig_updated_at


def test_attendance_excuses_grants(db):
    """Verify gheras_app has SELECT, INSERT, UPDATE privileges on attendance_excuses."""
    for priv in ["SELECT", "INSERT", "UPDATE"]:
        has_priv = db.execute(
            "SELECT has_table_privilege('gheras_app', 'attendance_excuses', %s) AS ok",
            (priv,)
        ).fetchone()["ok"]
        assert has_priv is True, f"gheras_app missing {priv} on attendance_excuses"


def test_notifications_target_type_excuse(db):
    """Verify that notifications table allows target_type = 'excuse'."""
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    user_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('guardian_notif_018', 'guardian', 'hash_123') RETURNING id"
    ).fetchone()["id"]

    db.execute("""
        INSERT INTO notifications (branch_id, user_id, kind, title, body, target_type)
        VALUES (%s, %s, 'attendance', 'إشعار عذر', 'تم قبول العذر', 'excuse')
    """, (branch_id, user_id))
    db.commit()

    notif = db.execute(
        "SELECT target_type FROM notifications WHERE user_id = %s AND title = 'إشعار عذر'",
        (user_id,)
    ).fetchone()
    assert notif["target_type"] == "excuse"
