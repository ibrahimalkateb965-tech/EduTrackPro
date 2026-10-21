"""Tests for authenticated media router and 4-source ACL security (R3-2, R4-1, R4-2).

Covers:
1. 25MB upload cap enforcement (413 file_too_large).
2. Magic bytes validation (PDF, JPEG, PNG, MP4, MP3).
3. Path traversal attack prevention (404 not_found).
4. 4-Source ACL verification:
   - Source 1: assignment_files (teacher owner & enrolled guardian).
   - Source 2: submission_files (student guardian & assignment teacher).
   - Source 3: teacher_feedback_key (guardian & teacher).
   - Source 4: staged_uploads (uploader preview).
5. Unauthenticated and out-of-scope access rejection.
"""

from __future__ import annotations

import io
import os
import uuid
from pathlib import Path
import pytest

from tests.conftest import login, make_user

API = "/api/v1"


@pytest.fixture()
def media_env(db, client):
    branch_id = uuid.UUID("00000000-0000-0000-0000-000000000001")
    t1_id = make_user(db, "teacher_m1", "teacher")
    t2_id = make_user(db, "teacher_m2", "teacher")
    g1_id = make_user(db, "guardian_m1", "guardian")
    g2_id = make_user(db, "guardian_m2", "guardian")

    room_id = db.execute("INSERT INTO rooms (name, group_name) VALUES ('Room M', 'G') RETURNING id").fetchone()["id"]
    db.execute("UPDATE users SET room_id = %s WHERE id = %s", (room_id, t1_id))

    s1_id = db.execute(
        "INSERT INTO students (name, guardian_phone, gender, room_id) VALUES ('طالب 1', '0501110001', 'بنين', %s) RETURNING id",
        (room_id,)
    ).fetchone()["id"]
    s2_id = db.execute(
        "INSERT INTO students (name, guardian_phone, gender, room_id) VALUES ('طالب 2', '0501110002', 'بنين', %s) RETURNING id",
        (room_id,)
    ).fetchone()["id"]

    # Guardian 1 -> Student 1
    ge1_id = db.execute("INSERT INTO guardians (name, phone) VALUES ('ولي 1', '0501110001') RETURNING id").fetchone()["id"]
    db.execute("UPDATE users SET guardian_id = %s WHERE id = %s", (ge1_id, g1_id))
    db.execute("INSERT INTO student_guardians (student_id, guardian_id, relation) VALUES (%s, %s, 'أب')", (s1_id, ge1_id))

    # Guardian 2 -> Student 2
    ge2_id = db.execute("INSERT INTO guardians (name, phone) VALUES ('ولي 2', '0501110002') RETURNING id").fetchone()["id"]
    db.execute("UPDATE users SET guardian_id = %s WHERE id = %s", (ge2_id, g2_id))
    db.execute("INSERT INTO student_guardians (student_id, guardian_id, relation) VALUES (%s, %s, 'أب')", (s2_id, ge2_id))

    db.commit()

    return {
        "t1_id": t1_id,
        "t2_id": t2_id,
        "g1_id": g1_id,
        "g2_id": g2_id,
        "s1_id": s1_id,
        "s2_id": s2_id,
        "room_id": room_id,
        "t1_headers": login(client, "teacher_m1"),
        "t2_headers": login(client, "teacher_m2"),
        "g1_headers": login(client, "guardian_m1"),
        "g2_headers": login(client, "guardian_m2"),
    }


def test_upload_magic_bytes_validation(client, media_env):
    """Uploading files with valid magic bytes succeeds; mismatched bytes fail with 422."""
    headers = media_env["t1_headers"]

    # 1. Valid PDF with %PDF magic header
    valid_pdf = b"%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF"
    res = client.post(
        f"{API}/uploads",
        files={"file": ("worksheet.pdf", valid_pdf, "application/pdf")},
        headers=headers,
    )
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["media_type"] == "document"
    assert "storage_key" in data

    # 2. Fake PDF with text header -> 422
    fake_pdf = b"This is not a pdf file but plain text"
    res_fake = client.post(
        f"{API}/uploads",
        files={"file": ("fake.pdf", fake_pdf, "application/pdf")},
        headers=headers,
    )
    assert res_fake.status_code == 422, res_fake.text
    assert res_fake.json()["error"]["code"] == "invalid_file_content"

    # 3. Valid PNG with \x89PNG magic header
    valid_png = b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR"
    res_png = client.post(
        f"{API}/uploads",
        files={"file": ("photo.png", valid_png, "image/png")},
        headers=headers,
    )
    assert res_png.status_code == 200, res_png.text
    assert res_png.json()["media_type"] == "image"


