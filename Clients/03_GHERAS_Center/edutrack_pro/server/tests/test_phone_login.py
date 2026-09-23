from __future__ import annotations

import uuid
from edutrack_api.auth import hash_password


def test_teacher_phone_login_local_and_intl(db, client):
    staff_id = uuid.uuid4()
    db.execute(
        "INSERT INTO staff (id, branch_id, name, role_title, phone) "
        "VALUES (%s, '00000000-0000-0000-0000-000000000001', 'أ/ محمد', 'معلم', '0559876543')",
        (staff_id,)
    )

    user_id = uuid.uuid4()
    db.execute(
        "INSERT INTO users (id, username, password_hash, role, staff_id, phone, is_active) "
        "VALUES (%s, 'm_teacher', %s, 'teacher', %s, '0559876543', true)",
        (user_id, hash_password("TeachPass123!"), staff_id)
    )
    db.commit()

    # 1. Login with local 05x phone format
    res_local = client.post(
        "/api/v1/auth/login",
        json={"username": "0559876543", "password": "TeachPass123!", "role": "teacher"}
    )
    assert res_local.status_code == 200, res_local.text
    data = res_local.json()
    assert data["user"]["role"] == "teacher"
    assert data["user"]["name"] == "أ/ محمد"

    # 2. Login with 9665x intl format
    res_intl = client.post(
        "/api/v1/auth/login",
        json={"username": "966559876543", "password": "TeachPass123!", "role": "teacher"}
    )
    assert res_intl.status_code == 200, res_intl.text

    # 3. Login with 9-digit format (559876543)
    res_short = client.post(
        "/api/v1/auth/login",
        json={"username": "559876543", "password": "TeachPass123!", "role": "teacher"}
    )
    assert res_short.status_code == 200, res_short.text

    # 4. Login with original username still works
    res_user = client.post(
        "/api/v1/auth/login",
        json={"username": "m_teacher", "password": "TeachPass123!", "role": "teacher"}
    )
    assert res_user.status_code == 200, res_user.text


def test_staff_phone_fallback_when_user_phone_null(db, client):
    staff_id = uuid.uuid4()
    db.execute(
        "INSERT INTO staff (id, branch_id, name, role_title, phone) "
        "VALUES (%s, '00000000-0000-0000-0000-000000000001', 'أ/ خالد', 'معلم', '0541112233')",
        (staff_id,)
    )
    user_id = uuid.uuid4()
    db.execute(
        "INSERT INTO users (id, username, password_hash, role, staff_id, phone, is_active) "
        "VALUES (%s, 'k_teacher', %s, 'teacher', %s, NULL, true)",
        (user_id, hash_password("Pass12345!"), staff_id)
    )
    db.commit()

    res = client.post(
        "/api/v1/auth/login",
        json={"username": "0541112233", "password": "Pass12345!", "role": "teacher"}
    )
    assert res.status_code == 200, res.text
    assert res.json()["user"]["name"] == "أ/ خالد"


def test_shared_phone_cross_role_collision_no_role_filter(db, client):
    """
    Simulates production scenario: a guardian and a student share the same phone number.
    Web dashboard posts login without a role filter.
    Password verification must authenticate the correct user deterministically without LIMIT 1 collision.
    """
    shared_phone = "0509998877"

    # Create guardian user
    guardian_id = uuid.uuid4()
    db.execute(
        "INSERT INTO guardians (id, branch_id, name, phone) "
        "VALUES (%s, '00000000-0000-0000-0000-000000000001', 'ولي الأمر / عبدالله', %s)",
        (guardian_id, shared_phone)
    )
    guardian_user_id = uuid.uuid4()
    db.execute(
        "INSERT INTO users (id, username, password_hash, role, guardian_id, phone, is_active) "
        "VALUES (%s, 'g_abdullah', %s, 'guardian', %s, %s, true)",
        (guardian_user_id, hash_password("Guardian#Pass1"), guardian_id, shared_phone)
    )

    # Create student user with the same phone
    student_user_id = uuid.uuid4()
    db.execute(
        "INSERT INTO users (id, username, password_hash, role, phone, is_active) "
        "VALUES (%s, 's_ahmed', %s, 'student', %s, true)",
        (student_user_id, hash_password("Student#Pass2"), shared_phone)
    )
    db.commit()

    # 1. Guardian logs in using shared phone without role filter
    res_guardian = client.post(
        "/api/v1/auth/login",
        json={"username": shared_phone, "password": "Guardian#Pass1"}
    )
    assert res_guardian.status_code == 200, res_guardian.text
    assert res_guardian.json()["user"]["role"] == "guardian"
    assert res_guardian.json()["user"]["username"] == "g_abdullah"

    # 2. Student logs in using shared phone without role filter
    res_student = client.post(
        "/api/v1/auth/login",
        json={"username": shared_phone, "password": "Student#Pass2"}
    )
    assert res_student.status_code == 200, res_student.text
    assert res_student.json()["user"]["role"] == "student"
    assert res_student.json()["user"]["username"] == "s_ahmed"


def test_unknown_phone_returns_401(client):
    res = client.post(
        "/api/v1/auth/login",
        json={"username": "0599999999", "password": "AnyPassword123!"}
    )
    assert res.status_code == 401
    assert res.json()["error"]["code"] == "unauthorized"


def test_wrong_password_returns_401(db, client):
    user_id = uuid.uuid4()
    phone = "0566665544"
    db.execute(
        "INSERT INTO users (id, username, password_hash, role, phone, is_active) "
        "VALUES (%s, 'test_user_wp', %s, 'teacher', %s, true)",
        (user_id, hash_password("RealPassword#1"), phone)
    )
    db.commit()

    res = client.post(
        "/api/v1/auth/login",
        json={"username": phone, "password": "WrongPassword#9"}
    )
    assert res.status_code == 401
    assert res.json()["error"]["code"] == "unauthorized"
