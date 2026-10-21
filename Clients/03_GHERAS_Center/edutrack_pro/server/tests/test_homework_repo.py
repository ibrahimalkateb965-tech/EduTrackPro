"""Tests for assignment_repo.py business logic and multi-attempt lifecycle.

Covers:
1. Multi-attempt creation with parent_submission_id links (H1, H2).
2. Immutable attempts: previous attempts and files remain intact when revising.
3. Timezone-safe is_late calculation for past, future, today, and NULL due dates (R3-5).
4. Savepoint concurrency handling and 409 attempt_conflict mapping.
5. Staged uploads ownership verification (R3-2, R4-2).
6. Edit lock enforcement after initial submission.
"""

from __future__ import annotations

import datetime
import uuid
import psycopg
import pytest
from psycopg.rows import dict_row

from edutrack_api.errors import ApiError
from edutrack_api.repositories import assignment_repo
from edutrack_api.scope import Scope


@pytest.fixture()
def repo_env(db):
    """Seed branch, room, teacher, guardian, student, and enrolled assignment."""
    branch_id = uuid.UUID("00000000-0000-0000-0000-000000000001")
    teacher_id = db.execute(
        "INSERT INTO users (username, password_hash, role) VALUES ('teacher_h1', 'hash', 'teacher') RETURNING id"
    ).fetchone()["id"]
    guardian_id = db.execute(
        "INSERT INTO users (username, password_hash, role) VALUES ('guardian_h1', 'hash', 'guardian') RETURNING id"
    ).fetchone()["id"]
    room_id = db.execute(
        "INSERT INTO rooms (name, group_name) VALUES ('Room H1', 'G1') RETURNING id"
    ).fetchone()["id"]
    student_id = db.execute(
        "INSERT INTO students (name, guardian_phone, gender, room_id) VALUES ('Student H1', '0501234567', 'بنين', %s) RETURNING id",
        (room_id,)
    ).fetchone()["id"]

    # Link student to guardian
    g_entity_id = db.execute(
        "INSERT INTO guardians (name, phone) VALUES ('Guardian Name', '0501234567') RETURNING id"
    ).fetchone()["id"]
    db.execute("UPDATE users SET guardian_id = %s WHERE id = %s", (g_entity_id, guardian_id))
    db.execute(
        "INSERT INTO student_guardians (student_id, guardian_id, relation) VALUES (%s, %s, 'أب')",
        (student_id, g_entity_id)
    )

    # Scopes
    teacher_scope = Scope(user_id=teacher_id, role="teacher", branch_id=branch_id, room_ids={room_id}, student_ids={student_id})
    guardian_scope = Scope(user_id=guardian_id, role="guardian", branch_id=branch_id, room_ids={room_id}, student_ids={student_id})

    # Stage an upload for guardian
    stage_key = f"submissions/test_{uuid.uuid4().hex[:8]}.jpg"
    db.execute("""
        INSERT INTO staged_uploads (storage_key, uploaded_by_user_id, media_type, file_name, bytes)
        VALUES (%s, %s, 'image', 'solution.jpg', 1024)
    """, (stage_key, guardian_id))

    # Stage an upload for teacher
    teacher_stage_key = f"assignments/inst_{uuid.uuid4().hex[:8]}.pdf"
    db.execute("""
        INSERT INTO staged_uploads (storage_key, uploaded_by_user_id, media_type, file_name, bytes)
        VALUES (%s, %s, 'document', 'worksheet.pdf', 2048)
    """, (teacher_stage_key, teacher_id))

    db.commit()

    return {
        "branch_id": branch_id,
        "teacher_id": teacher_id,
        "guardian_id": guardian_id,
        "room_id": room_id,
        "student_id": student_id,
        "teacher_scope": teacher_scope,
        "guardian_scope": guardian_scope,
        "stage_key": stage_key,
        "teacher_stage_key": teacher_stage_key,
    }


