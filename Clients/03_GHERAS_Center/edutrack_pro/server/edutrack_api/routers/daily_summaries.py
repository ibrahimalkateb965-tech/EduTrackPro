"""Daily classroom summaries and private student notes endpoints (Milestone M4)."""

from __future__ import annotations

from datetime import date
from typing import Any
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from edutrack_api.auth import current_user, require_roles
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.repositories import daily_summaries_repo
from edutrack_api.scope import resolve_scope
from edutrack_api.serializers import row_to_json

router = APIRouter(prefix="/daily-summaries", tags=["daily-summaries"])

_CONN = Depends(get_conn)
_USER = Depends(current_user)
_STAFF = Depends(require_roles("teacher", "supervisor", "manager"))


class StudentNoteIn(BaseModel):
    student_id: UUID
    private_note: str = Field(min_length=1, max_length=1000)


class DailySummaryIn(BaseModel):
    room_id: UUID
    summary_date: date
    subject: str = Field(min_length=2, max_length=100)
    title: str = Field(min_length=2, max_length=200)
    class_summary: str = Field(min_length=5)
    homework_summary: str | None = None
    attachments: list[dict[str, Any]] = []
    is_published: bool = False
    student_notes: list[StudentNoteIn] = []


def _broadcast_summary_notifications(conn, summary_id: UUID, room_id: UUID, title: str, branch_id: UUID, sender_id: UUID):
    """Notify guardians of all active students in the classroom about published summary."""
    guardian_users = conn.execute("""
        SELECT DISTINCT u.id AS user_id
        FROM students s
        JOIN student_guardians sg ON sg.student_id = s.id
        JOIN users u ON u.guardian_id = sg.guardian_id AND u.deleted_at IS NULL
        WHERE s.room_id = %s AND s.status = 'active' AND s.deleted_at IS NULL
    """, (room_id,)).fetchall()

    for gu in guardian_users:
        conn.execute("""
            INSERT INTO notifications (
                branch_id, user_id, kind, title, body, target_type, target_id,
                priority, sender_user_id, action_url, sent_at
            ) VALUES (
                %s, %s, 'announcement', %s, %s, 'daily_summary', %s,
                'normal', %s, %s, now()
            )
        """, (
            branch_id, gu["user_id"], f"ملخص الدرس اليومي: {title}",
            "تم نشر ملخص الدرس الصفي والواجبات المطلوبة لهذا اليوم.",
            summary_id, sender_id, f"gheras://daily-summaries/{summary_id}"
        ))


@router.get("", dependencies=[_STAFF])
def list_daily_summaries(
    room_id: UUID | None = None,
    summary_date: date | None = None,
    is_published: bool | None = None,
    conn=_CONN,
    user=_USER,
):
    """List classroom summaries for teachers and supervisors."""
    # B2 & M1: resolve scope for teacher, bypass for supervisors/managers
    scope = resolve_scope(conn, user) if user["role"] == "teacher" else None
    room_ids = None
    if user["role"] == "teacher" and scope:
        if room_id:
            scope.assert_rooms([room_id])
        else:
            room_ids = scope.room_ids

    summaries = daily_summaries_repo.list_daily_summaries(
        conn=conn,
        room_id=room_id,
        room_ids=room_ids,
        summary_date=summary_date,
        is_published=is_published,
    )
    return [row_to_json(s) for s in summaries]


@router.get("/{summary_id}", dependencies=[_STAFF])
def get_daily_summary(
    summary_id: UUID,
    conn=_CONN,
    user=_USER,
):
    """Get single daily summary with all private student notes."""
    # B2: resolve scope for teacher, bypass for supervisors/managers
    scope = resolve_scope(conn, user) if user["role"] == "teacher" else None
    summary = daily_summaries_repo.get_daily_summary_by_id(conn, summary_id, include_notes=True)
    if not summary:
        raise ApiError(status=404, code="not_found", message="ملخص الدرس غير موجود")

    if user["role"] == "teacher" and scope:
        scope.assert_rooms([summary["room_id"]])

    return row_to_json(summary)


