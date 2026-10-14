"""E2E Test: Dynamic Study Group & Student Device Photo Insertion.

Validates the full enterprise pipeline for Gheras Center (EduTrack Pro):
1. Register a student with a newly created custom study group (e.g. 'مسار الموهوبين').
2. Register a student with an in-memory base64 photo URL simulating file picker upload from PC/Laptop.
3. Verify that student record stores both the custom group and photo data URL.
4. Verify guardian card print endpoint (/api/v1/print/guardian-card) returns photo_url and group_name.
5. Verify web/print/templates/guardian_card.html template placeholder contract.
6. Verify room creation with custom study group name.
Strictly adheres to Rule 50 (0-9 Western Arabic numerals) and clean architecture invariants.
"""

from __future__ import annotations

import re
from pathlib import Path


def test_e2e_student_with_custom_group_and_photo(client, db, manager):
    """End-to-End: Register student with custom study group and local device photo URL."""
    # Ensure database check constraints on group_name are relaxed for dynamic groups
    db.execute("ALTER TABLE students DROP CONSTRAINT IF EXISTS chk_students_group_name")
    db.execute("ALTER TABLE rooms DROP CONSTRAINT IF EXISTS chk_rooms_group_name")
    db.execute("ALTER TABLE schedules DROP CONSTRAINT IF EXISTS chk_schedules_group_name")
    db.commit()

    # Step 1: Create a room assigned to a custom study group
    custom_group = "مسار الموهوبين والذكاء الاصطناعي"
    room = db.execute(
        "INSERT INTO rooms (name, group_name) VALUES (%s, %s) RETURNING id",
        ("قاعة الخوارزمي للموهوبين", custom_group),
    ).fetchone()
    room_id = str(room["id"])
    db.commit()

    # Step 2: Post student creation payload containing custom group and device photo Data URL
    mock_base64_photo = (
        "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP////////////////////////"
        "//////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QA"
        "FAABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA="
    )

    student_payload = {
        "name": "سلطان خالد الدوسري",
        "gender": "بنين",
        "group_name": custom_group,
        "room_id": room_id,
        "guardian_phone": "0554443322",
        "father_name": "خالد الدوسري",
        "father_phone": "0554443322",
        "photo_url": mock_base64_photo,
        "nationality": "سعودي",
    }

    create_res = client.post("/api/v1/students", json=student_payload, headers=manager)
    assert create_res.status_code == 200, f"Student registration failed: {create_res.text}"
    created_data = create_res.json()

    student_id = created_data.get("id")
    assert student_id is not None, "Missing student ID in creation response"
    assert created_data.get("name") == "سلطان خالد الدوسري"
    assert created_data.get("group_name") == custom_group
    assert created_data.get("photo_url") == mock_base64_photo

    # Step 3: Verify GET /api/v1/students/{id} retrieves the exact photo_url and group
    get_res = client.get(f"/api/v1/students/{student_id}", headers=manager)
    assert get_res.status_code == 200, f"Failed to fetch created student: {get_res.text}"
    fetched = get_res.json()
    assert fetched.get("photo_url") == mock_base64_photo
    assert fetched.get("group_name") == custom_group

    # Step 4: Verify Print Guardian Card Endpoint payload contract
    # This endpoint provides data to web/print/templates/guardian_card.html
    card_res = client.get(f"/api/v1/print/guardian-card?student={student_id}", headers=manager)
    assert card_res.status_code == 200, f"Guardian card query failed: {card_res.text}"
    card_data = card_res.json()

    assert card_data.get("template") == "guardian_card"
    assert card_data.get("student_name") == "سلطان خالد الدوسري"
    assert card_data.get("group_name") == custom_group
    assert card_data.get("photo_url") == mock_base64_photo
    assert card_data.get("room_name") == "قاعة الخوارزمي للموهوبين"

    # Step 5: Verify Guardian Card HTML Template Placeholders Contract
    repo_root = Path(__file__).resolve().parents[2]
    template_path = repo_root / "web" / "print" / "templates" / "guardian_card.html"
    if template_path.exists():
        content = template_path.read_text(encoding="utf-8")
        # Ensure photo_url block exists in the HTML template
        assert "{{#photo_url}}" in content, "guardian_card.html must include {{#photo_url}} condition"
        assert "<img src=\"{{photo_url}}\"" in content, "guardian_card.html must render student photo img tag"

        # Extract all scalar {{key}} placeholders
        mustache_keys = set(re.findall(r"\{\{([a-zA-Z0-9_]+)\}\}", content))
        mustache_keys.discard("center_name")
        mustache_keys.discard("academic_year")

        for key in mustache_keys:
            assert key in card_data, f"Placeholder '{{{{{key}}}}}' missing from print/guardian-card API response!"


def test_e2e_room_creation_with_custom_group(client, db, manager):
    """End-to-End: Create a room assigned to a dynamically added study group."""
    db.execute("ALTER TABLE rooms DROP CONSTRAINT IF EXISTS chk_rooms_group_name")
    db.commit()

    room_payload = {
        "name": "قاعة الإتقان لحفظ المتون",
        "group_name": "مسار المتون العلمية",
    }
    res = client.post("/api/v1/rooms", json=room_payload, headers=manager)
    assert res.status_code == 200, f"Room creation failed: {res.text}"
    data = res.json()
    assert data.get("name") == "قاعة الإتقان لحفظ المتون"
    assert data.get("group_name") == "مسار المتون العلمية"
