"""/api/v1/me — row-scoped surface for the teacher and guardian mobile apps (PHASE5_SPEC §2).

Handlers are thin: dependency (scope + user) → query-param validation → repo → row_to_json.
Manager/supervisor are rejected by require_scope (403); role mismatches per route → 403.
"""

from __future__ import annotations

import datetime as dt
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel

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


# ---- batch 2 ---------------------------------------------------------------


@router.get("/attendance")
def list_attendance(
    limit: int = 100,
    offset: int = 0,
    student_id: UUID | None = None,
    date_from: dt.date | None = None,
    date_to: dt.date | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _page(limit, offset)
    filters = {"student_id": student_id, "date_from": date_from, "date_to": date_to}
    rows, total = me_repo.list_attendance(conn, scope, filters, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/evaluations")
def list_evaluations(
    limit: int = 100,
    offset: int = 0,
    student_id: UUID | None = None,
    eval_type: str | None = None,
    date_from: dt.date | None = None,
    date_to: dt.date | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _page(limit, offset)
    filters = {"student_id": student_id, "eval_type": eval_type, "date_from": date_from, "date_to": date_to}
    rows, total = me_repo.list_evaluations(conn, scope, filters, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/assignments")
def list_assignments(
    limit: int = 100,
    offset: int = 0,
    student_id: UUID | None = None,
    due_from: dt.date | None = None,
    due_to: dt.date | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _page(limit, offset)
    filters = {"student_id": student_id, "due_from": due_from, "due_to": due_to}
    rows, total = me_repo.list_assignments(conn, scope, filters, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/submissions")
def list_submissions(
    limit: int = 100,
    offset: int = 0,
    assignment_id: UUID | None = None,
    student_id: UUID | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _page(limit, offset)
    filters = {"assignment_id": assignment_id, "student_id": student_id}
    rows, total = me_repo.list_submissions(conn, scope, filters, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/lesson-logs")
def list_lesson_logs(
    limit: int = 100,
    offset: int = 0,
    schedule_id: UUID | None = None,
    date_from: dt.date | None = None,
    date_to: dt.date | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _page(limit, offset)
    filters = {"schedule_id": schedule_id, "date_from": date_from, "date_to": date_to}
    rows, total = me_repo.list_lesson_logs(conn, scope, filters, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/skill-progress")
def list_skill_progress(
    limit: int = 100,
    offset: int = 0,
    student_id: UUID | None = None,
    subject: str | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _page(limit, offset)
    rows, total = me_repo.list_skill_progress(conn, scope, {"student_id": student_id, "subject": subject}, limit, offset)
    return _envelope(rows, total, limit, offset)


# ---- batch 3 ---------------------------------------------------------------


class LessonLogIn(BaseModel):
    schedule_id: UUID
    date: dt.date
    status: Literal["تمت", "مؤجلة", "ملغاة"]  # chk_lesson_logs_status
    covered: str | None = None
    homework: str | None = None
    notes: str | None = None


@router.get("/installments")
def list_installments(
    limit: int = 100,
    offset: int = 0,
    student_id: UUID | None = None,
    status: str | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _only(scope, "guardian")
    _page(limit, offset)
    rows, total = me_repo.list_installments(conn, scope, {"student_id": student_id, "status": status}, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/receipts")
def list_receipts(limit: int = 100, offset: int = 0, student_id: UUID | None = None, scope: Scope = _SCOPE, conn=_CONN) -> dict:
    _only(scope, "guardian")
    _page(limit, offset)
    rows, total = me_repo.list_receipts(conn, scope, {"student_id": student_id}, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.post("/lesson-logs")
def post_lesson_log(body: LessonLogIn, scope: Scope = _SCOPE, conn=_CONN) -> dict:
    _only(scope, "teacher")
    return row_to_json(me_repo.upsert_lesson_log(conn, scope, body.model_dump()))
