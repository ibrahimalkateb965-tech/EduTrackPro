"""Router for modern assignments, submissions, reviews, and reminders (Milestone M2)."""

from __future__ import annotations

import datetime as dt
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from edutrack_api.auth import current_user
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.repositories import assignment_repo
from edutrack_api.scope import Scope, require_scope

router = APIRouter()
_SCOPE = Depends(require_scope)
_CONN = Depends(get_conn)


class AssignmentCreateIn(BaseModel):
    id: UUID | None = None
    title: str = Field(..., min_length=1)
    subject: str = "القرآن الكريم"
    instructions: str = ""
    room_id: UUID | None = None
    student_ids: list[UUID] | None = None
    due_date: dt.date | None = None
    is_draft: bool = False
    files: list[dict] = []


class SubmitIn(BaseModel):
    student_id: UUID
    client_request_id: UUID | None = None
    text_content: str | None = None
    files: list[dict] = []


class ReviewIn(BaseModel):
    status: str
    grade_level: str | None = None
    revision_note: str | None = None
    teacher_feedback_key: str | None = None


class ReminderIn(BaseModel):
    kind: str = "bulk"
    student_id: UUID | None = None
    dry_run: bool = False
    client_request_id: UUID | None = None
    message_text: str | None = None


@router.post("/assignments")
def create_assignment(
    body: AssignmentCreateIn,
    scope: Scope = _SCOPE,
    conn = _CONN,
):
    """Create or update assignment with staged media attachments."""
    return assignment_repo.create_assignment(conn, scope, body.model_dump())


@router.post("/assignments/{id}/publish")
def publish_assignment(
    id: UUID,
    scope: Scope = _SCOPE,
    conn = _CONN,
):
    """Publish a draft assignment (idempotent, M-1).

    Verifies owner and non-deleted status; returns 404 for foreign or missing ID.
    """
    return assignment_repo.publish_assignment(conn, scope, id)


@router.get("/assignments/{id}")
def get_assignment(
    id: UUID,
    student_id: UUID | None = None,
    scope: Scope = _SCOPE,
    conn = _CONN,
):
    """Get single assignment details with files, enforcing role scopes."""
    ass = assignment_repo.get_assignment_by_id(conn, id)
    if not ass:
        raise ApiError(404, "not_found", "الواجب غير موجود")

    if scope.role == "guardian":
        if ass["is_draft"]:
            raise ApiError(404, "not_found", "الواجب غير موجود")
        if student_id:
            if student_id not in scope.student_ids:
                raise ApiError(403, "out_of_scope", "الطالب خارج نطاق ولي الأمر")
            enrolled = conn.execute(
                "SELECT 1 FROM assignment_students WHERE assignment_id = %s AND student_id = %s AND deleted_at IS NULL",
                (id, student_id),
            ).fetchone()
            if not enrolled:
                raise ApiError(404, "not_found", "الواجب غير مخصص لهذا الطالب")
    elif scope.role == "teacher":
        if ass["teacher_user_id"] != scope.user_id:
            raise ApiError(403, "out_of_scope", "الواجب غير متاح لهذه المعلمة")

    return ass


@router.get("/assignments/{id}/submissions")
def get_assignment_submissions(
    id: UUID,
    scope: Scope = _SCOPE,
    conn = _CONN,
):
    """Get submissions summary and student rows for teacher monitoring (T05)."""
    if scope.role != "teacher":
        raise ApiError(403, "forbidden", "المعلمة فقط مصرح لها بمتابعة تسليمات الواجب")

    ass = conn.execute(
        "SELECT * FROM assignments WHERE id = %s AND deleted_at IS NULL",
        (id,),
    ).fetchone()
    if not ass:
        raise ApiError(404, "not_found", "الواجب غير موجود")
    if ass["teacher_user_id"] != scope.user_id:
        raise ApiError(403, "out_of_scope", "الواجب خارج نطاق المعلمة")

    rows = conn.execute("""
        SELECT s.id AS student_id, s.name AS student_name,
               sb.id AS submission_id, sb.attempt_number, sb.status, sb.grade_level,
               sb.is_late, sb.submitted_at, sb.revision_note, sb.teacher_feedback_key
        FROM assignment_students ast
        JOIN students s ON s.id = ast.student_id
        LEFT JOIN LATERAL (
            SELECT sb2.* FROM submissions sb2 
            WHERE sb2.assignment_id = ast.assignment_id AND sb2.student_id = ast.student_id AND sb2.deleted_at IS NULL
            ORDER BY sb2.attempt_number DESC LIMIT 1
        ) sb ON TRUE
        WHERE ast.assignment_id = %s AND ast.deleted_at IS NULL
        ORDER BY s.name
    """, (id,)).fetchall()

    not_submitted = 0
    pending_review = 0
    reviewed = 0
    items = []
    for r in rows:
        st = r["status"]
        if st is None:
            display_status = "pending"
            not_submitted += 1
        elif st == "submitted":
            display_status = "submitted"
            pending_review += 1
        elif st == "graded":
            display_status = "graded"
            reviewed += 1
        elif st in ("revision_requested", "returned"):
            display_status = "revision_requested"
            pending_review += 1
        else:
            display_status = "pending"
            not_submitted += 1

        d = dict(r)
        d["display_status"] = display_status
        items.append(d)

    return {
        "assignment_id": str(id),
        "title": ass["title"],
        "stats": {
            "not_submitted_count": not_submitted,
            "pending_review_count": pending_review,
            "reviewed_count": reviewed,
            "total_count": len(items),
        },
        "submissions": items,
    }


@router.post("/assignments/{id}/submit")
def submit_assignment(
    id: UUID,
    body: SubmitIn,
    scope: Scope = _SCOPE,
    conn = _CONN,
):
    """Sole submit surface for guardians."""
    return assignment_repo.submit_assignment_cas(
        conn, scope, id, body.student_id, body.client_request_id,
        body.files, body.text_content
    )


@router.post("/submissions/{id}/review")
def review_submission(
    id: UUID,
    body: ReviewIn,
    scope: Scope = _SCOPE,
    conn = _CONN,
):
    """Sole review & grade surface for teachers."""
    return assignment_repo.review_submission_cas(
        conn, scope, id, body.status, body.grade_level,
        body.revision_note, body.teacher_feedback_key
    )


@router.post("/assignments/{id}/reminders")
def send_assignment_reminders(
    id: UUID,
    body: ReminderIn,
    scope: Scope = _SCOPE,
    conn = _CONN,
):
    """Send reminders with dual cooldown and name preview (R4-4, T05)."""
    return assignment_repo.send_assignment_reminders(
        conn, scope, id, body.kind, body.student_id,
        body.dry_run, body.client_request_id, body.message_text
    )
