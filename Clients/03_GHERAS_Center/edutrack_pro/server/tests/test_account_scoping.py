"""Tests for relational account creation and scoping (Teacher, Guardian, Student)."""

from __future__ import annotations

import uuid

from tests.conftest import login, make_user


def test_student_creation_auto_creates_guardian_and_links(client, manager, db):
    phone = f"05{uuid.uuid4().int % 10**8:08d}"
    payload = {
        "name": "طالب تجريبي أولي",
        "guardian_phone": phone,
        "father_name": "أبو التجريبي",
        "guardian_relation": "الأب",
        "gender": "بنين",
    }
    res = client.post("/api/v1/students", json=payload, headers=manager)
    assert res.status_code == 200, res.text
    student = res.json()
    student_id = student["id"]

    # Verify guardian record was auto-created in database
    g = db.execute("SELECT id, name, phone, relation FROM guardians WHERE phone = %s", (phone,)).fetchone()
    assert g is not None
    assert g["name"] == "أبو التجريبي"
    assert g["phone"] == phone

    # Verify student_guardians link exists
    sg = db.execute(
        "SELECT student_id, guardian_id, is_primary FROM student_guardians WHERE student_id = %s AND guardian_id = %s",
        (student_id, g["id"]),
    ).fetchone()
    assert sg is not None
    assert sg["is_primary"] is True


def test_guardian_user_creation_with_child_ids_and_invalid_ids(client, manager, db):
    # 1. Create two real students
    s1_res = client.post("/api/v1/students", json={"name": "الابن الأول", "guardian_phone": "0501111111", "gender": "بنين"}, headers=manager)
    assert s1_res.status_code == 200
    s1_id = s1_res.json()["id"]

    s2_res = client.post("/api/v1/students", json={"name": "الابن الثاني", "guardian_phone": "0501111111", "gender": "بنين"}, headers=manager)
    assert s2_res.status_code == 200
    s2_id = s2_res.json()["id"]

    # 2. Create guardian user with both valid student IDs and invalid / nonexistent IDs
    guardian_phone = f"05{uuid.uuid4().int % 10**8:08d}"
    non_existent_id = str(uuid.uuid4())
    guardian_payload = {
        "username": guardian_phone,
        "password": "Password123!",
        "role": "guardian",
        "phone": guardian_phone,
        "guardian_name": "عبدالله الوالد",
        "child_student_ids": [s1_id, s2_id, "invalid-uuid-string", non_existent_id],
    }

    # Should succeed with 200 without PostgreSQL transaction failure
    res = client.post("/api/v1/users", json=guardian_payload, headers=manager)
    assert res.status_code == 200, res.text
    user_data = res.json()
    assert user_data["username"] == guardian_phone
    assert user_data["role"] == "guardian"
    assert user_data.get("guardian_id") is not None
    guardian_id = user_data["guardian_id"]

    # Verify both valid students are linked
    links = db.execute(
        "SELECT student_id FROM student_guardians WHERE guardian_id = %s AND deleted_at IS NULL",
        (guardian_id,),
    ).fetchall()
    linked_ids = {str(r["student_id"]) for r in links}
    assert s1_id in linked_ids
    assert s2_id in linked_ids

    # 3. Verify user response contains enriched children list
    get_res = client.get(f"/api/v1/users/{user_data['id']}", headers=manager)
    assert get_res.status_code == 200
    details = get_res.json()
    assert details["guardian_name"] == "عبدالله الوالد"
    assert isinstance(details.get("children"), list)
    children_names = [c["name"] for c in details["children"]]
    assert "الابن الأول" in children_names
    assert "الابن الثاني" in children_names


def test_student_user_creation_inherits_details_and_links(client, manager):
    phone = f"05{uuid.uuid4().int % 10**8:08d}"
    nat_id = f"1{uuid.uuid4().int % 10**9:09d}"

    # Create student
    st_res = client.post(
        "/api/v1/students",
        json={"name": "سعد محمد", "national_id": nat_id, "guardian_phone": phone, "father_name": "محمد", "gender": "بنين"},
        headers=manager,
    )
    assert st_res.status_code == 200
    st_id = st_res.json()["id"]

    # Create student user
    user_payload = {
        "username": nat_id,
        "password": "Password123!",
        "role": "student",
        "student_id": st_id,
    }
    u_res = client.post("/api/v1/users", json=user_payload, headers=manager)
    assert u_res.status_code == 200, u_res.text
    u_data = u_res.json()
    assert u_data["role"] == "student"
    assert u_data["student_id"] == st_id
    assert u_data["national_id"] == nat_id
    assert u_data["phone"] == phone

    # Verify enriched response
    get_res = client.get(f"/api/v1/users/{u_data['id']}", headers=manager)
    assert get_res.status_code == 200
    details = get_res.json()
    assert details["student_name"] == "سعد محمد"
    assert details.get("linked_guardian") is not None
    assert details["linked_guardian"]["phone"] == phone


def test_student_guardians_resource_accessible(client, manager):
    res = client.get("/api/v1/student-guardians", headers=manager)
    assert res.status_code == 200
    assert "items" in res.json()
