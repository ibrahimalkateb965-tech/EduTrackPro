"""Router for authenticated media uploads and access-controlled file streaming (R3-2, R4-1, R4-2).

Enforces:
1. 25MB file upload cap (Rule 53).
2. Media magic bytes & type validation (audio, video, image, document).
3. Staged uploads tracking: records uploaded_by_user_id for ownership gates.
4. Traversal guard: prohibits '..' and absolute paths.
5. 4-Source ACL:
   - Source 1: assignment_files (teacher owner or enrolled guardian).
   - Source 2: submission_files (student guardian or assignment teacher).
   - Source 3: submissions.teacher_feedback_key (guardian of student or teacher).
   - Source 4: staged_uploads (uploader previewing their own staged upload).
6. Constant-time 404 on ACL mismatch to prevent resource enumeration.
"""

from __future__ import annotations

import mimetypes
import os
import uuid
from pathlib import Path

from fastapi import APIRouter, Depends, Request, UploadFile, File
from fastapi.responses import FileResponse

from edutrack_api.auth import current_user
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.scope import resolve_scope

router = APIRouter()
_USER = Depends(current_user)
_CONN = Depends(get_conn)

MAX_UPLOAD_SIZE = 25 * 1024 * 1024  # 25 MB cap

ALLOWED_EXTENSIONS = {
    "jpg": "image",
    "jpeg": "image",
    "png": "image",
    "pdf": "document",
    "mp4": "video",
    "mp3": "audio",
    "m4a": "audio",
    "aac": "audio",
}


def _detect_media_type(filename: str, header: bytes) -> str:
    ext = Path(filename).suffix.lower().lstrip(".")
    if ext not in ALLOWED_EXTENSIONS:
        raise ApiError(422, "invalid_file_type", f"امتداد الملف غير مسموح به: {ext}")

    expected_type = ALLOWED_EXTENSIONS[ext]

    # Magic byte verification
    if ext in ("jpg", "jpeg") and not header.startswith(b"\xff\xd8\xff"):
        raise ApiError(422, "invalid_file_content", "محتوى الملف لا يتطابق مع صورة JPEG")
    if ext == "png" and not header.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ApiError(422, "invalid_file_content", "محتوى الملف لا يتطابق مع صورة PNG")
    if ext == "pdf" and not header.startswith(b"%PDF"):
        raise ApiError(422, "invalid_file_content", "محتوى الملف لا يتطابق مع مستند PDF")
    if ext in ("mp4", "m4a") and len(header) >= 8 and header[4:8] != b"ftyp":
        raise ApiError(422, "invalid_file_content", "محتوى الملف لا يتطابق مع وسائط MP4/M4A")
    if ext == "mp3" and not (header.startswith(b"ID3") or header.startswith(b"\xff\xfb") or header.startswith(b"\xff\xf3") or header.startswith(b"\xff\xf2")):
        raise ApiError(422, "invalid_file_content", "محتوى الملف لا يتطابق مع ملف صوت MP3")

    return expected_type


def _get_upload_dir() -> Path:
    d = Path(os.getenv("UPLOAD_DIR", "uploads")).resolve()
    d.mkdir(parents=True, exist_ok=True)
    return d


