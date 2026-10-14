# Student Photo Private Storage — Implementation Plan (first agy fleet order)

> **For agentic workers:** this plan is executed as **fleet order 08** through `D:\FleetProfiles\agy-worker.ps1`. Claude Code writes the tests (Task 0) and runs the quality gate (Task 5). Workers W1–W4 each own exactly one task, edit only the files listed in it, and **run no commands at all** (no tests, no compilers, no git, no servers). Steps use checkbox (`- [ ]`) syntax.

**Goal:** Move student photos out of `students.photo_url` (base64 data URLs today) into a private, authenticated file store, so `/students` stays small, photos stay out of public static files, and every printed document still shows the photo.

**Architecture:** A new service writes one validated JPEG/PNG file per student into `STUDENT_PHOTO_DIR` (outside `UPLOAD_DIR`, never mounted as static). A new router exposes `POST/GET/DELETE /api/v1/students/{id}/photo`; `photo_url` becomes a short server-written URL with a cache-busting `?v=`. Print endpoints embed the file as a `data:` URI under the existing `photo_url` key, so the print templates do not change. A one-off idempotent module moves today's base64 rows into files during deploy.

**Tech Stack:** FastAPI + psycopg 3 (sync handlers, one async upload handler), PostgreSQL 16, vanilla ES modules dashboard, Docker Compose on the VPS, bash.

**Spec:** `docs/superpowers/specs/2026-10-14-student-photo-design.md` (commit `78d42c1`). Deviations are listed below; the spec stays the design reference.

## Deviations from the spec (decided while planning, 2026-10-14)

