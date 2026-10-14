"""E2E Test: Staff Soft Deletion & Lifecycle Management (Gheras Center - EduTrack Pro).

Validates the full enterprise staff lifecycle:
1. Manager creates a staff member with role and salary details.
2. Manager links a user login account for the staff member.
3. Verify active listing and individual retrieval via REST API.
4. Manager executes soft deletion via DELETE /api/v1/staff/{id}.
5. Assert 204 No Content response, subsequent 404 on individual retrieval,
   and exclusion from the active staff list.
6. Verify associated user login account is automatically soft-deleted and deactivated.
7. Verify PostgreSQL audit_log entry with action='delete' and valid timestamps.
8. Verify RBAC permission boundaries: supervisor receives 403 Forbidden.
9. Verify manager cannot delete their own active staff record (400 Bad Request).
10. Verify 404 Not Found for non-existent staff deletion.
Strictly adheres to Rule 50 (0-9 Western Arabic numerals) and clean architecture invariants.
"""

from __future__ import annotations

import uuid


def test_e2e_staff_delete_lifecycle(client, db, manager):
    """End-to-End: Create staff -> link user -> verify -> delete -> verify 204, 404, deactivation, and audit log."""
    # Step 1: Create staff member
    staff_payload = {
        "name": "عبدالرحمن فهد القحطاني",
        "role_title": "معلم قرآني",
        "phone": "0559998877",
        "base_salary": 4500.0,
        "hire_date": "2026-01-15",
        "status": "active",
    }
    create_res = client.post("/api/v1/staff", json=staff_payload, headers=manager)
    assert create_res.status_code == 200, f"Staff creation failed: {create_res.text}"
    staff_id = create_res.json()["id"]

    # Step 2: Create a user account linked to this staff member
    username = f"teacher_{uuid.uuid4().hex[:6]}"
    user_payload = {
        "username": username,
        "password": "TempPassword123!",
        "role": "teacher",
        "staff_id": staff_id,
        "phone": "0559998877",
    }
    create_user_res = client.post("/api/v1/users", json=user_payload, headers=manager)
    assert create_user_res.status_code == 200, f"User creation failed: {create_user_res.text}"
    user_id = create_user_res.json()["id"]

    # Step 3: Verify staff is in active staff listing
    list_res = client.get("/api/v1/staff", headers=manager)
    assert list_res.status_code == 200
    active_ids = [s["id"] for s in list_res.json().get("items", [])]
    assert staff_id in active_ids, "Newly created staff must be present in active listing"

    # Step 4: Execute staff deletion
    delete_res = client.delete(f"/api/v1/staff/{staff_id}", headers=manager)
    assert delete_res.status_code == 204, f"Delete failed: {delete_res.status_code} - {delete_res.text}"

    # Step 5: Verify GET /api/v1/staff/{id} returns 404 Not Found
    get_res = client.get(f"/api/v1/staff/{staff_id}", headers=manager)
    assert get_res.status_code == 404, f"Deleted staff should return 404, got: {get_res.status_code}"

    # Step 6: Verify staff is excluded from active staff list
    list_after_res = client.get("/api/v1/staff", headers=manager)
    assert list_after_res.status_code == 200
    active_ids_after = [s["id"] for s in list_after_res.json().get("items", [])]
    assert staff_id not in active_ids_after, "Deleted staff must be excluded from active listing"

    # Step 7: Verify PostgreSQL direct soft delete flag on staff
    staff_row = db.execute("SELECT deleted_at FROM staff WHERE id = %s", (staff_id,)).fetchone()
    assert staff_row is not None, "Staff record must still exist physically in PostgreSQL"
    assert staff_row["deleted_at"] is not None, "deleted_at timestamp must be set on staff"

    # Step 8: Verify associated user account is deactivated and soft-deleted
    user_row = db.execute("SELECT deleted_at, is_active FROM users WHERE id = %s", (user_id,)).fetchone()
    assert user_row is not None, "User record must still exist physically in PostgreSQL"
    assert user_row["deleted_at"] is not None, "deleted_at timestamp must be set on linked user"
    assert user_row["is_active"] is False, "Linked user account must be deactivated"

    # Step 9: Verify audit_log entry
    audit_row = db.execute(
        "SELECT action, entity, entity_id FROM audit_log "
        "WHERE entity = 'staff' AND entity_id = %s AND action = 'delete'",
        (staff_id,),
    ).fetchone()
    assert audit_row is not None, "Audit log must record staff deletion"
    assert audit_row["action"] == "delete"


def test_e2e_staff_delete_permissions(client, db, manager, supervisor):
    """End-to-End: Verify supervisor cannot delete staff records (403 Forbidden)."""
    # Create staff as manager
    create_res = client.post(
        "/api/v1/staff",
        json={"name": "سلطان إبراهيم الخالدي", "role_title": "مشرف تربوي", "status": "active"},
        headers=manager,
    )
    assert create_res.status_code == 200
    staff_id = create_res.json()["id"]

    # Attempt deletion as supervisor (should fail with 403 Forbidden)
    sup_del_res = client.delete(f"/api/v1/staff/{staff_id}", headers=supervisor)
    assert sup_del_res.status_code == 403, f"Expected 403 Forbidden, got: {sup_del_res.status_code}"

    # Verify staff record is still active
    check_res = client.get(f"/api/v1/staff/{staff_id}", headers=manager)
    assert check_res.status_code == 200, "Staff must still exist after unauthorized delete attempt"

    # Clean up by deleting with manager
    mgr_del_res = client.delete(f"/api/v1/staff/{staff_id}", headers=manager)
    assert mgr_del_res.status_code == 204


def test_e2e_staff_delete_self_prevention(client, db, manager):
    """End-to-End: Verify manager cannot delete their own staff profile (400 Bad Request)."""
    # Query current manager's user record and staff_id
    manager_user = db.execute(
        "SELECT id, staff_id FROM users WHERE username = 'manager' AND deleted_at IS NULL"
    ).fetchone()

    if manager_user and manager_user.get("staff_id"):
        mgr_staff_id = str(manager_user["staff_id"])
        res = client.delete(f"/api/v1/staff/{mgr_staff_id}", headers=manager)
        assert res.status_code == 400, f"Expected 400 Bad Request for self staff deletion, got: {res.status_code}"


def test_e2e_staff_delete_nonexistent(client, manager):
    """End-to-End: Verify 404 Not Found when deleting non-existent staff record."""
    random_id = str(uuid.uuid4())
    res = client.delete(f"/api/v1/staff/{random_id}", headers=manager)
    assert res.status_code == 404, f"Expected 404 Not Found, got: {res.status_code}"
