"""Tests for 020_monthly_student_reports.sql database migration (Batch S5-B1).

Covers:
1. Migration idempotency (applying 020 multiple times without error).
2. Verification of monthly_student_reports table schema, defaults, and foreign keys.
3. Verification of status check ('draft', 'pending_approval', 'needs_revision', 'approved', 'published').
4. Verification of month format regex check (YYYY-MM).
5. Verification of unique constraint uq_monthly_student_reports_active preventing duplicate active reports per student/month.
6. Verification of soft-delete behavior (a new active report is permitted if previous is soft-deleted).
7. Verification of chk_notifications_target_type allowing 'monthly_report'.
8. Verification of set_updated_at trigger.
"""

from __future__ import annotations

import re
import time
import uuid
from pathlib import Path

import psycopg
import pytest

MIGRATION_PATH = Path(__file__).resolve().parents[2] / "db" / "postgres" / "020_monthly_student_reports.sql"


def test_migration_020_idempotent(db):
    """Applying 020_monthly_student_reports.sql directly must succeed idempotently twice."""
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


def test_monthly_student_reports_schema_and_defaults(db):
    """Verify table creation, defaults, and foreign keys."""
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة التقارير', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]
    student_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب التقرير الشهري') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    teacher_user_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('teacher_m5_test', 'teacher', 'hash_123') RETURNING id"
    ).fetchone()["id"]
    db.commit()

    # 1. Insert minimal report
    row = db.execute("""
        INSERT INTO monthly_student_reports (branch_id, student_id, room_id, month, teacher_user_id)
        VALUES (%s, %s, %s, '2026-10', %s)
        RETURNING id, status, attendance_stats, subject_ratings, created_at, updated_at
    """, (branch_id, student_id, room_id, teacher_user_id)).fetchone()

    assert row["id"] is not None
    assert row["status"] == "draft"
    assert row["attendance_stats"]["total_days"] == 0
    assert row["attendance_stats"]["attendance_pct"] == 0
    assert row["subject_ratings"] == []
    assert row["created_at"] is not None
    assert row["updated_at"] is not None


def test_monthly_student_reports_status_check(db):
    """Verify valid statuses and rejection of invalid statuses."""
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة الحالات', 'المساء') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]
    student_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب الحالات') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    teacher_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('teacher_m5_status', 'teacher', 'hash_123') RETURNING id"
    ).fetchone()["id"]
    db.commit()

    valid_statuses = ["draft", "pending_approval", "needs_revision", "approved", "published"]
    for idx, st in enumerate(valid_statuses, start=1):
        month = f"2026-{idx:02d}"
        res = db.execute("""
            INSERT INTO monthly_student_reports (branch_id, student_id, room_id, month, teacher_user_id, status)
            VALUES (%s, %s, %s, %s, %s, %s)
            RETURNING status
        """, (branch_id, student_id, room_id, month, teacher_id, st)).fetchone()
        assert res["status"] == st

    # Invalid status must be rejected
    with pytest.raises(psycopg.errors.CheckViolation):
        with db.transaction():
            db.execute("""
                INSERT INTO monthly_student_reports (branch_id, student_id, room_id, month, teacher_user_id, status)
                VALUES (%s, %s, %s, '2026-06', %s, 'rejected')
            """, (branch_id, student_id, room_id, teacher_id))


def test_monthly_student_reports_month_format_check(db):
    """Verify month regex format check YYYY-MM."""
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة التواريخ', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]
    student_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب التواريخ') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    teacher_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('teacher_m5_month', 'teacher', 'hash_123') RETURNING id"
    ).fetchone()["id"]
    db.commit()

    invalid_months = ["2026-13", "2026-00", "2026-1", "26-10", "2026/10", "october"]
    for bad_month in invalid_months:
        with pytest.raises(psycopg.errors.CheckViolation):
            with db.transaction():
                db.execute("""
                    INSERT INTO monthly_student_reports (branch_id, student_id, room_id, month, teacher_user_id)
                    VALUES (%s, %s, %s, %s, %s)
                """, (branch_id, student_id, room_id, bad_month, teacher_id))


def test_monthly_student_reports_unique_active_constraint(db):
    """uq_monthly_student_reports_active prevents two active reports for the same student & month."""
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة التفرد', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]
    student_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب التفرد') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    teacher_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('teacher_m5_unique', 'teacher', 'hash_123') RETURNING id"
    ).fetchone()["id"]
    db.commit()

    # Insert first active report
    report1_id = db.execute("""
        INSERT INTO monthly_student_reports (branch_id, student_id, room_id, month, teacher_user_id)
        VALUES (%s, %s, %s, '2026-10', %s)
        RETURNING id
    """, (branch_id, student_id, room_id, teacher_id)).fetchone()["id"]

    # Duplicate active report for same month must fail
    with pytest.raises(psycopg.errors.UniqueViolation):
        with db.transaction():
            db.execute("""
                INSERT INTO monthly_student_reports (branch_id, student_id, room_id, month, teacher_user_id)
                VALUES (%s, %s, %s, '2026-10', %s)
            """, (branch_id, student_id, room_id, teacher_id))

    # Soft-delete the first report
    db.execute("UPDATE monthly_student_reports SET deleted_at = now() WHERE id = %s", (report1_id,))
    db.commit()

    # Now a new active report for 2026-10 must succeed
    report2_id = db.execute("""
        INSERT INTO monthly_student_reports (branch_id, student_id, room_id, month, teacher_user_id)
        VALUES (%s, %s, %s, '2026-10', %s)
        RETURNING id
    """, (branch_id, student_id, room_id, teacher_id)).fetchone()["id"]
    assert report2_id != report1_id


def test_notifications_target_type_monthly_report(db):
    """Notifications table must allow target_type = 'monthly_report'."""
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    user_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('notif_guardian', 'guardian', 'hash_123') RETURNING id"
    ).fetchone()["id"]
    db.commit()
    notif_id = db.execute("""
        INSERT INTO notifications (branch_id, user_id, kind, title, body, target_type)
        VALUES (%s, %s, 'announcement', 'تقرير شهري جديد', 'تم نشر التقرير الشهري لشهر أكتوبر', 'monthly_report')
        RETURNING id, target_type
    """, (branch_id, user_id)).fetchone()
    assert notif_id["target_type"] == "monthly_report"


def test_monthly_reports_updated_at_trigger(db):
    """trg_monthly_student_reports_updated_at must update updated_at on UPDATE."""
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة التحديث', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]
    student_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'طالب التحديث') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    teacher_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('teacher_m5_trg', 'teacher', 'hash_123') RETURNING id"
    ).fetchone()["id"]
    db.commit()

    row = db.execute("""
        INSERT INTO monthly_student_reports (branch_id, student_id, room_id, month, teacher_user_id)
        VALUES (%s, %s, %s, '2026-10', %s)
        RETURNING id, updated_at
    """, (branch_id, student_id, room_id, teacher_id)).fetchone()
    report_id = row["id"]
    db.commit()
    initial_updated_at = row["updated_at"]

    time.sleep(0.05)

    db.execute("""
        UPDATE monthly_student_reports
        SET strengths = 'طالب متميز في التلاوة وحفظ سورة الملك'
        WHERE id = %s
    """, (report_id,))
    db.commit()

    updated_row = db.execute("SELECT updated_at, strengths FROM monthly_student_reports WHERE id = %s", (report_id,)).fetchone()
    assert updated_row["updated_at"] > initial_updated_at
    assert updated_row["strengths"] == "طالب متميز في التلاوة وحفظ سورة الملك"
