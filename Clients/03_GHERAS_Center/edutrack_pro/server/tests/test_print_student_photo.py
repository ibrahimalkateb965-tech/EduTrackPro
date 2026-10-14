"""Student photo reaches every printed document that shows the student (embedded from the private store)."""

from __future__ import annotations

import base64

import pytest

JPEG = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00" + b"\x00" * 64
DATA_URI = "data:image/jpeg;base64," + base64.b64encode(JPEG).decode()


@pytest.fixture(autouse=True)
def photo_dir(tmp_path, monkeypatch):
    monkeypatch.setenv("STUDENT_PHOTO_DIR", str(tmp_path / "student_photos"))


def _student(client, manager, with_photo=False):
    body = {"name": "طالب الصورة", "gender": "بنين", "group_name": "المرحلة الابتدائية", "guardian_phone": "0554443322"}
    res = client.post("/api/v1/students", json=body, headers=manager)
    assert res.status_code == 200, res.text
    sid = res.json()["id"]
    if with_photo:
        up = client.post(f"/api/v1/students/{sid}/photo", content=JPEG, headers={**manager, "Content-Type": "image/jpeg"})
        assert up.status_code == 200, up.text
    return sid


def test_photo_in_card_report_and_certificate_by_student(client, manager):
    sid = _student(client, manager, with_photo=True)
    for path in ("guardian-card", "student-report", "certificate"):
        res = client.get(f"/api/v1/print/{path}?student_id={sid}", headers=manager)
        assert res.status_code == 200, (path, res.text)
        assert res.json()["photo_url"] == DATA_URI, path


def test_photo_in_certificate_by_certificate_id(client, db, manager):
    sid = _student(client, manager, with_photo=True)
    cert = db.execute(
        "INSERT INTO certificates (student_id, title, reason, issued_on) VALUES (%s, %s, %s, CURRENT_DATE) RETURNING id",
        (sid, "شهادة تميز", "تفوق"),
    ).fetchone()
    db.commit()
    res = client.get(f"/api/v1/print/certificate?certificate_id={cert['id']}", headers=manager)
    assert res.status_code == 200, res.text
    assert res.json()["photo_url"] == DATA_URI


def test_no_photo_gives_empty_string(client, manager):
    sid = _student(client, manager)
    for path in ("guardian-card", "student-report", "certificate"):
        res = client.get(f"/api/v1/print/{path}?student_id={sid}", headers=manager)
        assert res.status_code == 200, (path, res.text)
        assert res.json()["photo_url"] == "", path


def test_external_url_in_db_is_never_printed(client, db, manager):
    sid = _student(client, manager)
    db.execute("UPDATE students SET photo_url = 'https://evil.example/x.jpg' WHERE id = %s", (sid,))
    db.commit()
    res = client.get(f"/api/v1/print/guardian-card?student_id={sid}", headers=manager)
    assert res.status_code == 200, res.text
    assert res.json()["photo_url"] == ""
