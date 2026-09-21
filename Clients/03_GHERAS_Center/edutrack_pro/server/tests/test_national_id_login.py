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
