"""Student photo private storage: upload, scoped read, delete, CRUD guard, legacy migration."""

from __future__ import annotations

import pytest

from tests.conftest import _guardian, _room, _student, _teacher, login

JPEG = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00" + b"\x00" * 200
PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 200


@pytest.fixture(autouse=True)
def photo_dir(tmp_path, monkeypatch):
    d = tmp_path / "student_photos"
    monkeypatch.setenv("STUDENT_PHOTO_DIR", str(d))
    return d


def _upload(client, headers, sid, data, ctype="image/jpeg"):
    return client.post(f"/api/v1/students/{sid}/photo", content=data, headers={**headers, "Content-Type": ctype})


def _files(d, sid):
    return sorted(p.name for p in d.glob(f"{sid}_*")) if d.exists() else []


def _photo_url(db, sid):
    return db.execute("SELECT photo_url FROM students WHERE id = %s", (sid,)).fetchone()["photo_url"]


def _supervisor_with_students(client, manager):
    res = client.post("/api/v1/users", json={
        "username": "sup_photos", "password": "Password123!", "role": "supervisor",
        "permissions": {"students": True, "attendance": False, "daily_evaluation": False,
                        "monthly_evaluation": False, "finance": False},
    }, headers=manager)
    assert res.status_code == 200, res.text
    return login(client, "sup_photos", "Password123!")


def test_upload_jpeg_sets_versioned_url_and_writes_one_file(client, db, manager, photo_dir):
    sid = _student(client, manager, "طالب 1", None)
    res = _upload(client, manager, sid, JPEG)
    assert res.status_code == 200, res.text
    url = res.json()["photo_url"]
    assert url.startswith(f"/api/v1/students/{sid}/photo?v=") and len(url.split("?v=")[1]) == 8
    assert _photo_url(db, sid) == url
    names = _files(photo_dir, sid)
    assert len(names) == 1 and names[0].endswith(".jpg")
    audit = db.execute("SELECT count(*) n FROM audit_log WHERE action = 'student_photo_set' AND entity_id = %s", (sid,)).fetchone()
    assert audit["n"] == 1


def test_html_disguised_as_jpeg_is_rejected(client, manager, photo_dir):
    sid = _student(client, manager, "طالب 2", None)
    res = _upload(client, manager, sid, b"<html><script>alert(1)</script></html>", "image/jpeg")
    assert res.status_code == 422, res.text
    assert _files(photo_dir, sid) == []


def test_oversize_is_rejected_and_leaves_no_file(client, manager, photo_dir):
    sid = _student(client, manager, "طالب 3", None)
    res = _upload(client, manager, sid, JPEG + b"\x00" * (2 * 1024 * 1024))
    assert res.status_code == 413, res.text
    assert _files(photo_dir, sid) == []


def test_replacing_keeps_only_the_new_file_and_changes_version(client, manager, photo_dir):
    sid = _student(client, manager, "طالب 4", None)
    first = _upload(client, manager, sid, JPEG).json()["photo_url"]
    second = _upload(client, manager, sid, PNG, "image/png").json()["photo_url"]
    assert first != second
    names = _files(photo_dir, sid)
    assert len(names) == 1 and names[0].endswith(".png")


def test_get_returns_file_for_manager_with_private_cache(client, manager):
    sid = _student(client, manager, "طالب 5", None)
    _upload(client, manager, sid, JPEG)
    res = client.get(f"/api/v1/students/{sid}/photo", headers=manager)
    assert res.status_code == 200
    assert res.content == JPEG
    assert res.headers["content-type"] == "image/jpeg"
    assert res.headers["cache-control"] == "private, max-age=86400"


def test_get_is_scoped_for_guardians_and_teachers(client, db, manager):
    room_a = _room(client, manager, "قاعة أ")
    room_b = _room(client, manager, "قاعة ب")
    mine = _student(client, manager, "ابني", room_a)
    other = _student(client, manager, "غيره", room_b)
    for sid in (mine, other):
        _upload(client, manager, sid, JPEG)
    _, guardian = _guardian(db, client, "g_photo", [mine])
    _, teacher_a = _teacher(db, client, "t_photo", room_a)
    assert client.get(f"/api/v1/students/{mine}/photo", headers=guardian).status_code == 200
    assert client.get(f"/api/v1/students/{other}/photo", headers=guardian).status_code == 403
    assert client.get(f"/api/v1/students/{mine}/photo", headers=teacher_a).status_code == 200
    assert client.get(f"/api/v1/students/{other}/photo", headers=teacher_a).status_code == 403


