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
