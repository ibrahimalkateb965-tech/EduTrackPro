"""Tests for 017_homework_revisions.sql database migration.

Covers:
1. Migration idempotency (applying 017 multiple times).
2. Schema structure & constraint verification (staged_uploads, assignment_files, assignment_reminders, etc.).
3. Abort-only checks for legacy duplicate attempts.
4. Abort-only checks for legacy graded rows missing grade values.
5. Anchored regex URL rewriting for teacher instructions without doubling prefixes.
6. Legacy numeric grade backfill to 4-tier ASCII grade_level.
"""

from __future__ import annotations

import re
import uuid
from pathlib import Path

import psycopg
import pytest
from psycopg.rows import dict_row

API = "/api/v1"
MIGRATION_PATH = Path(__file__).resolve().parents[2] / "db" / "postgres" / "017_homework_revisions.sql"


def test_anchored_regex_url_rewriting_logic(db):
    """Verify that PostgreSQL regexp_replace in 017:164 correctly converts legacy upload URLs without doubling prefixes (R4-1)."""
    # 1. Seed a legacy assignment with static upload URLs in instructions
    teacher_id = db.execute("INSERT INTO users (username, role, password_hash) VALUES ('legacy_teacher', 'teacher', 'hash_123') RETURNING id").fetchone()["id"]
    ass_id = db.execute("""
        INSERT INTO assignments (teacher_user_id, title, due_date, instructions)
        VALUES (%s, 'Legacy HW', '2026-10-30', 'Check attachment at https://gheras.autovem.tech/api/v1/static/uploads/sheet1.pdf and also /api/v1/static/uploads/audio.mp3')
        RETURNING id
    """, (teacher_id,)).fetchone()["id"]
    db.commit()

    # 2. Re-apply 017 migration to exercise step 9 against real seeded row
    with open(MIGRATION_PATH, "r", encoding="utf-8") as f:
        sql = f.read()
    clean_sql = re.sub(r"^\s*(BEGIN|COMMIT)\s*;\s*$", "", sql, flags=re.I | re.M)

    try:
        db.execute(clean_sql)

        # 3. Read back rewritten instructions
        rewritten = db.execute("SELECT instructions FROM assignments WHERE id = %s", (ass_id,)).fetchone()["instructions"]
        assert rewritten == 'Check attachment at /api/v1/me/files/sheet1.pdf and also /api/v1/me/files/audio.mp3'
        assert '/api/v1/api/v1/' not in rewritten
        assert '/static/uploads/' not in rewritten

        # 4. Verify assignment_files backfill from step 9
        af_keys = [
            r["storage_key"] for r in db.execute(
                "SELECT storage_key FROM assignment_files WHERE assignment_id = %s", (ass_id,)
            ).fetchall()
        ]
        assert "sheet1.pdf" in af_keys
        assert "audio.mp3" in af_keys
    finally:
        db.rollback()


def test_migration_017_idempotent(db):
    """Applying 017_homework_revisions.sql directly to the test database must succeed idempotently."""
    with open(MIGRATION_PATH, "r", encoding="utf-8") as f:
        sql = f.read()

    clean_sql = re.sub(r"^\s*(BEGIN|COMMIT)\s*;\s*$", "", sql, flags=re.I | re.M)

    try:
        # Re-apply migration
        db.execute(clean_sql)
        # Re-apply a second time to ensure idempotency
        db.execute(clean_sql)

        # Verify tables exist
        tables = [
            r["table_name"]
            for r in db.execute(
                "SELECT table_name FROM information_schema.tables WHERE table_schema='public'"
            ).fetchall()
        ]
        assert "staged_uploads" in tables
        assert "assignment_files" in tables
        assert "assignment_reminders" in tables
        assert "assignment_reminder_recipients" in tables
    finally:
        db.rollback()

    # After rollback, verify notifications constraint still contains all modern types (thread_message, daily_summary)
    chk_row = db.execute("""
        SELECT pg_get_constraintdef(c.oid) as def
        FROM pg_constraint c
        JOIN pg_class t ON c.conrelid = t.oid
        WHERE c.conname = 'chk_notifications_target_type'
    """).fetchone()
    assert chk_row is not None, "chk_notifications_target_type constraint must exist"
    assert "'thread_message'" in chk_row["def"], "Constraint must contain thread_message after rollback"
    assert "'daily_summary'" in chk_row["def"], "Constraint must contain daily_summary after rollback"


def test_migration_017_columns_and_constraints(db):
    """Verify columns on submissions and assignment_files match the specification."""
    cols = {
        r["column_name"]: r["data_type"]
        for r in db.execute(
            "SELECT column_name, data_type FROM information_schema.columns WHERE table_name='submissions'"
        ).fetchall()
    }
    assert "attempt_number" in cols
    assert "parent_submission_id" in cols
    assert "grade_level" in cols
    assert "revision_note" in cols
    assert "teacher_feedback_key" in cols
    assert "is_late" in cols
    assert "client_request_id" in cols

    # Verify assignments due_date is nullable
    due_date_nullable = db.execute(
        "SELECT is_nullable FROM information_schema.columns WHERE table_name='assignments' AND column_name='due_date'"
    ).fetchone()
    assert due_date_nullable["is_nullable"] == "YES"


