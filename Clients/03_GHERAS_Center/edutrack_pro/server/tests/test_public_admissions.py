"""Tests for public admissions and website programs endpoint."""

from __future__ import annotations


def test_get_public_programs(client):
    res = client.get("/api/v1/public/programs")
    assert res.status_code == 200, res.text
    programs = res.json()
    assert isinstance(programs, list)
    assert len(programs) >= 4
    prog_ids = [p["id"] for p in programs]
    assert "foundation" in prog_ids
    assert "qudurat" in prog_ids
    assert "english" in prog_ids
    assert "mental-math" in prog_ids


def test_submit_admission_lead_valid(client):
    payload = {
        "student_name": "سعد محمد التميمي",
        "grade_level": "الصف الأول المتوسط",
        "program_name": "برنامج القدرات العامة والتحصيلي",
        "guardian_name": "محمد التميمي",
        "phone": "0551234567",
        "district": "حوطة بني تميم - حي السلام",
        "source": "landing_page",
        "notes": "يرغب في الفترة المسائية",
    }
    res = client.post("/api/v1/public/admissions", json=payload)
    assert res.status_code == 201, res.text
    data = res.json()
    assert data["status"] == "success"
    assert data["booking_ref"].startswith("GHR-2026-")
    assert data["student_name"] == "سعد محمد التميمي"
    assert "whatsapp_confirmation_url" in data
    assert "wa.me" in data["whatsapp_confirmation_url"]


def test_submit_admission_lead_digit_normalization(client):
    # Eastern Arabic numerals input
    payload = {
        "student_name": "عبدالله إبراهيم",
        "grade_level": "التمهيدي",
        "program_name": "برنامج التأسيس الشامل",
        "guardian_name": "إبراهيم عبدالله",
        "phone": "٠٥٩٨٧٦٥٤٣٢",  # 0598765432 in eastern digits
        "district": "الحلوة",
        "source": "website",
    }
    res = client.post("/api/v1/public/admissions", json=payload)
    assert res.status_code == 201, res.text
    data = res.json()
    assert data["status"] == "success"
    assert data["booking_ref"].startswith("GHR-2026-")


def test_submit_admission_lead_invalid_phone_rejected(client):
    payload = {
        "student_name": "فهد السليمان",
        "grade_level": "الصف الثالث الابتدائي",
        "program_name": "برنامج التأسيس الشامل",
        "guardian_name": "سليمان الفهد",
        "phone": "0112345678",  # Not 05xx
        "district": "حوطة بني تميم",
    }
    res = client.post("/api/v1/public/admissions", json=payload)
    assert res.status_code == 422
    err = res.json()
    assert err.get("code") == "invalid_phone"