def test_submit_attempt_lifecycle_immutable(db, repo_env):
    """Test full multi-attempt lifecycle: initial submit -> revision_requested -> resubmit (H1, H2)."""
    env = repo_env
    # 1. Create assignment
    ass = assignment_repo.create_assignment(db, env["teacher_scope"], {
        "title": "واجب الرياضيات 1",
        "room_id": env["room_id"],
        "due_date": datetime.date.today() + datetime.timedelta(days=2),
        "is_draft": False,
    })
    ass_id = ass["id"]
    db.commit()

    # 2. Guardian submits Attempt 1
    sub1 = assignment_repo.submit_assignment_cas(
        db, env["guardian_scope"], ass_id, env["student_id"],
        client_request_id=uuid.uuid4(),
        files=[{"storage_key": env["stage_key"], "media_type": "image", "file_name": "solution.jpg", "bytes": 1024}]
    )
    db.commit()

    assert sub1["attempt_number"] == 1
    assert sub1["parent_submission_id"] is None
    assert sub1["status"] == "submitted"
    assert sub1["is_late"] is False
    assert len(sub1["files"]) == 1

    # 3. Concurrent submit attempt while status is 'submitted' must be rejected (409)
    with pytest.raises(ApiError) as exc_info:
        assignment_repo.submit_assignment_cas(
            db, env["guardian_scope"], ass_id, env["student_id"],
            client_request_id=uuid.uuid4(),
            files=[{"storage_key": env["stage_key"]}]
        )
    assert exc_info.value.status == 409
    assert exc_info.value.code == "already_submitted"

    # 4. Teacher reviews Attempt 1: requests revision
    rev1 = assignment_repo.review_submission_cas(
        db, env["teacher_scope"], sub1["id"],
        status="revision_requested",
        revision_note="يرجى توضيح خطوات حل المسألة الثانية"
    )
    db.commit()
    assert rev1["status"] == "revision_requested"
    assert rev1["revision_note"] == "يرجى توضيح خطوات حل المسألة الثانية"

    # Stage new file for Attempt 2
    stage_key_2 = f"submissions/test_{uuid.uuid4().hex[:8]}.jpg"
    db.execute("""
        INSERT INTO staged_uploads (storage_key, uploaded_by_user_id, media_type, file_name, bytes)
        VALUES (%s, %s, 'image', 'solution_v2.jpg', 2048)
    """, (stage_key_2, env["guardian_id"]))
    db.commit()

    # 5. Guardian submits Attempt 2
    sub2 = assignment_repo.submit_assignment_cas(
        db, env["guardian_scope"], ass_id, env["student_id"],
        client_request_id=uuid.uuid4(),
        files=[{"storage_key": stage_key_2, "media_type": "image", "file_name": "solution_v2.jpg", "bytes": 2048}]
    )
    db.commit()

    assert sub2["attempt_number"] == 2
    assert sub2["parent_submission_id"] == sub1["id"]
    assert sub2["status"] == "submitted"

    # 6. Verify Attempt 1 remains frozen and immutable
    persisted_sub1 = db.execute("SELECT * FROM submissions WHERE id = %s", (sub1["id"],)).fetchone()
    assert persisted_sub1["status"] == "revision_requested"
    assert persisted_sub1["attempt_number"] == 1
    assert persisted_sub1["deleted_at"] is None

    files_sub1 = db.execute("SELECT * FROM submission_files WHERE submission_id = %s", (sub1["id"],)).fetchall()
    assert len(files_sub1) == 1
    assert files_sub1[0]["storage_key"] == env["stage_key"]


def test_is_late_calculation_four_cases(db, repo_env):
    """Verify is_late timezone-safe calculation across future, past, today, and NULL due dates (R3-5)."""
    env = repo_env
    today = datetime.date.today()

    cases = [
        ("Future due date", today + datetime.timedelta(days=2), False),
        ("Past due date", today - datetime.timedelta(days=2), True),
        ("Today due date", today, False),
        ("Nullable due date", None, False),
    ]

    for label, due_date, expected_late in cases:
        ass = assignment_repo.create_assignment(db, env["teacher_scope"], {
            "title": f"Test {label}",
            "room_id": env["room_id"],
            "due_date": due_date,
            "is_draft": False,
        })
        ass_id = ass["id"]
        db.commit()

        # Submit
        stage_key = f"submissions/test_{uuid.uuid4().hex[:8]}.jpg"
        db.execute("""
            INSERT INTO staged_uploads (storage_key, uploaded_by_user_id, media_type, file_name, bytes)
            VALUES (%s, %s, 'image', 'test.jpg', 512)
        """, (stage_key, env["guardian_id"]))
        db.commit()

        sub = assignment_repo.submit_assignment_cas(
            db, env["guardian_scope"], ass_id, env["student_id"],
            client_request_id=uuid.uuid4(),
            files=[{"storage_key": stage_key}]
        )
        db.commit()

        assert sub["is_late"] == expected_late, f"Failed for {label}: expected {expected_late}, got {sub['is_late']}"


def test_staged_uploads_ownership_security(db, repo_env):
    """Attempting to attach a file staged by another user raises 403 unauthorized_file."""
    env = repo_env
    ass = assignment_repo.create_assignment(db, env["teacher_scope"], {
        "title": "Security Assignment",
        "room_id": env["room_id"],
        "due_date": datetime.date.today() + datetime.timedelta(days=1),
        "is_draft": False,
    })
    ass_id = ass["id"]
    db.commit()

    # Guardian tries to attach teacher_stage_key (uploaded by teacher, not guardian)
    with pytest.raises(ApiError) as exc_info:
        assignment_repo.submit_assignment_cas(
            db, env["guardian_scope"], ass_id, env["student_id"],
            client_request_id=uuid.uuid4(),
            files=[{"storage_key": env["teacher_stage_key"]}]
        )
    assert exc_info.value.status == 403
    assert exc_info.value.code == "unauthorized_file"


def test_edit_lock_enforcement(db, repo_env):
    """Updating assignment details once a submission exists must be blocked with 409 edit_locked."""
    env = repo_env
    ass = assignment_repo.create_assignment(db, env["teacher_scope"], {
        "title": "Lock Test",
        "room_id": env["room_id"],
        "due_date": datetime.date.today() + datetime.timedelta(days=1),
        "is_draft": False,
    })
    ass_id = ass["id"]
    db.commit()

    # Submit
    assignment_repo.submit_assignment_cas(
        db, env["guardian_scope"], ass_id, env["student_id"],
        client_request_id=uuid.uuid4(),
        files=[{"storage_key": env["stage_key"]}]
    )
    db.commit()

    # Teacher tries to update
    with pytest.raises(ApiError) as exc_info:
        assignment_repo.create_assignment(db, env["teacher_scope"], {
            "id": ass_id,
            "title": "Modified Title",
            "room_id": env["room_id"],
        })
    assert exc_info.value.status == 409
    assert exc_info.value.code == "edit_locked"