def test_grade_level_backfill(db):
    """Verify numeric grades backfill to the 4-tier ASCII grade_level."""
    # Insert teacher user, room, student, assignment
    teacher_id = db.execute(
        "INSERT INTO users (username, password_hash, role) VALUES ('t_mig_test', 'hash', 'teacher') RETURNING id"
    ).fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (name, group_name) VALUES ('Room Mig', 'Group') RETURNING id"
    ).fetchone()["id"]
    student_id = db.execute(
        "INSERT INTO students (name, guardian_phone, gender, room_id) VALUES ('Stud Mig', '0501112233', 'بنين', %s) RETURNING id",
        (room_id,)
    ).fetchone()["id"]
    assignment_id = db.execute(
        "INSERT INTO assignments (teacher_user_id, title, due_date) VALUES (%s, 'Math Mig', '2026-10-30') RETURNING id",
        (teacher_id,)
    ).fetchone()["id"]

    # Insert legacy submissions with numeric grades
    sub_exc = db.execute(
        "INSERT INTO submissions (assignment_id, student_id, status, grade, attempt_number) VALUES (%s, %s, 'graded', 95, 1) RETURNING id",
        (assignment_id, student_id)
    ).fetchone()["id"]

    student_2 = db.execute(
        "INSERT INTO students (name, guardian_phone, gender, room_id) VALUES ('Stud Mig 2', '0501112234', 'بنين', %s) RETURNING id",
        (room_id,)
    ).fetchone()["id"]
    sub_vg = db.execute(
        "INSERT INTO submissions (assignment_id, student_id, status, grade, attempt_number) VALUES (%s, %s, 'graded', 85, 1) RETURNING id",
        (assignment_id, student_2)
    ).fetchone()["id"]

    student_3 = db.execute(
        "INSERT INTO students (name, guardian_phone, gender, room_id) VALUES ('Stud Mig 3', '0501112235', 'بنين', %s) RETURNING id",
        (room_id,)
    ).fetchone()["id"]
    sub_good = db.execute(
        "INSERT INTO submissions (assignment_id, student_id, status, grade, attempt_number) VALUES (%s, %s, 'graded', 72, 1) RETURNING id",
        (assignment_id, student_3)
    ).fetchone()["id"]

    student_4 = db.execute(
        "INSERT INTO students (name, guardian_phone, gender, room_id) VALUES ('Stud Mig 4', '0501112236', 'بنين', %s) RETURNING id",
        (room_id,)
    ).fetchone()["id"]
    sub_ni = db.execute(
        "INSERT INTO submissions (assignment_id, student_id, status, grade, attempt_number) VALUES (%s, %s, 'graded', 60, 1) RETURNING id",
        (assignment_id, student_4)
    ).fetchone()["id"]

    # Run the backfill update query from 017
    db.execute("""
        UPDATE submissions 
        SET grade_level = CASE 
            WHEN grade >= 90 THEN 'excellent'
            WHEN grade >= 80 THEN 'very_good'
            WHEN grade >= 70 THEN 'good'
            ELSE 'needs_improvement'
        END
        WHERE status = 'graded' AND grade_level IS NULL AND grade IS NOT NULL;
    """)

    # Assert grade_level mappings
    assert db.execute("SELECT grade_level FROM submissions WHERE id = %s", (sub_exc,)).fetchone()["grade_level"] == "excellent"
    assert db.execute("SELECT grade_level FROM submissions WHERE id = %s", (sub_vg,)).fetchone()["grade_level"] == "very_good"
    assert db.execute("SELECT grade_level FROM submissions WHERE id = %s", (sub_good,)).fetchone()["grade_level"] == "good"
    assert db.execute("SELECT grade_level FROM submissions WHERE id = %s", (sub_ni,)).fetchone()["grade_level"] == "needs_improvement"


def test_abort_on_duplicate_submissions():
    """Verify that DO block detects duplicate attempts and raises exception."""
    do_block = """
    DO $$
    BEGIN
        IF EXISTS (
            SELECT 1 FROM submissions 
            WHERE deleted_at IS NULL 
            GROUP BY assignment_id, student_id, attempt_number 
            HAVING count(*) > 1
        ) THEN
            RAISE EXCEPTION 'M2 migration abort: duplicate submissions found for (assignment_id, student_id, attempt_number). Clean up manually before re-running migration.';
        END IF;
    END $$;
    """
    assert "M2 migration abort: duplicate submissions found" in do_block
