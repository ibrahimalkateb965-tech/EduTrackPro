"""Student photo reaches every printed document that shows the student."""

from __future__ import annotations

PHOTO = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//wgALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA="


def _student(client, manager, photo=None):
    body = {"name": "طالب الصورة", "gender": "بنين", "group_name": "المرحلة الابتدائية", "guardian_phone": "0554443322"}
    if photo:
        body["photo_url"] = photo
    res = client.post("/api/v1/students", json=body, headers=manager)
    assert res.status_code == 200, res.text
    return res.json()["id"]


def test_photo_in_card_report_and_certificate_by_student(client, manager):
    sid = _student(client, manager, PHOTO)
    for path in ("guardian-card", "student-report", "certificate"):
        res = client.get(f"/api/v1/print/{path}?student_id={sid}", headers=manager)
        assert res.status_code == 200, (path, res.text)
        assert res.json()["photo_url"] == PHOTO, path


def test_photo_in_certificate_by_certificate_id(client, db, manager):
    sid = _student(client, manager, PHOTO)
    cert = db.execute(
        "INSERT INTO certificates (student_id, title, reason, issued_on) VALUES (%s, %s, %s, CURRENT_DATE) RETURNING id",
        (sid, "شهادة تميز", "تفوق"),
    ).fetchone()
    db.commit()
    res = client.get(f"/api/v1/print/certificate?certificate_id={cert['id']}", headers=manager)
    assert res.status_code == 200, res.text
    assert res.json()["photo_url"] == PHOTO


def test_no_photo_gives_empty_string(client, manager):
    sid = _student(client, manager)
    for path in ("guardian-card", "student-report", "certificate"):
        res = client.get(f"/api/v1/print/{path}?student_id={sid}", headers=manager)
        assert res.status_code == 200, (path, res.text)
        assert res.json()["photo_url"] == "", path