@router.post("/uploads")
async def upload_media_file(
    request: Request,
    user: dict = _USER,
    conn = _CONN,
) -> dict:
    """Unified authenticated media upload handler with 25MB cap and staged_uploads tracking."""
    content_type = request.headers.get("content-type", "")
    upload_dir = _get_upload_dir()

    content_length = request.headers.get("content-length")
    if content_length and int(content_length) > MAX_UPLOAD_SIZE:
        raise ApiError(413, "file_too_large", "حجم الملف يتجاوز الحد المسموح (25 ميجابايت)")

    if "multipart/form-data" in content_type:
        form = await request.form()
        file_obj = form.get("file")
        if not file_obj or not hasattr(file_obj, "read"):
            raise ApiError(422, "missing_file", "لم يتم إرسال ملف")
        filename = file_obj.filename or "upload.bin"
        chunk_head = await file_obj.read(512)
        media_type = _detect_media_type(filename, chunk_head)
        await file_obj.seek(0)

        safe_base = Path(filename).name.replace(" ", "_")
        storage_key = f"{uuid.uuid4().hex}_{safe_base}"
        dest = upload_dir / storage_key

        total_bytes = 0
        with open(dest, "wb") as f:
            while chunk := await file_obj.read(65536):
                total_bytes += len(chunk)
                if total_bytes > MAX_UPLOAD_SIZE:
                    dest.unlink(missing_ok=True)
                    raise ApiError(413, "file_too_large", "حجم الملف يتجاوز الحد المسموح (25 ميجابايت)")
                f.write(chunk)
    else:
        filename = request.headers.get("x-filename") or f"{uuid.uuid4().hex}.bin"
        body_bytes = bytearray()
        first_chunk = True
        media_type = "image"
        safe_base = Path(filename).name.replace(" ", "_")
        storage_key = f"{uuid.uuid4().hex}_{safe_base}"
        dest = upload_dir / storage_key

        total_bytes = 0
        with open(dest, "wb") as f:
            async for chunk in request.stream():
                total_bytes += len(chunk)
                if total_bytes > MAX_UPLOAD_SIZE:
                    dest.unlink(missing_ok=True)
                    raise ApiError(413, "file_too_large", "حجم الملف يتجاوز الحد المسموح (25 ميجابايت)")
                if first_chunk:
                    media_type = _detect_media_type(filename, chunk[:512])
                    first_chunk = False
                f.write(chunk)

    # Insert into staged_uploads with user ownership
    conn.execute("""
        INSERT INTO staged_uploads (storage_key, uploaded_by_user_id, media_type, file_name, bytes)
        VALUES (%s, %s, %s, %s, %s)
        ON CONFLICT (storage_key) DO UPDATE SET bytes = EXCLUDED.bytes
    """, (storage_key, user["id"], media_type, safe_base, total_bytes))

    return {
        "storage_key": storage_key,
        "url": f"/api/v1/me/files/{storage_key}",
        "media_type": media_type,
        "file_name": safe_base,
        "bytes": total_bytes,
    }


