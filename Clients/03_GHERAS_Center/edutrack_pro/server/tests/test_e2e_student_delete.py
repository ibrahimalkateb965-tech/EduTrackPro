"""E2E Test: Student Soft Deletion & Audit Trail Lifecycle (Gheras Center - EduTrack Pro).

Validates the full enterprise pipeline:
1. Manager creates a student record with associated details.
2. Verify active listing and retrieval via REST API.
3. Manager executes soft deletion via DELETE /api/v1/students/{id}.
4. Assert 204 No Content response, subsequent 404 on individual retrieval,
   and exclusion from the active students list.
5. Verify PostgreSQL audit_log entry with action='delete' and valid timestamps.
6. Verify RBAC permission boundaries: supervisor without student permission receives 403 Forbidden.
7. Verify 404 Not Found for non-existent student deletion.
Strictly adheres to Rule 50 (0-9 Western Arabic numerals) and clean architecture invariants.
"""

from __future__ import annotations

import uuid


def test_e2e_student_delete_flow(client, db, manager):
    """End-to-End: Create student -> verify active -> delete -> verify 204, 404, and audit log."""
    # Step 1: Create a room for the student
    room = db.execute(
        "INSERT INTO rooms (name, group_name) VALUES (%s, %s) RETURNING id",
        ("قاعة ابن خلدون", "الصباح"),
    ).fetchone()
    room_id = str(room["id"])
    db.commit()

    # Step 2: Register student
    student_payload = {
        "name": "ياسر محمد الشمري",
        "gender": "بنين",
        "group_name": "الصباح",
        "room_id": room_id,
        "guardian_phone": "0557778899",
        "father_name": "محمد الشمري",
        "father_phone": "0557778899",
        "nationality": "سعودي",
    }
    create_res = client.post("/api/v1/students", json=student_payload, headers=manager)
    assert create_res.status_code == 200, f"Creation failed: {create_res.text}"
    student_id = create_res.json()["id"]

    # Step 3: Verify student is listed in active students
    list_res = client.get("/api/v1/students", headers=manager)
    assert list_res.status_code == 200
    active_ids = [s["id"] for s in list_res.json().get("items", [])]
    assert student_id in active_ids, "Newly created student must be present in active listing"

    # Step 4: Execute student deletion
    delete_res = client.delete(f"/api/v1/students/{student_id}", headers=manager)
    assert delete_res.status_code == 204, f"Delete failed: {delete_res.status_code} - {delete_res.text}"

    # Step 5: Verify GET /api/v1/students/{id} returns 404 Not Found
    get_res = client.get(f"/api/v1/students/{student_id}", headers=manager)
    assert get_res.status_code == 404, f"Deleted student should return 404, got: {get_res.status_code}"

    # Step 6: Verify student is excluded from active students list
    list_after_res = client.get("/api/v1/students", headers=manager)
    assert list_after_res.status_code == 200
    active_ids_after = [s["id"] for s in list_after_res.json().get("items", [])]
    assert student_id not in active_ids_after, "Deleted student must be excluded from active listing"

    # Step 7: Verify PostgreSQL direct soft delete flag
    row = db.execute("SELECT deleted_at FROM students WHERE id = %s", (student_id,)).fetchone()
    assert row is not None, "Record must still exist physically in PostgreSQL"
    assert row["deleted_at"] is not None, "deleted_at timestamp must be set"

    # Step 8: Verify audit_log entry
    audit_row = db.execute(
        "SELECT action, entity, entity_id FROM audit_log "
        "WHERE entity = 'students' AND entity_id = %s AND action = 'delete'",
        (student_id,),
    ).fetchone()
    assert audit_row is not None, "Audit log must record student deletion"
    assert audit_row["action"] == "delete"


def test_e2e_student_delete_permissions(client, db, manager, supervisor):
    """End-to-End: Verify supervisor without student permission receives 403 Forbidden."""
    # Create student as manager
    create_res = client.post(
        "/api/v1/students",
        json={"name": "ماجد تركي المطيري", "gender": "بنين", "guardian_phone": "0553332211"},
        headers=manager,
    )
    assert create_res.status_code == 200
    student_id = create_res.json()["id"]

    # Attempt deletion as supervisor without permissions
    sup_del_res = client.delete(f"/api/v1/students/{student_id}", headers=supervisor)
    assert sup_del_res.status_code == 403, f"Expected 403 Forbidden, got: {sup_del_res.status_code}"

    # Verify student is still active
    check_res = client.get(f"/api/v1/students/{student_id}", headers=manager)
    assert check_res.status_code == 200, "Student must still exist after unauthorized delete attempt"

    # Clean up by deleting with manager
    mgr_del_res = client.delete(f"/api/v1/students/{student_id}", headers=manager)
    assert mgr_del_res.status_code == 204


def test_e2e_student_delete_nonexistent(client, db, manager):
    """End-to-End: Attempting to delete a non-existent student ID returns 404 Not Found."""
    fake_id = uuid.uuid4()
    del_res = client.delete(f"/api/v1/students/{fake_id}", headers=manager)
    assert del_res.status_code == 404, f"Expected 404 for non-existent ID, got: {del_res.status_code}"
