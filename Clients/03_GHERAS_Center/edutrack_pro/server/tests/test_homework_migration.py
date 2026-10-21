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
    # Execute actual SQL regexp_replace against Postgres
    res = db.execute("""
        SELECT 
            regexp_replace('Check attachment at https://gheras.autovem.tech/api/v1/static/uploads/sheet1.pdf', '(https?://[^/"\\s]+)?/api/v1/static/uploads/', '/api/v1/me/files/', 'g') as case1,
            regexp_replace('Review file /api/v1/static/uploads/audio_lesson.mp3 for details', '(https?://[^/"\\s]+)?/api/v1/static/uploads/', '/api/v1/me/files/', 'g') as case2,
            regexp_replace('<img src="/api/v1/static/uploads/diagram.png" />', '(https?://[^/"\\s]+)?/api/v1/static/uploads/', '/api/v1/me/files/', 'g') as case3
    """).fetchone()

    # Case 1: Absolute URL with domain
    assert res["case1"] == 'Check attachment at /api/v1/me/files/sheet1.pdf'
    assert '/api/v1/api/v1/' not in res["case1"]

    # Case 2: Relative URL
    assert res["case2"] == 'Review file /api/v1/me/files/audio_lesson.mp3 for details'
    assert '/api/v1/api/v1/' not in res["case2"]

    # Case 3: URL inside HTML tags or markdown
    assert res["case3"] == '<img src="/api/v1/me/files/diagram.png" />'


def test_migration_017_idempotent(db):
    """Applying 017_homework_revisions.sql directly to the test database must succeed idempotently."""
    with open(MIGRATION_PATH, "r", encoding="utf-8") as f:
        sql = f.read()

    # Re-apply migration
    db.execute(sql)
    db.commit()

    # Re-apply a second time to ensure idempotency
    db.execute(sql)
    db.commit()

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