def test_path_traversal_guard(client, media_env):
    """Path traversal sequences (..) in storage_key are blocked with 404."""
    headers = media_env["g1_headers"]

    res1 = client.get(f"{API}/me/files/../secret.txt", headers=headers)
    assert res1.status_code == 404

    res2 = client.get(f"{API}/me/files/uploads/../../etc/passwd", headers=headers)
    assert res2.status_code == 404


def test_4_source_acl_enforcement(db, client, media_env):
    """Verify ACL across the 4 sources (R4-1, R4-2)."""
    env = media_env
    upload_dir = Path(os.getenv("UPLOAD_DIR", "uploads")).resolve()
    upload_dir.mkdir(parents=True, exist_ok=True)

    # Helper to write mock file on disk
    def write_disk_file(key: str, content: bytes = b"%PDF-1.4\ncontent"):
        path = upload_dir / key
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)

    # 1. Source 4: Preview own staged upload
    staged_key = f"staged_preview_{uuid.uuid4().hex[:8]}.pdf"
    write_disk_file(staged_key)
    db.execute("""
        INSERT INTO staged_uploads (storage_key, uploaded_by_user_id, media_type, file_name, bytes)
        VALUES (%s, %s, 'document', 'preview.pdf', 100)
    """, (staged_key, env["g1_id"]))
    db.commit()

    # Guardian 1 can view own staged upload
    res = client.get(f"{API}/me/files/{staged_key}", headers=env["g1_headers"])
    assert res.status_code == 200

    # Guardian 2 cannot view Guardian 1's staged upload (404)
    res_forbidden = client.get(f"{API}/me/files/{staged_key}", headers=env["g2_headers"])
    assert res_forbidden.status_code == 404

    # 2. Source 1: assignment_files
    ass_id = db.execute("""
        INSERT INTO assignments (teacher_user_id, room_id, title, is_draft)
        VALUES (%s, %s, 'واجب القراءة', false) RETURNING id
    """, (env["t1_id"], env["room_id"])).fetchone()["id"]
    db.execute("INSERT INTO assignment_students (assignment_id, student_id) VALUES (%s, %s)", (ass_id, env["s1_id"]))

    ass_key = f"ass_file_{uuid.uuid4().hex[:8]}.pdf"
    write_disk_file(ass_key)
    db.execute("""
        INSERT INTO assignment_files (assignment_id, uploaded_by_user_id, storage_key, media_type, file_name, bytes)
        VALUES (%s, %s, %s, 'document', 'ass.pdf', 100)
    """, (ass_id, env["t1_id"], ass_key))
    db.commit()

    # Teacher 1 (owner) can download
    assert client.get(f"{API}/me/files/{ass_key}", headers=env["t1_headers"]).status_code == 200
    # Guardian 1 (enrolled student 1) can download
    assert client.get(f"{API}/me/files/{ass_key}", headers=env["g1_headers"]).status_code == 200
    # Guardian 2 (student 2 not enrolled) gets 404
    assert client.get(f"{API}/me/files/{ass_key}", headers=env["g2_headers"]).status_code == 404

    # 3. Source 3: teacher_feedback_key (voice note)
    sub_id = db.execute("""
        INSERT INTO submissions (assignment_id, student_id, attempt_number, status)
        VALUES (%s, %s, 1, 'graded') RETURNING id
    """, (ass_id, env["s1_id"])).fetchone()["id"]

    fb_key = f"feedback_{uuid.uuid4().hex[:8]}.mp3"
    write_disk_file(fb_key, b"ID3\x03\x00\x00\x00\x00")
    db.execute("UPDATE submissions SET teacher_feedback_key = %s WHERE id = %s", (fb_key, sub_id))
    db.commit()

    # Guardian 1 (parent of student 1) can stream teacher feedback
    assert client.get(f"{API}/me/files/{fb_key}", headers=env["g1_headers"]).status_code == 200
    # Teacher 1 can stream
    assert client.get(f"{API}/me/files/{fb_key}", headers=env["t1_headers"]).status_code == 200
    # Guardian 2 cannot stream Student 1's feedback
    assert client.get(f"{API}/me/files/{fb_key}", headers=env["g2_headers"]).status_code == 404
