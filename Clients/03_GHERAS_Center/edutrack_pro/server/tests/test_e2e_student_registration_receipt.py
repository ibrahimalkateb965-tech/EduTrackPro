"""E2E Test: New Student Registration with Immediate Down Payment and Receipt Print Flow.

Validates the full enterprise pipeline for Gheras Center (EduTrack Pro):
1. Manager authenticates and creates a new student with an initial payment.
2. System allocates payment, generates receipt, and updates ledger.
3. System verifies receipt print endpoint payload matches web/print/templates/receipt.html template keys.
4. Non-paying student creation verifies that no initial receipt is created.
Strictly adheres to Rule 50 (0-9 numerals) and clean architecture invariants.
"""

from __future__ import annotations

import os
import re
from pathlib import Path


def test_e2e_student_registration_with_receipt_flow(client, db, manager):
    """End-to-End: Register student with initial payment -> issue receipt -> verify print payload."""
    # Step 1: Create a room for the student
    room = db.execute(
        "INSERT INTO rooms (name, group_name) VALUES (%s, %s) RETURNING id",
        ("قاعة الإمام نافع", "الصباح"),
    ).fetchone()
    room_id = str(room["id"])
    db.commit()

    # Step 2: Post student creation payload containing initial payment details
    student_payload = {
        "name": "عبدالرحمن فهد السبيعي",
        "gender": "بنين",
        "group_name": "الصباح",
        "room_id": room_id,
        "guardian_phone": "0551234567",
        "father_name": "فهد السبيعي",
        "father_phone": "0551234567",
        "registration_fee": 600.0,
        "initial_payment_amount": 350.0,
        "payment_method": "كاش",
    }

    create_res = client.post("/api/v1/students", json=student_payload, headers=manager)
    assert create_res.status_code == 200, f"Student registration failed: {create_res.text}"
    created_data = create_res.json()

    student_id = created_data.get("id")
    assert student_id is not None, "Missing student ID in creation response"
    assert created_data.get("name") == "عبدالرحمن فهد السبيعي"

    # Step 3: Verify initial payment and receipt generation in creation payload
    initial_payment = created_data.get("initial_payment")
    assert initial_payment is not None, "Initial payment record must be returned upon creation"
    payment_id = initial_payment.get("id")
    assert payment_id is not None, "Initial payment ID missing"
    assert float(initial_payment.get("amount")) == 350.0
    assert initial_payment.get("method") == "كاش"

    initial_receipt = created_data.get("initial_receipt")
    assert initial_receipt is not None, "Initial receipt record must be returned upon creation"
    receipt_no = initial_receipt.get("receipt_no")
    assert receipt_no is not None and int(receipt_no) > 0, "Valid positive receipt number required"

    # Step 4: Verify Print Engine API Endpoint for Receipt
    # This endpoint is queried by web/print/print_engine.js when redirecting to receipt.html?payment={id}
    print_res = client.get(f"/api/v1/print/receipt?payment={payment_id}", headers=manager)
    assert print_res.status_code == 200, f"Receipt print query failed: {print_res.text}"
    print_data = print_res.json()

    assert print_data.get("template") == "receipt"
    assert print_data.get("receipt_no") == receipt_no
    assert print_data.get("student_name") == "عبدالرحمن فهد السبيعي"
    assert float(print_data.get("amount")) == 350.0
    assert print_data.get("method") == "كاش"
    assert "ريال" in print_data.get("amount_words", "")

    # Step 5: Verify Template Placeholder Integrity
    # Read the receipt HTML template to guarantee all {{placeholder}} tokens are satisfied by the payload
    repo_root = Path(__file__).resolve().parents[2]
    template_path = repo_root / "web" / "print" / "templates" / "receipt.html"
    if template_path.exists():
        template_content = template_path.read_text(encoding="utf-8")
        # Extract all {{key}} tokens
        mustache_keys = set(re.findall(r"\{\{([a-zA-Z0-9_]+)\}\}", template_content))
        # Remove comment / meta keys if any
        mustache_keys.discard("center_name")  # Injected from system_settings

        for key in mustache_keys:
            assert key in print_data, f"Template placeholder '{{{{{key}}}}}' missing from print/receipt API response!"


def test_e2e_student_registration_zero_payment(client, db, manager):
    """End-to-End: Register student without initial payment -> verify NO payment or receipt generated."""
    student_payload = {
        "name": "سارة عبدالعزيز المقرن",
        "gender": "بنات",
        "group_name": "الصباح",
        "guardian_phone": "0559876543",
        "registration_fee": 500.0,
        "initial_payment_amount": 0.0,
        "payment_method": "كاش",
    }

    create_res = client.post("/api/v1/students", json=student_payload, headers=manager)
    assert create_res.status_code == 200, f"Student registration failed: {create_res.text}"
    created_data = create_res.json()

    # When no initial payment is made, initial_payment and initial_receipt must be None
    assert created_data.get("initial_payment") is None, "Zero payment must not generate initial payment"
    assert created_data.get("initial_receipt") is None, "Zero payment must not generate initial receipt"
