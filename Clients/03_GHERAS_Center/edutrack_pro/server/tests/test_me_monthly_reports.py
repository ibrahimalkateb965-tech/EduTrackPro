"""API integration tests for Guardian Monthly Reports & Anti-Draft Invariants (Batch S5-B4)."""

from __future__ import annotations

import uuid
from datetime import date

import pytest

from edutrack_api.auth import issue_token
from edutrack_api.config import get_settings


def _auth_header(user_id: str | uuid.UUID, role: str) -> dict[str, str]:
    token = issue_token({"id": str(user_id), "role": role, "token_version": 1}, get_settings())
    return {"Authorization": f"Bearer {token}"}


def _seed_guardian_fixture(db):
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]

    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة الرياحين', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]

    # Teacher
    teacher_id = db.execute(
        "INSERT INTO users (username, role, password_hash, room_id) VALUES ('teacher_m5_me', 'teacher', 'hash', %s) RETURNING id",
        (room_id,)
    ).fetchone()["id"]

    # Student 1 (belongs to Guardian 1)
    student1_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'خالد وليد') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]

    # Guardian 1
    g1_rec = db.execute(
        "INSERT INTO guardians (branch_id, name, phone) VALUES (%s, 'وليد والد خالد', '0551113344') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]

    db.execute(
        "INSERT INTO student_guardians (branch_id, student_id, guardian_id, is_primary) VALUES (%s, %s, %s, TRUE)",
        (branch_id, student1_id, g1_rec)
    )

    g1_user_id = db.execute(
        "INSERT INTO users (username, role, password_hash, guardian_id) VALUES ('user_g1_m5', 'guardian', 'hash', %s) RETURNING id",
        (g1_rec,)
    ).fetchone()["id"]

    # Student 2 (belongs to Guardian 2 - foreign)
    student2_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'عائشة فهد') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]

    g2_rec = db.execute(
        "INSERT INTO guardians (branch_id, name, phone) VALUES (%s, 'فهد والد عائشة', '0552224455') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]

    db.execute(
        "INSERT INTO student_guardians (branch_id, student_id, guardian_id, is_primary) VALUES (%s, %s, %s, TRUE)",
        (branch_id, student2_id, g2_rec)
    )

    g2_user_id = db.execute(
        "INSERT INTO users (username, role, password_hash, guardian_id) VALUES ('user_g2_m5', 'guardian', 'hash', %s) RETURNING id",
        (g2_rec,)
    ).fetchone()["id"]

    # Supervisor
    supervisor_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('supervisor_m5_me', 'supervisor', 'hash') RETURNING id"
    ).fetchone()["id"]

    db.commit()
    return {
        "branch_id": branch_id,
        "room_id": room_id,
        "teacher_id": teacher_id,
        "supervisor_id": supervisor_id,
        "student1_id": student1_id,
        "student2_id": student2_id,
        "g1_user_id": g1_user_id,
        "g2_user_id": g2_user_id,
    }


