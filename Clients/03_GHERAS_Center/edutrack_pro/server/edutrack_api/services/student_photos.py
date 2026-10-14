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
