"""/api/v1/me — row-scoped surface for the teacher and guardian mobile apps (PHASE5_SPEC §2).

Handlers are thin: dependency (scope + user) → query-param validation → repo → row_to_json.
Manager/supervisor are rejected by require_scope (403); role mismatches per route → 403.
"""

from __future__ import annotations

import datetime as dt  # noqa: F401
from typing import Literal  # noqa: F401
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel  # noqa: F401

from edutrack_api.auth import current_user
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.repositories import me_repo
from edutrack_api.scope import Scope, require_scope
from edutrack_api.serializers import row_to_json

router = APIRouter(prefix="/me")
_SCOPE = Depends(require_scope)
_USER = Depends(current_user)
_CONN = Depends(get_conn)


def _page(limit: int, offset: int) -> None:
    if not 1 <= limit <= 500 or offset < 0:
        raise ApiError(422, "validation_error", "قيم التصفح غير صحيحة")


def _envelope(rows: list[dict], total: int, limit: int, offset: int) -> dict:
    return {"items": [row_to_json(r) for r in rows], "total": total, "limit": limit, "offset": offset}


def _only(scope: Scope, role: str) -> None:
    if scope.role != role:
        raise ApiError(403, "forbidden")


# ---- batch 1 ---------------------------------------------------------------


@router.get("/profile")
def get_profile(scope: Scope = _SCOPE, user: dict = _USER, conn=_CONN) -> dict:
    return row_to_json(me_repo.profile(conn, scope, user))


@router.get("/students")
def list_students(
    limit: int = 100,
    offset: int = 0,
    student_id: UUID | None = None,
    status: str | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _page(limit, offset)
    rows, total = me_repo.list_students(conn, scope, {"student_id": student_id, "status": status}, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/rooms")
def list_rooms(limit: int = 100, offset: int = 0, scope: Scope = _SCOPE, conn=_CONN) -> dict:
    _only(scope, "teacher")
    _page(limit, offset)
    rows, total = me_repo.list_rooms(conn, scope, {}, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/schedule")
def list_schedule(limit: int = 100, offset: int = 0, day: str | None = None, scope: Scope = _SCOPE, conn=_CONN) -> dict:
    _page(limit, offset)
    rows, total = me_repo.list_schedule(conn, scope, {"day": day}, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/notifications")
def list_notifications(limit: int = 100, offset: int = 0, unread: bool | None = None, scope: Scope = _SCOPE, conn=_CONN) -> dict:
    _page(limit, offset)
    rows, total = me_repo.list_notifications(conn, scope, {"unread": unread}, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.post("/notifications/{notification_id}/read")
def read_notification(notification_id: UUID, scope: Scope = _SCOPE, conn=_CONN) -> dict:
    row = me_repo.mark_notification_read(conn, scope, notification_id)
    if row is None:
        raise ApiError(404, "not_found")
    return row_to_json(row)