@router.post("", dependencies=[_STAFF])
def upsert_daily_summary(
    payload: DailySummaryIn,
    conn=_CONN,
    user=_USER,
):
    """Create or update daily classroom summary and private student notes."""
    # B2: resolve scope for teacher, bypass for supervisors/managers
    scope = resolve_scope(conn, user) if user["role"] == "teacher" else None

    # Room access verification
    if user["role"] == "teacher" and scope:
        scope.assert_rooms([payload.room_id])

    # Verify room exists and get branch
    room_row = conn.execute(
        "SELECT id, branch_id FROM rooms WHERE id = %s AND deleted_at IS NULL",
        (payload.room_id,),
    ).fetchone()
    if not room_row:
        raise ApiError(status=404, code="not_found", message="القاعة غير موجودة")

    branch_id = room_row["branch_id"]

    # Verify all students belong to this classroom
    if payload.student_notes:
        student_ids = [n.student_id for n in payload.student_notes]
        valid_count = conn.execute("""
            SELECT COUNT(*) AS cnt FROM students
            WHERE id = ANY(%s) AND room_id = %s AND deleted_at IS NULL
        """, (student_ids, payload.room_id)).fetchone()["cnt"]

        if valid_count != len(student_ids):
            raise ApiError(status=422, code="invalid_students", message="بعض الطلاب لا ينتمون لهذه القاعة")

    # M4: check if already published prior to upsert to prevent duplicate notifications
    existing_row = conn.execute(
        "SELECT id, is_published, published_at FROM daily_summaries WHERE room_id = %s AND summary_date = %s AND subject = %s AND deleted_at IS NULL",
        (payload.room_id, payload.summary_date, payload.subject),
    ).fetchone()
    was_published = bool(existing_row and (existing_row["is_published"] or existing_row.get("published_at")))

    summary = daily_summaries_repo.upsert_daily_summary(
        conn=conn,
        branch_id=branch_id,
        room_id=payload.room_id,
        teacher_user_id=user["id"],
        summary_date=payload.summary_date,
        subject=payload.subject,
        title=payload.title,
        class_summary=payload.class_summary,
        homework_summary=payload.homework_summary,
        attachments=payload.attachments,
        is_published=payload.is_published,
        student_notes=[n.model_dump() for n in payload.student_notes],
    )

    if payload.is_published and not was_published:
        _broadcast_summary_notifications(
            conn=conn,
            summary_id=summary["id"],
            room_id=payload.room_id,
            title=payload.title,
            branch_id=branch_id,
            sender_id=user["id"]
        )
        conn.commit()

    return row_to_json(summary)


@router.post("/{summary_id}/publish", dependencies=[_STAFF])
def publish_summary(
    summary_id: UUID,
    conn=_CONN,
    user=_USER,
):
    """Publish an existing draft summary and alert guardians."""
    # B2: resolve scope for teacher, bypass for supervisors/managers
    scope = resolve_scope(conn, user) if user["role"] == "teacher" else None
    existing = daily_summaries_repo.get_daily_summary_by_id(conn, summary_id, include_notes=False)
    if not existing:
        raise ApiError(status=404, code="not_found", message="ملخص الدرس غير موجود")

    if user["role"] == "teacher" and scope:
        scope.assert_rooms([existing["room_id"]])

    # M4: If already published, return without re-broadcasting
    if existing["is_published"]:
        return row_to_json(existing)

    updated = daily_summaries_repo.publish_daily_summary(conn, summary_id)

    _broadcast_summary_notifications(
        conn=conn,
        summary_id=summary_id,
        room_id=existing["room_id"],
        title=existing["title"],
        branch_id=existing["branch_id"],
        sender_id=user["id"]
    )
    conn.commit()

    return row_to_json(updated)