def test_guardian_monthly_reports_anti_draft_invariants(client, db):
    """Confirm guardian sees 0 reports in draft, pending, revision, or approved, and sees only published reports."""
    ctx = _seed_guardian_fixture(db)
    g1_headers = _auth_header(ctx["g1_user_id"], "guardian")
    t_headers = _auth_header(ctx["teacher_id"], "teacher")
    s_headers = _auth_header(ctx["supervisor_id"], "supervisor")

    # 1. Initially no reports exist
    res = client.get(f"/api/v1/me/monthly-reports?student_id={ctx['student1_id']}", headers=g1_headers)
    assert res.status_code == 200
    assert res.json() == []

    # 2. Teacher creates draft
    payload = {
        "student_id": str(ctx["student1_id"]),
        "room_id": str(ctx["room_id"]),
        "month": "2026-10",
        "action": "save_draft",
    }
    r = client.post("/api/v1/monthly-reports", json=payload, headers=t_headers).json()
    report_id = r["id"]

    # Draft: must still be empty for guardian
    res_draft = client.get(f"/api/v1/me/monthly-reports?student_id={ctx['student1_id']}", headers=g1_headers)
    assert res_draft.json() == []

    # Fetching single report by id in draft must return 404
    res_single_draft = client.get(f"/api/v1/me/monthly-reports/{report_id}", headers=g1_headers)
    assert res_single_draft.status_code == 404

    # 3. Submit for approval
    payload["action"] = "submit"
    client.post("/api/v1/monthly-reports", json=payload, headers=t_headers)

    # Pending: must still be empty for guardian
    res_pending = client.get(f"/api/v1/me/monthly-reports?student_id={ctx['student1_id']}", headers=g1_headers)
    assert res_pending.json() == []

    # 4. Supervisor requests revision
    client.post(
        f"/api/v1/monthly-reports/{report_id}/review",
        json={"action": "request_revision", "revision_notes": "يرجى تعديل التقييم"},
        headers=s_headers,
    )
    # Revision: must still be empty
    assert client.get(f"/api/v1/me/monthly-reports?student_id={ctx['student1_id']}", headers=g1_headers).json() == []

    # 5. Teacher re-submits and Supervisor approves
    client.post("/api/v1/monthly-reports", json=payload, headers=t_headers)
    client.post(f"/api/v1/monthly-reports/{report_id}/review", json={"action": "approve"}, headers=s_headers)

    # Approved (not published): must still be empty
    assert client.get(f"/api/v1/me/monthly-reports?student_id={ctx['student1_id']}", headers=g1_headers).json() == []

    # 6. Supervisor publishes
    res_pub = client.post(f"/api/v1/monthly-reports/{report_id}/publish", headers=s_headers)
    assert res_pub.status_code == 200

    # Published: Now guardian sees the report!
    res_published = client.get(f"/api/v1/me/monthly-reports?student_id={ctx['student1_id']}", headers=g1_headers)
    assert res_published.status_code == 200
    reports = res_published.json()
    assert len(reports) == 1
    assert reports[0]["id"] == report_id
    assert reports[0]["status"] == "published"

    # Single report fetch
    res_single = client.get(f"/api/v1/me/monthly-reports/{report_id}", headers=g1_headers)
    assert res_single.status_code == 200
    assert res_single.json()["id"] == report_id

    # 7. Foreign guardian receives 403
    g2_headers = _auth_header(ctx["g2_user_id"], "guardian")
    res_foreign = client.get(f"/api/v1/me/monthly-reports?student_id={ctx['student1_id']}", headers=g2_headers)
    assert res_foreign.status_code == 403


def test_monthly_report_pdf_export_endpoint(client, db):
    """Test GET /api/v1/monthly-reports/{report_id}/pdf."""
    ctx = _seed_guardian_fixture(db)
    g1_headers = _auth_header(ctx["g1_user_id"], "guardian")
    t_headers = _auth_header(ctx["teacher_id"], "teacher")
    s_headers = _auth_header(ctx["supervisor_id"], "supervisor")

    # Create, approve, and publish report
    payload = {
        "student_id": str(ctx["student1_id"]),
        "room_id": str(ctx["room_id"]),
        "month": "2026-10",
        "action": "submit",
    }
    r = client.post("/api/v1/monthly-reports", json=payload, headers=t_headers).json()
    report_id = r["id"]

    # Before publishing, guardian gets 404
    res_pre = client.get(f"/api/v1/monthly-reports/{report_id}/pdf", headers=g1_headers)
    assert res_pre.status_code == 404

    # Publish
    client.post(f"/api/v1/monthly-reports/{report_id}/review", json={"action": "approve"}, headers=s_headers)
    client.post(f"/api/v1/monthly-reports/{report_id}/publish", headers=s_headers)

    # After publishing, guardian receives valid PDF metadata
    res_pdf = client.get(f"/api/v1/monthly-reports/{report_id}/pdf", headers=g1_headers)
    assert res_pdf.status_code == 200
    pdf_data = res_pdf.json()
    assert pdf_data["report_id"] == report_id
    assert "pdf_url" in pdf_data
    assert pdf_data["status"] == "published"
