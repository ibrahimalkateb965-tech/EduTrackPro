from __future__ import annotations

import os
import uuid
import psycopg
import pytest

from tests.conftest import make_user, login
from edutrack_api.auth import hash_password


def _ensure_008_applied(db):
    col = db.execute(
        "SELECT 1 FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'student_id'"
    ).fetchone()
    if not col:
        mig_path = os.path.normpath(
            os.path.join(os.path.dirname(__file__), "..", "..", "db", "postgres", "008_national_id_auth_and_student_role.sql")
        )
        if os.path.exists(mig_path):
            with open(mig_path, "r", encoding="utf-8") as f:
                db.execute(f.read())
                db.commit()


def test_login_with_national_id_and_role_hint(db, client):
    _ensure_008_applied(db)

    # Create a guardian with national_id
    uid = uuid.uuid4()
    db.execute(
        "INSERT INTO users (id, username, password_hash, role, national_id) VALUES (%s, %s, %s, %s, %s)",
        (uid, "g_user1", hash_password("Secret123!"), "guardian", "1020304050"),
    )
    db.commit()

    # Login using national_id as the identity with role="guardian"
    res = client.post(
        "/api/v1/auth/login",
        json={"username": "1020304050", "password": "Secret123!", "role": "guardian"}
    )
    assert res.status_code == 200
    data = res.json()
    assert "token" in data
    assert data["user"]["role"] == "guardian"

    # Login fails if mismatched role hint is given
    res_mismatch = client.post(
        "/api/v1/auth/login",
        json={"username": "1020304050", "password": "Secret123!", "role": "teacher"}
    )
    assert res_mismatch.status_code == 401


def test_student_login_and_scope(db, client):
    _ensure_008_applied(db)

    # Create room and student matching real 001 schema
    room_id = uuid.uuid4()
    db.execute(
        "INSERT INTO rooms (id, branch_id, name, group_name) VALUES (%s, '00000000-0000-0000-0000-000000000001', 'Room 1', 'الصباح')",
        (room_id,)
    )
    student_id = uuid.uuid4()
    db.execute(
        "INSERT INTO students (id, room_id, name, status) VALUES (%s, %s, %s, 'active')",
        (student_id, room_id, "بطل غراس")
    )
    uid = uuid.uuid4()
    db.execute(
        "INSERT INTO users (id, username, password_hash, role, national_id, student_id) VALUES (%s, %s, %s, %s, %s, %s)",
        (uid, "s_user1", hash_password("Secret123!"), "student", "1122334455", student_id),
    )
    db.commit()

    # Student logs in with national_id
    res = client.post(
        "/api/v1/auth/login",
        json={"username": "1122334455", "password": "Secret123!", "role": "student"}
    )
    assert res.status_code == 200
    data = res.json()
    assert data["user"]["role"] == "student"
    assert data["user"]["name"] == "بطل غراس"


def test_student_login_via_students_national_id_when_user_nid_null(db, client):
    _ensure_008_applied(db)

    # Create room matching real 001 schema
    room_id = uuid.uuid4()
    db.execute(
        "INSERT INTO rooms (id, branch_id, name, group_name) VALUES (%s, '00000000-0000-0000-0000-000000000001', 'Room 3', 'الصباح')",
        (room_id,)
    )
    # Student carries the national_id; the user account has national_id NULL
    student_id = uuid.uuid4()
    db.execute(
        "INSERT INTO students (id, room_id, name, status, national_id) VALUES (%s, %s, %s, 'active', %s)",
        (student_id, room_id, "طالب هوية", "2563110275")
    )
    uid = uuid.uuid4()
    db.execute(
        "INSERT INTO users (id, username, password_hash, role, national_id, student_id) VALUES (%s, %s, %s, %s, %s, %s)",
        (uid, "s_nid_null", hash_password("Gh123456"), "student", None, student_id),
    )
    db.commit()

    # Login using the students.national_id
    res = client.post(
        "/api/v1/auth/login",
        json={"username": "2563110275", "password": "Gh123456"}
    )
    assert res.status_code == 200
    data = res.json()
    assert "token" in data
    assert data["user"]["role"] == "student"

    # Same login with role hint
    res_role = client.post(
        "/api/v1/auth/login",
        json={"username": "2563110275", "password": "Gh123456", "role": "student"}
    )
    assert res_role.status_code == 200


def test_student_update_syncs_national_id_to_user(db, client):
    _ensure_008_applied(db)

    room_id = uuid.uuid4()
    db.execute(
        "INSERT INTO rooms (id, branch_id, name, group_name) VALUES (%s, '00000000-0000-0000-0000-000000000001', 'Room 4', 'المساء')",
        (room_id,)
    )
    student_id = uuid.uuid4()
    db.execute(
        "INSERT INTO students (id, room_id, name, status) VALUES (%s, %s, %s, 'active')",
        (student_id, room_id, "طالب مزامنة")
    )
    uid = uuid.uuid4()
    db.execute(
        "INSERT INTO users (id, username, password_hash, role, national_id, student_id) VALUES (%s, %s, %s, %s, %s, %s)",
        (uid, "s_sync", hash_password("Secret123!"), "student", None, student_id),
    )
    make_user(db, "mgr_sync", "manager")
    headers = login(client, "mgr_sync")

    # Manager updates student's national_id via PATCH
    res = client.patch(
        f"/api/v1/students/{student_id}",
        json={"national_id": "3000000001"},
        headers=headers,
    )
    assert res.status_code == 200

    # national_id synced to linked student user account
    synced = db.execute(
        "SELECT national_id FROM users WHERE student_id = %s",
        (student_id,),
    ).fetchone()
    assert synced is not None
    assert synced["national_id"] == "3000000001"

    # User can now log in with the synced national_id
    res_login = client.post(
        "/api/v1/auth/login",
        json={"username": "3000000001", "password": "Secret123!"}
    )
    assert res_login.status_code == 200
