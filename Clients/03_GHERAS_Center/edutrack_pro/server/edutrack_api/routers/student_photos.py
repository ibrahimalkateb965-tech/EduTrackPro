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