1. **Print key stays `photo_url`.** Commit `47cd020` already ships templates and `print_engine.js` that render `{{#photo_url}}…{{/photo_url}}`. The print payload keeps that key but fills it with the data URI from the file. No template or engine edits.
2. **Legacy base64 rows exist in production** (Antigravity's picker stored `data:image/jpeg;base64,…` in `photo_url`). New module `edutrack_api/migrate_student_photos.py` moves them into files; `deploy.sh` runs it after the API is healthy. It is idempotent.
3. **Backups.** `backup.sh` only runs `pg_dump`. Today photos are inside the dump; after this change they would not be. `backup.sh` gains a verified `tar.gz` of the photo volume with the same rotation.
4. **Container permissions.** The image creates only `/app/uploads` for the non-root `edutrack` user. A new named volume at `/app/student_photos` would be root-owned and every upload would fail in production. The Dockerfile creates and chowns `/app/student_photos`; `deploy.sh` proves the API can write there.
5. **Android is out of scope** (it does not sync student photos today; its photo code is homework capture).

## Global Constraints

- Allowed photo types: JPEG (`FF D8 FF`) and PNG (`89 50 4E 47 0D 0A 1A 0A`), detected from bytes, never from the file name or `Content-Type`.
- Size cap: 2 MiB (`2 * 1024 * 1024`), checked on `Content-Length` and again while streaming.
- Storage: `STUDENT_PHOTO_DIR` env, default `student_photos`; production `/app/student_photos` on volume `gheras_student_photos`. File name `{student_id}_{uuid4.hex}.{jpg|png}`; one file per student.
- `photo_url` is written only by the server: `/api/v1/students/{id}/photo?v={first 8 hex of the uuid}`. Generic CRUD silently drops `photo_url` on `students` POST/PATCH.
- GET access: manager; supervisor with `students` permission; teacher/guardian/student only for ids in their `Scope.student_ids`. POST/DELETE: manager or supervisor with `students` permission.
- `Cache-Control: private, max-age=86400` on GET.
- Audit: `student_photo_set` / `student_photo_delete` on entity `students`, details `{}`.
- Error messages Arabic via `ApiError`; 413 too large, 422 wrong type, 404 missing/deleted student or no photo, 403 no permission.
- Rule 50: Western digits only. Rule 53: explicit allow-list + explicit MIME, no scriptable types. No new dependencies.
- Dashboard cache tag moves `v=3.3` → `v=3.4` in `index.html` **and** `app.js` together.

## Review Focus

1. **Production volume ownership** — first upload after deploy must not 500 because `/app/student_photos` is root-owned. Pinned by the `deploy.sh` write probe (Task 3) and the Dockerfile chown.
2. **Migration run twice / partially** — a second run must move nothing and touch no file. Pinned by `test_migration_is_idempotent_and_skips_non_images` (Task 0).
3. **Stale dashboard tab still posting `photo_url`** — an old cached form must not write a data URL back. Pinned by `test_crud_ignores_photo_url_on_create_and_patch` (Task 0) + cache tag bump (Task 4).
4. **Editing a student without touching the photo** must not delete or re-upload it. Pinned by the Task 5 browser check (edit → save → photo still present, no DELETE request).
5. **Photo backup and restore** — a nightly run must produce a readable archive. Pinned by `tar -tzf` in `backup.sh` (Task 3) and the deploy-time backup smoke that `deploy.sh` already runs.

---

### Task 0: Acceptance tests (Claude Code — before any dispatch)

**Files:**
- Create: `server/tests/test_student_photos.py`
- Modify (rewrite): `server/tests/test_print_student_photo.py` (it posts `photo_url` through CRUD, which this plan forbids)

**Interfaces:**
- Consumes (written by Tasks 1 and 2): `POST/GET/DELETE /api/v1/students/{id}/photo`, `edutrack_api.migrate_student_photos.migrate_legacy_photos(conn) -> dict`.

- [ ] **Step 1: Write `server/tests/test_student_photos.py`**

```python
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
```

- [ ] **Step 2: Rewrite `server/tests/test_print_student_photo.py`**

```python
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
```

- [ ] **Step 3: Run and confirm RED**

Run (booter alive, see `CURRENT_STATE.md` §5n harness): `server/.venv/Scripts/python.exe -m pytest tests/test_student_photos.py tests/test_print_student_photo.py -q`
Expected: every new test fails (404 on `/students/{id}/photo`, `ModuleNotFoundError: edutrack_api.migrate_student_photos`, print returns the old DB value). Then the full suite minus these two files must still be green (regression baseline).

- [ ] **Step 4: Commit the tests**

```bash
git add Clients/03_GHERAS_Center/edutrack_pro/server/tests/test_student_photos.py Clients/03_GHERAS_Center/edutrack_pro/server/tests/test_print_student_photo.py
git commit -m "test(edutrack-photo): acceptance tests for private student photo storage (RED)"
```

---

### Task 1: Photo service + router (Worker W2 — backend lane)

**Files:**
- Create: `server/edutrack_api/services/student_photos.py`
- Create: `server/edutrack_api/routers/student_photos.py`
- Modify: `server/edutrack_api/main.py` (import line 18, router list lines 54–61)

**Interfaces:**
- Produces (Task 2 imports these names exactly): `MAX_BYTES: int`, `MEDIA_TYPES: dict[str, str]`, `photo_dir() -> Path`, `detect_image_type(data: bytes) -> str | None` (returns `"jpg"`, `"png"` or `None`), `save_photo(student_id, data: bytes) -> str` (8-char version, raises `ValueError` on an unsupported type), `find_photo(student_id) -> Path | None`, `delete_photo(student_id) -> None`, `photo_url_for(student_id, version: str) -> str`, `photo_data_uri(student_id) -> str` (`""` when no photo).

- [ ] **Step 1: Create `server/edutrack_api/services/student_photos.py` with exactly this content**

```python
"""Student photo files: private directory, magic-byte validation, data URIs for printing.

The directory (STUDENT_PHOTO_DIR) is outside UPLOAD_DIR and is never mounted as static
files; photos of minors are served only through the authenticated photo router.
"""

from __future__ import annotations

import base64
import os
import uuid
from pathlib import Path
from uuid import UUID

MAX_BYTES = 2 * 1024 * 1024
MEDIA_TYPES = {"jpg": "image/jpeg", "png": "image/png"}
_MAGIC = ((b"\xff\xd8\xff", "jpg"), (b"\x89PNG\r\n\x1a\n", "png"))


def photo_dir() -> Path:
    return Path(os.getenv("STUDENT_PHOTO_DIR", "student_photos")).resolve()


def detect_image_type(data: bytes) -> str | None:
    for magic, ext in _MAGIC:
        if data.startswith(magic):
            return ext
    return None


def _owned(student_id: UUID | str) -> list[Path]:
    d = photo_dir()
    if not d.is_dir():
        return []
    return [p for p in d.glob(f"{student_id}_*") if p.suffix.lstrip(".") in MEDIA_TYPES]


def find_photo(student_id: UUID | str) -> Path | None:
    files = sorted(_owned(student_id), key=lambda p: p.stat().st_mtime)
    return files[-1] if files else None


def save_photo(student_id: UUID | str, data: bytes) -> str:
    ext = detect_image_type(data)
    if ext is None:
        raise ValueError("unsupported image type")
    d = photo_dir()
    d.mkdir(parents=True, exist_ok=True)
    token = uuid.uuid4().hex
    dest = d / f"{student_id}_{token}.{ext}"
    tmp = d / f".{dest.name}.part"
    tmp.write_bytes(data)
    os.replace(tmp, dest)
    for old in _owned(student_id):
        if old != dest:
            old.unlink(missing_ok=True)
    return token[:8]


def delete_photo(student_id: UUID | str) -> None:
    for old in _owned(student_id):
        old.unlink(missing_ok=True)


def photo_url_for(student_id: UUID | str, version: str) -> str:
    return f"/api/v1/students/{student_id}/photo?v={version}"


def photo_data_uri(student_id: UUID | str) -> str:
    path = find_photo(student_id)
    if path is None:
        return ""
    media = MEDIA_TYPES[path.suffix.lstrip(".")]
    return f"data:{media};base64,{base64.b64encode(path.read_bytes()).decode('ascii')}"
```

- [ ] **Step 2: Create `server/edutrack_api/routers/student_photos.py` with exactly this content**

```python
"""Student photo upload, scoped download and delete (private storage, never static)."""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends, Request, Response
from fastapi.responses import FileResponse

from edutrack_api.audit import write_audit
from edutrack_api.auth import current_user
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.scope import resolve_scope
from edutrack_api.services.student_photos import (
    MAX_BYTES,
    MEDIA_TYPES,
    delete_photo,
    detect_image_type,
    find_photo,
    photo_url_for,
    save_photo,
)

router = APIRouter(prefix="/students")
_USER = Depends(current_user)
_CONN = Depends(get_conn)

MSG_NO_PERMISSION = "ليس لديك صلاحية الوصول إلى سجلات الطلاب"
MSG_TOO_LARGE = "حجم الصورة يتجاوز الحد المسموح (2 ميجابايت)"
MSG_BAD_TYPE = "نوع الصورة غير مسموح. المسموح: JPEG أو PNG"


def _check_staff(user: dict) -> None:
    if user["role"] == "manager":
        return
    if user["role"] == "supervisor" and user.get("permissions", {}).get("students", False):
        return
    raise ApiError(403, "forbidden", MSG_NO_PERMISSION)


def _check_exists(conn, student_id: UUID) -> None:
    row = conn.execute("SELECT 1 FROM students WHERE id = %s AND deleted_at IS NULL", (student_id,)).fetchone()
    if not row:
        raise ApiError(404, "not_found", "الطالب غير موجود")


@router.post("/{student_id}/photo")
async def upload_photo(student_id: UUID, request: Request, user: dict = _USER, conn=_CONN) -> dict:
    _check_staff(user)
    _check_exists(conn, student_id)
    declared = request.headers.get("content-length", "")
    if declared.isdigit() and int(declared) > MAX_BYTES:
        raise ApiError(413, "file_too_large", MSG_TOO_LARGE)
    data = bytearray()
    async for chunk in request.stream():
        data.extend(chunk)
        if len(data) > MAX_BYTES:
            raise ApiError(413, "file_too_large", MSG_TOO_LARGE)
    if detect_image_type(bytes(data)) is None:
        raise ApiError(422, "invalid_file_type", MSG_BAD_TYPE)
    url = photo_url_for(student_id, save_photo(student_id, bytes(data)))
    conn.execute("UPDATE students SET photo_url = %s WHERE id = %s", (url, student_id))
    write_audit(conn, user["id"], "student_photo_set", "students", student_id, {})
    return {"photo_url": url}


@router.get("/{student_id}/photo")
def get_photo(student_id: UUID, user: dict = _USER, conn=_CONN) -> FileResponse:
    if user["role"] in {"manager", "supervisor"}:
        _check_staff(user)
    else:
        resolve_scope(conn, user).assert_students([student_id])
    _check_exists(conn, student_id)
    path = find_photo(student_id)
    if path is None:
        raise ApiError(404, "not_found", "لا توجد صورة لهذا الطالب")
    return FileResponse(
        path,
        media_type=MEDIA_TYPES[path.suffix.lstrip(".")],
        headers={"Cache-Control": "private, max-age=86400"},
    )


@router.delete("/{student_id}/photo", status_code=204)
def remove_photo(student_id: UUID, user: dict = _USER, conn=_CONN) -> Response:
    _check_staff(user)
    _check_exists(conn, student_id)
    delete_photo(student_id)
    conn.execute("UPDATE students SET photo_url = NULL WHERE id = %s", (student_id,))
    write_audit(conn, user["id"], "student_photo_delete", "students", student_id, {})
    return Response(status_code=204)
```

- [ ] **Step 3: Register the router in `server/edutrack_api/main.py`, before `crud.router`**

Line 18 becomes:

```python
from edutrack_api.routers import attendance, auth, crud, importer, me, print, reports, student_photos
```

In the router list, insert one line directly above `api.include_router(crud.router)`:

```python
    api.include_router(student_photos.router)
    api.include_router(crud.router)
```

Change nothing else in `main.py`.

- [ ] **Step 4: Write `fleet_orders/08_REPORT_W2.md`** — list the 3 files touched and anything in the plan you could not apply verbatim. Do not run any command.

---

### Task 2: Print embedding, CRUD guard, legacy migration (Worker W4 — refactor/overflow lane)

**Files:**
- Modify: `server/edutrack_api/routers/print.py` (imports lines 1–12; photo sites at lines ~124, ~152, ~163, ~169, ~177, ~238)
- Modify: `server/edutrack_api/routers/crud.py` (`create_row` and `update_row` inside `_register`, lines ~544 and ~563)
- Create: `server/edutrack_api/migrate_student_photos.py`

**Interfaces:**
- Consumes from Task 1 (module `edutrack_api.services.student_photos`): `photo_data_uri(student_id) -> str`, `detect_image_type(data: bytes) -> str | None`, `save_photo(student_id, data: bytes) -> str`, `photo_url_for(student_id, version: str) -> str`. Task 1 runs in parallel; use these names exactly.
- Produces: `migrate_legacy_photos(conn) -> dict` with keys `moved: int`, `skipped: list[str]`; CLI `python -m edutrack_api.migrate_student_photos` (used by Task 3).

- [ ] **Step 1: `print.py` — add the import after line 12**

```python
from edutrack_api.services.student_photos import photo_data_uri
```

- [ ] **Step 2: `print.py` — replace the four photo sources**

Guardian card (`"photo_url": s.get("photo_url") or "",` in the `guardian_card` payload):

```python
        "photo_url": photo_data_uri(s["id"]),
```

Certificate by id — the query loses `s.photo_url` and the payload reads the file:

```python
            "SELECT c.*, s.name student_name FROM certificates c JOIN students s ON s.id=c.student_id WHERE c.id=%s AND c.deleted_at IS NULL AND s.deleted_at IS NULL",
```

```python
                "photo_url": photo_data_uri(c["student_id"]),
```

Certificate by student id:

```python
        s = _one(conn, "SELECT name FROM students WHERE id = %s AND deleted_at IS NULL", (sid,), "الطالب غير موجود")
```

```python
            "photo_url": photo_data_uri(sid),
```

Student report (`"photo_url": s.get("photo_url") or "",` in the `student_report` payload):

```python
        "photo_url": photo_data_uri(sid),
```

After this step `grep -n "photo_url" print.py` must show exactly 4 payload lines, all calling `photo_data_uri`, and no SQL selecting `photo_url`.

- [ ] **Step 3: `crud.py` — drop client-sent `photo_url` for students**

In both `create_row` and `update_row`, directly after the line `_allowed(user, table, True)`, insert:

```python
        if table == "students":
            body = {k: v for k, v in body.items() if k != "photo_url"}  # written only by POST /students/{id}/photo
```

Change nothing else in `crud.py`.

- [ ] **Step 4: Create `server/edutrack_api/migrate_student_photos.py` with exactly this content**

```python
"""Move legacy base64 student photos out of students.photo_url into STUDENT_PHOTO_DIR.

Idempotent: only rows whose photo_url starts with "data:" are touched; a second run moves nothing.
Run inside the API container after a deploy:  python -m edutrack_api.migrate_student_photos
"""

from __future__ import annotations

import base64
import binascii
import sys

import psycopg
from psycopg.rows import dict_row

from edutrack_api.config import get_settings
from edutrack_api.services.student_photos import detect_image_type, photo_url_for, save_photo


def migrate_legacy_photos(conn) -> dict:
    rows = conn.execute(
        "SELECT id, photo_url FROM students WHERE left(photo_url, 5) = 'data:' ORDER BY id"
    ).fetchall()
    moved, skipped = 0, []
    for row in rows:
        header, _, payload = row["photo_url"].partition(",")
        try:
            data = base64.b64decode(payload, validate=True) if header.endswith(";base64") else b""
        except (binascii.Error, ValueError):
            data = b""
        if detect_image_type(data) is None:
            skipped.append(str(row["id"]))
            continue
        url = photo_url_for(row["id"], save_photo(row["id"], data))
        conn.execute("UPDATE students SET photo_url = %s WHERE id = %s", (url, row["id"]))
        moved += 1
    return {"moved": moved, "skipped": skipped}


def main() -> int:
    with psycopg.connect(get_settings().database_url, row_factory=dict_row) as conn:
        result = migrate_legacy_photos(conn)
        conn.commit()
    print(f"student photos: moved {result['moved']}, skipped {len(result['skipped'])}")
    for sid in result["skipped"]:
        print(f"  skipped (not JPEG/PNG): {sid}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 5: Write `fleet_orders/08_REPORT_W4.md`** — files touched, the final `photo_url` lines of `print.py`, anything not applied verbatim. Do not run any command.

---

### Task 3: Container, volume, deploy and backup (Worker W3 — ops lane)

**Files:**
- Modify: `server/Dockerfile` (the `RUN useradd …` block)
- Modify: `deploy/docker-compose.prod.yml` (`api.environment`, `api.volumes`, top-level `volumes`)
- Modify: `deploy/deploy.sh` (new section between the API health wait and `# ---- backups`)
- Modify: `deploy/backup.sh` (new archive section after the dump's atomic publish; rotation; final line)

**Interfaces:**
- Consumes from Task 2: CLI `python -m edutrack_api.migrate_student_photos` (prints `student photos: moved N, skipped M`, exit 0).
- Produces: volume `gheras_student_photos` mounted at `/app/student_photos`, env `STUDENT_PHOTO_DIR=/app/student_photos`, nightly `student_photos_<STAMP>.tar.gz` next to the dumps.

- [ ] **Step 1: `server/Dockerfile`** — replace the `RUN useradd` block with:

```dockerfile
RUN useradd --system --uid 10001 --no-create-home edutrack && \
    mkdir -p /app/uploads /app/student_photos && \
    chown -R edutrack:edutrack /app/uploads /app/student_photos
```

- [ ] **Step 2: `deploy/docker-compose.prod.yml`** — in service `api`:

```yaml
    environment:
      DATABASE_URL: postgresql://gheras_app:${GHERAS_APP_PASSWORD:?set in deploy/.env}@db:5432/gheras_edutrack
      TZ: Asia/Riyadh
      STUDENT_PHOTO_DIR: /app/student_photos
```

```yaml
    volumes:
      - gheras_uploads:/app/uploads
      - gheras_student_photos:/app/student_photos
```

Top-level `volumes:` gains `gheras_student_photos:` directly under `gheras_uploads:`. Change nothing else.

- [ ] **Step 3: `deploy/deploy.sh`** — insert this section after the line that dies when the API is not healthy (`[[ "$status" == healthy ]] || …`) and before `# ---------------------------------------------------------------- backups (nightly pg_dump)`:

```bash
# ---------------------------------------------------------------- student photos (private volume)
log "Checking the student photo volume and moving legacy base64 photos out of the database"
"${COMPOSE[@]}" exec -T api sh -c 'touch "$STUDENT_PHOTO_DIR/.write-test" && rm "$STUDENT_PHOTO_DIR/.write-test"' \
  || die "the API cannot write to STUDENT_PHOTO_DIR (volume gheras_student_photos)"
"${COMPOSE[@]}" exec -T api python -m edutrack_api.migrate_student_photos \
  || die "student photo migration failed"
```

The single quotes are intentional: `$STUDENT_PHOTO_DIR` must expand inside the container.

- [ ] **Step 4: `deploy/backup.sh`** — insert after the dump's `trap - EXIT` (end of the `atomic publish` section) and before `# ---- rotation`:

```bash
# ---------------------------------------------------------------- student photos (private volume, not in the dump)
PHOTOS_OUT="$BACKUP_DIR/student_photos_${STAMP}.tar.gz"
PHOTOS_TMP="$PHOTOS_OUT.part"
trap 'rm -f "$PHOTOS_TMP"' EXIT
log "Archiving student photos -> $PHOTOS_OUT"
"${COMPOSE[@]}" exec -T api tar -C /app -czf - student_photos > "$PHOTOS_TMP"
tar -tzf "$PHOTOS_TMP" >/dev/null || die "student photo archive is not readable"
chmod 600 "$PHOTOS_TMP"
mv "$PHOTOS_TMP" "$PHOTOS_OUT"
trap - EXIT
```

In the rotation section add, after the existing two `find` lines:

```bash
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'student_photos_*.tar.gz' -mtime +"$RETENTION_DAYS" -delete
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'student_photos_*.tar.gz.part' -mtime +1 -delete
```

Replace the final `echo "backup ok: …"` line with:

```bash
echo "backup ok: $OUT ($(du -h "$OUT" | cut -f1), $entries toc entries) + $PHOTOS_OUT ($(du -h "$PHOTOS_OUT" | cut -f1)), removed $removed old dump(s)"
```

- [ ] **Step 5: Write `fleet_orders/08_REPORT_W3.md`** — files touched and anything not applied verbatim. Do not run any command (no bash, no docker).

---

### Task 4: Dashboard upload flow (Worker W1 — UI/web lane)

**Files:**
- Modify: `web/dashboard/js/api.js`
- Modify: `web/dashboard/js/views/students.js` (photo picker block lines ~568–670; cancel listener line ~778; save handler lines ~875–885)
- Modify: `web/dashboard/index.html` (2 × `?v=3.3`)
- Modify: `web/dashboard/js/app.js` (2 × `?v=3.3`)

**Interfaces:**
- Consumes (Task 1 HTTP contract): `POST students/{id}/photo` raw JPEG body → `{photo_url}`; `GET students/{id}/photo` → image bytes (auth required); `DELETE students/{id}/photo` → 204.
- Produces: `api.upload(path, blob) -> Promise<object>`, `api.fetchBlobUrl(path) -> Promise<string>`.

- [ ] **Step 1: `api.js`** — replace the `request` function and the `export` line; keep `fetchAll` exactly as it is.

```js
const BASE = '/api/v1';

function authHeaders(extra = {}) {
  const token = sessionStorage.getItem('gheras_token') || localStorage.getItem('gheras_token');
  const headers = { Accept: 'application/json', ...extra };
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

async function send(path, options) {
  const response = await fetch(`${BASE}/${path.replace(/^\//, '')}`, options);
  if (response.status === 401) window.dispatchEvent(new CustomEvent('gheras:logout'));
  return response;
}

async function request(path, options = {}) {
  const headers = authHeaders(options.headers);
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await send(path, { ...options, headers, body: options.body === undefined ? undefined : JSON.stringify(options.body) });
  const data = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) throw new Error(data?.error?.message || data?.error?.code || 'تعذر إكمال الطلب');
  return data;
}

// Raw binary upload (student photo): the blob is sent as-is with its own type, not as JSON.
async function upload(path, blob) {
  const response = await send(path, { method: 'POST', headers: authHeaders({ 'Content-Type': blob.type || 'application/octet-stream' }), body: blob });
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error(data?.error?.message || data?.error?.code || 'تعذر رفع الملف');
  return data;
}

// Authenticated image fetch for <img src>; the caller must URL.revokeObjectURL() the result.
async function fetchBlobUrl(path) {
  const response = await send(path, { headers: authHeaders({ Accept: 'image/*' }) });
  if (!response.ok) throw new Error('تعذر تحميل الصورة');
  return URL.createObjectURL(await response.blob());
}
```

Export line:

```js
export const api = { get: path => request(path), fetchAll, upload, fetchBlobUrl, post: (path, body) => request(path, { method: 'POST', body }), patch: (path, body) => request(path, { method: 'PATCH', body }), put: (path, body) => request(path, { method: 'PUT', body }), del: path => request(path, { method: 'DELETE' }) };
```

- [ ] **Step 2: `students.js` — replace the photo picker block**

Replace everything from the comment `// --- 📷 إدراج صورة الطالب من جهاز المستخدم (كمبيوتر / لابتوب) ---` down to (not including) `const photoPickerField = el('div', …` with:

```js
  // --- 📷 صورة الطالب: تُرفع إلى مخزن خاص على الخادم بعد حفظ الطالب (لا تُحفظ داخل بيانات الطالب) ---
  const photoState = { blob: null, remove: false, objectUrl: null };
  const fileInput = el('input', { type: 'file', accept: 'image/png, image/jpeg, image/webp', style: 'display:none;' });

  const previewImg = el('img', {
    alt: 'صورة الطالب',
    style: 'width:60px; height:60px; border-radius:10px; object-fit:cover; border:2px solid #0284c7; box-shadow:0 2px 6px rgba(0,0,0,0.08); display:none;'
  });

  const previewPlaceholder = el('div', {
    style: 'width:60px; height:60px; border-radius:10px; background:#f1f5f9; border:2px dashed #cbd5e1; display:flex; align-items:center; justify-content:center; font-size:26px; color:#94a3b8;'
  }, '📷');

  const uploadBtn = el('button', {
    type: 'button',
    class: 'button button-outline',
    style: 'display:inline-flex; align-items:center; gap:6px; padding:7px 12px; font-size:12px; font-weight:600; color:#0284c7; border-color:#38bdf8; background:#f0f9ff;'
  }, '📁 إدراج صورة من الجهاز (كمبيوتر / لابتوب)');

  const clearPhotoBtn = el('button', {
    type: 'button',
    class: 'button button-outline',
    style: 'padding:4px 8px; font-size:11px; color:#ef4444; border-color:#fecaca; background:#fff; display:none;'
  }, 'حذف الصورة');

  const showPreview = url => {
    if (photoState.objectUrl) URL.revokeObjectURL(photoState.objectUrl);
    photoState.objectUrl = url || null;
    previewImg.src = url || '';
    previewImg.style.display = url ? 'block' : 'none';
    previewPlaceholder.style.display = url ? 'none' : 'flex';
    clearPhotoBtn.style.display = url ? 'inline-block' : 'none';
  };

  if (editing && student.photo_url) {
    api.fetchBlobUrl(`students/${student.id}/photo`).then(showPreview).catch(() => showPreview(null));
  }

  uploadBtn.onclick = () => fileInput.click();

  clearPhotoBtn.onclick = () => {
    photoState.blob = null;
    photoState.remove = true;
    fileInput.value = '';
    showPreview(null);
    toast('سيتم حذف صورة الطالب عند الحفظ');
  };

  fileInput.onchange = () => {
    const file = fileInput.files && fileInput.files[0];
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      toast('يرجى اختيار ملف صورة صالح (JPEG, PNG, WebP)', true);
      return;
    }
    const sourceUrl = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(sourceUrl);
      const maxDim = 600;
      let w = img.width;
      let h = img.height;
      if (w > maxDim || h > maxDim) {
        if (w > h) { h = Math.round((h * maxDim) / w); w = maxDim; } else { w = Math.round((w * maxDim) / h); h = maxDim; }
      }
      const canvas = document.createElement('canvas');
      canvas.width = w;
      canvas.height = h;
      canvas.getContext('2d').drawImage(img, 0, 0, w, h);
      canvas.toBlob(blob => {
        if (!blob) { toast('تعذر معالجة ملف الصورة', true); return; }
        photoState.blob = blob;
        photoState.remove = false;
        showPreview(URL.createObjectURL(blob));
        toast(`تم تجهيز صورة الطالب: ${file.name}`);
      }, 'image/jpeg', 0.85);
    };
    img.onerror = () => {
      URL.revokeObjectURL(sourceUrl);
      toast('تعذر معالجة ملف الصورة', true);
    };
    img.src = sourceUrl;
  };
```

In the `photoPickerField` element that follows, delete the trailing `photoInput` child (the last argument after `fileInput,`) so it ends with `fileInput`. Change the hint text `'يدعم ملفات JPG, PNG, WebP بحجم خفيف وجودة عالية تُحفظ وتُطبع في البطاقة'` to `'يدعم JPG و PNG و WebP، وتُصغَّر تلقائيًا وتظهر في البطاقة والتقرير والشهادة'`. After this step `grep -n "photoInput\|photo_url" students.js` must show only the `student.photo_url` check inside `if (editing && student.photo_url)`.

- [ ] **Step 3: `students.js` — revoke the preview on cancel**

Directly after `cancel.addEventListener('click', dialog.close);` add:

```js
  cancel.addEventListener('click', () => { if (photoState.objectUrl) URL.revokeObjectURL(photoState.objectUrl); });
```

- [ ] **Step 4: `students.js` — upload or delete after the student is saved**

Replace this block of the save handler:

```js
      let createdStudent = null;
      if (editing) {
        await api.patch(`students/${student.id}`, payload);
      } else {
        createdStudent = await api.post('students', payload);
      }
      dialog.close();
      toast(editing ? 'تم تحديث بيانات الطالب بنجاح' : 'تمت إضافة الطالب بنجاح');
```

with:

```js
      let createdStudent = null;
      if (editing) {
        await api.patch(`students/${student.id}`, payload);
      } else {
        createdStudent = await api.post('students', payload);
      }
      const savedId = editing ? student.id : createdStudent?.id;
      let photoFailed = false;
      if (savedId && (photoState.blob || (editing && photoState.remove))) {
        try {
          if (photoState.blob) await api.upload(`students/${savedId}/photo`, photoState.blob);
          else await api.del(`students/${savedId}/photo`);
        } catch (photoError) {
          console.error('Student photo failed:', photoError);
          photoFailed = true;
        }
      }
      if (photoState.objectUrl) URL.revokeObjectURL(photoState.objectUrl);
      dialog.close();
      if (photoFailed) toast('تم حفظ الطالب، لكن تعذر رفع الصورة. أعد المحاولة من نافذة التعديل', true);
      else toast(editing ? 'تم تحديث بيانات الطالب بنجاح' : 'تمت إضافة الطالب بنجاح');
```

Leave the rest of the handler (reload, receipt redirect, catch) unchanged.

- [ ] **Step 5: Cache tag** — in `web/dashboard/index.html` and `web/dashboard/js/app.js` replace every `?v=3.3` with `?v=3.4` (2 + 2 occurrences). Nothing else.

- [ ] **Step 6: Write `fleet_orders/08_REPORT_W1.md`** — files touched, anything not applied verbatim. Do not run any command.

---

### Task 5: Quality gate (Claude Code only)

- [ ] **Step 1: Diff audit** — `git diff --stat` must list exactly the 14 files of Tasks 1–4 plus the 2 test files; anything else is reverted and reported. Compare each worker diff against its task block.
- [ ] **Step 2: Static checks** — `python -m py_compile` on the 5 Python files; `uvx ruff check server` delta vs HEAD (no new rule class); `node --check` on `api.js`, `students.js`, `app.js`; `bash -n deploy/deploy.sh deploy/backup.sh`; `docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod.example config -q`.
- [ ] **Step 3: Tests** — `pytest tests/test_student_photos.py tests/test_print_student_photo.py -q` (all green), then the full suite (previous count 160 + new tests, 0 failures).
- [ ] **Step 4: Browser check (local stack, Caddy + uvicorn + embedded PG)** — add a student with a large phone photo → request is ≤ 2 MiB JPEG; reopen edit → preview loads through `fetchBlobUrl`; edit another field and save → no POST/DELETE to `/photo` in the network log and the photo is still there; delete photo → DELETE 204; print guardian card, student report and certificate → `img` with a `data:image/jpeg` source; a student without a photo keeps the dashed box.
- [ ] **Step 5: Backup script check** — run the photo-archive section of `backup.sh` against a local `docker` shim (pattern from §5g) to prove the `.part` → `mv` → `tar -tzf` path and the rotation patterns.
- [ ] **Step 6: Docs** — `deploy/DEPLOY.md` Backups section: one paragraph on `student_photos_<STAMP>.tar.gz` and its restore (`docker compose … exec -T api tar -C /app -xzf - < student_photos_<STAMP>.tar.gz`). Spec status line → "IMPLEMENTED (plan 2026-10-14)".
- [ ] **Step 7: Verdict + commit** — `[APPROVED]` or CHANGES REQUESTED with the failing item; commit `feat(edutrack-photo): private student photo storage, scoped photo API, print embedding, legacy migration`.

### Task 6: Deploy (Ibrahim / Antigravity, after `[APPROVED]`)

- [ ] **Step 1 (before deploy, read-only on production):** `SELECT count(*) FILTER (WHERE left(photo_url,5)='data:') AS legacy, count(*) FILTER (WHERE photo_url LIKE '/api/v1/students/%') AS migrated, count(*) FILTER (WHERE photo_url IS NOT NULL) AS any FROM students;` — paste back.
- [ ] **Step 2:** push, sync the WSL clone, `deploy/push.sh` (API rebuild + new volume). `deploy.sh` prints `student photos: moved N, skipped M` and the backup line now names a `student_photos_*.tar.gz`.
- [ ] **Step 3:** paste back the deploy tail; Claude re-checks `/api/v1/health` 200 and `/api/v1/students/<any>/photo` without a token → 401 from the public side.
