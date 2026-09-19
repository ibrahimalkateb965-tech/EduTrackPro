"""Router for importing backup data into EduTrack Pro."""

from __future__ import annotations

import uuid
from fastapi import APIRouter, Depends, Request

from edutrack_api.audit import write_audit
from edutrack_api.auth import require_roles
from edutrack_api.config import get_settings
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.importer import import_backup

router = APIRouter()


def _normalize_payload(payload: dict) -> dict:
    if not isinstance(payload, dict):
        raise ApiError(422, "validation_error", "صيغة البيانات غير صحيحة، يجب إرسال كائن JSON")

    data = payload.get("data") if isinstance(payload.get("data"), dict) else payload
    normalized = {}

    for key, val in data.items():
        if isinstance(val, dict) and "items" in val and isinstance(val["items"], list):
            normalized[key] = val["items"]
        elif isinstance(val, list):
            normalized[key] = val
        else:
            normalized[key] = val

    return normalized


@router.post("/import")
def run_import(
    request: Request,
    body: dict,
    user: dict = Depends(require_roles("manager")),
    conn=Depends(get_conn),
):
    """Import a JSON backup payload into PostgreSQL."""
    normalized = _normalize_payload(body)

    branch_id = getattr(request.app.state, "branch_id", None) or get_settings().main_branch_id
    if not isinstance(branch_id, uuid.UUID):
        try:
            branch_id = uuid.UUID(str(branch_id))
        except (ValueError, TypeError):
            branch_id = get_settings().main_branch_id

    try:
        report = import_backup(conn, normalized, branch_id=branch_id, dry_run=False)
    except Exception as exc:
        raise ApiError(500, "import_failed", f"فشلت عملية الاستيراد: {exc}") from exc

    collections_summary = []
    for r in report.rows:
        if r.read > 0 or r.inserted > 0 or r.skipped > 0:
            collections_summary.append({
                "collection": r.collection,
                "read": r.read,
                "inserted": r.inserted,
                "skipped": r.skipped,
                "warnings": r.warnings,
            })

    total_read = sum(r.read for r in report.rows)
    total_inserted = sum(r.inserted for r in report.rows)
    total_skipped = sum(r.skipped for r in report.rows)

    try:
        write_audit(
            conn,
            user["id"],
            "import",
            "system",
            user["id"],
            {
                "total_read": total_read,
                "total_inserted": total_inserted,
                "total_skipped": total_skipped,
            },
        )
    except Exception:
        pass

    return {
        "status": "ok",
        "message": f"تم استيراد {total_inserted} سجل بنجاح من أصل {total_read}",
        "total_read": total_read,
        "total_inserted": total_inserted,
        "total_skipped": total_skipped,
        "collections": collections_summary,
    }