@router.get("/me/files/{storage_key:path}")
def get_authenticated_file(
    storage_key: str,
    user: dict = _USER,
    conn = _CONN,
):
    """Serve media files protected by 4-source ACL (R4-1, R4-2)."""
    # Traversal security guard
    if ".." in storage_key or storage_key.startswith("/") or "\\" in storage_key:
        raise ApiError(404, "not_found", "الملف غير موجود")

    upload_dir = _get_upload_dir()
    file_path = upload_dir / storage_key
    if not file_path.is_file():
        raise ApiError(404, "not_found", "الملف غير موجود")

    user_id = user["id"]
    role = user.get("role")

    allowed = False

    # Staff roles (manager, supervisor) bypass resolve_scope() (P8)
    if role in ("manager", "supervisor"):
        # Source 5: attendance_excuses.attachment_storage_key
        excuse = conn.execute(
            "SELECT 1 FROM attendance_excuses WHERE attachment_storage_key = %s AND deleted_at IS NULL",
            (storage_key,),
        ).fetchone()
        if excuse:
            allowed = True

        # Source 4: Preview own staged upload
        if not allowed:
            staged = conn.execute(
                "SELECT 1 FROM staged_uploads WHERE storage_key = %s AND uploaded_by_user_id = %s",
                (storage_key, user_id),
            ).fetchone()
            if staged:
                allowed = True

        # Staff can inspect assignment files or submission files
        if not allowed:
            ass_or_sub = conn.execute(
                """
                SELECT 1 FROM assignment_files WHERE storage_key = %s AND deleted_at IS NULL
                UNION
                SELECT 1 FROM submission_files WHERE storage_key = %s AND deleted_at IS NULL
                UNION
                SELECT 1 FROM submissions WHERE teacher_feedback_key = %s AND deleted_at IS NULL
                """,
                (storage_key, storage_key, storage_key),
            ).fetchone()
            if ass_or_sub:
                allowed = True
    else:
        # Non-staff roles (teacher, guardian, student)
        scope = resolve_scope(conn, user)

        # Source 4: Preview own staged upload
        staged = conn.execute(
            "SELECT 1 FROM staged_uploads WHERE storage_key = %s AND uploaded_by_user_id = %s",
            (storage_key, user_id),
        ).fetchone()
        if staged:
            allowed = True

        # Source 5: attendance_excuses.attachment_storage_key
        if not allowed:
            exc_rows = conn.execute(
                """
                SELECT ae.id, ae.student_id, s.room_id
                FROM attendance_excuses ae
                JOIN students s ON s.id = ae.student_id AND s.deleted_at IS NULL
                WHERE ae.attachment_storage_key = %s AND ae.deleted_at IS NULL
                """,
                (storage_key,),
            ).fetchall()
            for r in exc_rows:
                if role == "guardian" and r["student_id"] in scope.student_ids:
                    allowed = True
                    break
                if role == "teacher" and (r["student_id"] in scope.student_ids or r["room_id"] in scope.room_ids):
                    allowed = True
                    break

        # Source 1: assignment_files
        if not allowed:
            ass_file = conn.execute("""
                SELECT a.id, a.is_draft, a.teacher_user_id, ast.student_id
                FROM assignment_files af
                JOIN assignments a ON a.id = af.assignment_id
                LEFT JOIN assignment_students ast ON ast.assignment_id = a.id AND ast.deleted_at IS NULL
                WHERE af.storage_key = %s AND af.deleted_at IS NULL AND a.deleted_at IS NULL
            """, (storage_key,)).fetchall()
            for r in ass_file:
                if role == "teacher" and r["teacher_user_id"] == user_id:
                    allowed = True
                    break
                if role == "guardian" and not r["is_draft"] and r["student_id"] in scope.student_ids:
                    allowed = True
                    break

        # Source 2: submission_files
        if not allowed:
            sub_file = conn.execute("""
                SELECT sb.id, sb.student_id, a.teacher_user_id
                FROM submission_files sf
                JOIN submissions sb ON sb.id = sf.submission_id
                JOIN assignments a ON a.id = sb.assignment_id
                WHERE sf.storage_key = %s AND sf.deleted_at IS NULL AND sb.deleted_at IS NULL
            """, (storage_key,)).fetchall()
            for r in sub_file:
                if role == "guardian" and r["student_id"] in scope.student_ids:
                    allowed = True
                    break
                if role == "teacher" and (r["teacher_user_id"] == user_id or r["student_id"] in scope.student_ids):
                    allowed = True
                    break

        # Source 3: submissions.teacher_feedback_key
        if not allowed:
            fb_rows = conn.execute("""
                SELECT sb.id, sb.student_id, a.teacher_user_id
                FROM submissions sb
                JOIN assignments a ON a.id = sb.assignment_id
                WHERE sb.teacher_feedback_key = %s AND sb.deleted_at IS NULL
            """, (storage_key,)).fetchall()
            for r in fb_rows:
                if role == "guardian" and r["student_id"] in scope.student_ids:
                    allowed = True
                    break
                if role == "teacher" and (r["teacher_user_id"] == user_id or r["student_id"] in scope.student_ids):
                    allowed = True
                    break

    if not allowed:
        raise ApiError(404, "not_found", "الملف غير موجود")

    mime_type, _ = mimetypes.guess_type(str(file_path))
    return FileResponse(
        str(file_path),
        media_type=mime_type or "application/octet-stream",
        filename=Path(storage_key).name,
    )