def test_supervisor_needs_students_permission(client, manager, supervisor):
    sid = _student(client, manager, "طالب 6", None)
    assert _upload(client, supervisor, sid, JPEG).status_code == 403
    assert client.get(f"/api/v1/students/{sid}/photo", headers=supervisor).status_code == 403
    allowed = _supervisor_with_students(client, manager)
    assert _upload(client, allowed, sid, JPEG).status_code == 200
    assert client.get(f"/api/v1/students/{sid}/photo", headers=allowed).status_code == 200


def test_guardian_cannot_upload_or_delete(client, db, manager):
    sid = _student(client, manager, "طالب 7", None)
    _, guardian = _guardian(db, client, "g_write", [sid])
    assert _upload(client, guardian, sid, JPEG).status_code == 403
    assert client.delete(f"/api/v1/students/{sid}/photo", headers=guardian).status_code == 403


def test_delete_clears_file_and_url_and_is_repeatable(client, db, manager, photo_dir):
    sid = _student(client, manager, "طالب 8", None)
    _upload(client, manager, sid, JPEG)
    assert client.delete(f"/api/v1/students/{sid}/photo", headers=manager).status_code == 204
    assert _photo_url(db, sid) is None
    assert _files(photo_dir, sid) == []
    assert client.get(f"/api/v1/students/{sid}/photo", headers=manager).status_code == 404
    assert client.delete(f"/api/v1/students/{sid}/photo", headers=manager).status_code == 204


def test_missing_or_deleted_student_is_404(client, manager):
    assert _upload(client, manager, "00000000-0000-0000-0000-0000000000aa", JPEG).status_code == 404
    sid = _student(client, manager, "طالب 9", None)
    assert client.delete(f"/api/v1/students/{sid}", headers=manager).status_code in (200, 204)
    assert _upload(client, manager, sid, JPEG).status_code == 404
    assert client.get(f"/api/v1/students/{sid}/photo", headers=manager).status_code == 404


def test_crud_ignores_photo_url_on_create_and_patch(client, db, manager):
    res = client.post("/api/v1/students", json={
        "name": "طالب 10", "guardian_phone": "0500000000", "gender": "بنين",
        "photo_url": "https://evil.example/x.jpg",
    }, headers=manager)
    assert res.status_code == 200, res.text
    sid = res.json()["id"]
    assert _photo_url(db, sid) is None
    url = _upload(client, manager, sid, JPEG).json()["photo_url"]
    res = client.patch(f"/api/v1/students/{sid}", json={"photo_url": "data:image/jpeg;base64,AAAA"}, headers=manager)
    assert res.status_code == 200, res.text
    assert _photo_url(db, sid) == url


def test_photo_is_not_served_as_static_upload(client, manager, photo_dir):
    sid = _student(client, manager, "طالب 11", None)
    _upload(client, manager, sid, JPEG)
    name = _files(photo_dir, sid)[0]
    assert client.get(f"/api/v1/static/uploads/{name}").status_code == 404
    assert client.get(f"/static/uploads/{name}").status_code == 404


def test_migration_is_idempotent_and_skips_non_images(client, db, manager, photo_dir):
    import base64

    from edutrack_api.migrate_student_photos import migrate_legacy_photos

    good = _student(client, manager, "قديم صالح", None)
    bad = _student(client, manager, "قديم تالف", None)
    legacy = "data:image/jpeg;base64," + base64.b64encode(JPEG).decode()
    db.execute("UPDATE students SET photo_url = %s WHERE id = %s", (legacy, good))
    db.execute("UPDATE students SET photo_url = %s WHERE id = %s", ("data:text/html;base64,PGh0bWw+", bad))
    db.commit()

    first = migrate_legacy_photos(db)
    db.commit()
    assert first["moved"] == 1 and first["skipped"] == [bad]
    assert _photo_url(db, good).startswith(f"/api/v1/students/{good}/photo?v=")
    assert len(_files(photo_dir, good)) == 1
    assert _photo_url(db, bad) == "data:text/html;base64,PGh0bWw+"
    assert _files(photo_dir, bad) == []

    second = migrate_legacy_photos(db)
    db.commit()
    assert second == {"moved": 0, "skipped": [bad]}
    assert len(_files(photo_dir, good)) == 1
    res = client.get(f"/api/v1/students/{good}/photo", headers=manager)
    assert res.status_code == 200 and res.content == JPEG
