"""Monthly student reports and evaluations endpoints (Milestone M5)."""

from __future__ import annotations

import re
from typing import Any, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field

from edutrack_api.auth import current_user, require_permission, require_roles
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.repositories import monthly_reports_repo
from edutrack_api.scope import resolve_scope
from edutrack_api.serializers import row_to_json
from edutrack_api.services import monthly_reports as monthly_reports_service

router = APIRouter(prefix="/monthly-reports", tags=["monthly-reports"])

_CONN = Depends(get_conn)
_USER = Depends(current_user)
_STAFF = Depends(require_roles("teacher", "supervisor", "manager"))
_SUPERVISOR_OR_MANAGER = Depends(require_roles("supervisor", "manager"))
_MONTHLY_EVALUATION = Depends(require_permission("monthly_evaluation"))
_REPORT_READER = Depends(require_roles("teacher", "supervisor", "manager", "guardian"))

MONTH_REGEX = r"^[0-9]{4}-(0[1-9]|1[0-2])$"


class SubjectRatingIn(BaseModel):
    subject: str = Field(min_length=1, max_length=100)
    rating: str = Field(min_length=1, max_length=50)
    notes: str | None = None


class MonthlyReportIn(BaseModel):
    student_id: UUID
    room_id: UUID
    month: str = Field(pattern=MONTH_REGEX)
    attendance_stats: dict[str, Any] = Field(default_factory=dict)
    subject_ratings: list[SubjectRatingIn] = Field(default_factory=list)
    strengths: str | None = None
    improvements: str | None = None
    recommendations: str | None = None
    action: Literal["save_draft", "submit"] = "save_draft"


class ReportReviewIn(BaseModel):
    action: Literal["approve", "request_revision"]
    revision_notes: str | None = None


class BatchPublishIn(BaseModel):
    room_id: UUID
    month: str = Field(pattern=MONTH_REGEX)


@router.get("/auto-stats", dependencies=[_STAFF])
def get_auto_attendance_stats(
    student_id: UUID,
    month: str,
    conn=_CONN,
    user=_USER,
):
    """Retrieve auto-aggregated monthly attendance statistics for a student."""
    if not re.match(MONTH_REGEX, month):
        raise ApiError(422, "INVALID_MONTH", "تنسيق الشهر غير صالح، يجب أن يكون YYYY-MM")

    # Teacher scope validation
    if user["role"] == "teacher":
        scope = resolve_scope(conn, user)
        # Check student belongs to a classroom within teacher's scope
        st_row = conn.execute(
            "SELECT room_id FROM students WHERE id = %s AND deleted_at IS NULL",
            (student_id,),
        ).fetchone()
        if not st_row or (scope.room_ids is not None and st_row["room_id"] not in scope.room_ids):
            raise ApiError(403, "FORBIDDEN", "غير مصرح لك بالاطلاع على إحصائيات هذا الطالب")

    return monthly_reports_repo.compute_student_attendance_stats(conn, student_id, month)


@router.get("", dependencies=[_STAFF])
def list_monthly_reports(
    room_id: UUID | None = None,
    month: str | None = None,
    status: str | None = None,
    student_id: UUID | None = None,
    conn=_CONN,
    user=_USER,
):
    """List monthly reports for staff members with scope restrictions for teachers."""
    scope = resolve_scope(conn, user) if user["role"] == "teacher" else None
    room_ids = None

    if user["role"] == "teacher":
        if scope and scope.room_ids is not None:
            room_ids = scope.room_ids
            if room_id and room_id not in room_ids:
                return []
        else:
            return []

    reports = monthly_reports_repo.list_monthly_reports(
        conn=conn,
        room_id=room_id,
        room_ids=room_ids,
        month=month,
        status=status,
        student_id=student_id,
    )
    return [row_to_json(r) for r in reports]


@router.get("/{report_id}", dependencies=[_STAFF])
def get_monthly_report(
    report_id: UUID,
    conn=_CONN,
    user=_USER,
):
    """Retrieve detailed single monthly report."""
    report = monthly_reports_repo.get_monthly_report_by_id(conn, report_id)
    if not report:
        raise ApiError(404, "NOT_FOUND", "التقرير الشهري غير موجود")

    if user["role"] == "teacher":
        scope = resolve_scope(conn, user)
        if scope.room_ids is not None and report["room_id"] not in scope.room_ids:
            raise ApiError(403, "FORBIDDEN", "غير مصرح لك بالاطلاع على تقرير هذا الفصل")

    return row_to_json(report)


@router.post("", dependencies=[_STAFF])
def upsert_monthly_report(
    payload: MonthlyReportIn,
    conn=_CONN,
    user=_USER,
):
    """Create or update a student monthly report draft or submit it for supervisor approval."""
    # Teacher scope verification
    if user["role"] == "teacher":
        scope = resolve_scope(conn, user)
        if scope.room_ids is not None and payload.room_id not in scope.room_ids:
            raise ApiError(403, "FORBIDDEN", "غير مصرح لك بإعداد تقارير لهذا الفصل")

    # Verify student belongs to classroom
    st = conn.execute(
        "SELECT id, room_id, branch_id FROM students WHERE id = %s AND deleted_at IS NULL",
        (payload.student_id,),
    ).fetchone()
    if not st:
        raise ApiError(404, "NOT_FOUND", "الطالب غير موجود")

    if st["room_id"] != payload.room_id:
        raise ApiError(422, "INVALID_ROOM", "الطالب لا ينتمي إلى هذا الفصل")

    branch_id = st["branch_id"]

    # Stats always come from the attendance table; client-sent values are ignored
    stats = monthly_reports_repo.compute_student_attendance_stats(conn, payload.student_id, payload.month)

    # Sanitize ratings
    cleaned_ratings = [r.model_dump() for r in payload.subject_ratings]

    try:
        report = monthly_reports_repo.upsert_monthly_report(
            conn=conn,
            branch_id=branch_id,
            student_id=payload.student_id,
            room_id=payload.room_id,
            month=payload.month,
            teacher_user_id=user["id"],
            attendance_stats=stats,
            subject_ratings=cleaned_ratings,
            strengths=payload.strengths,
            improvements=payload.improvements,
            recommendations=payload.recommendations,
            action=payload.action,
        )
        conn.commit()
    except ValueError as e:
        raise ApiError(422, "VALIDATION_ERROR", str(e))

    return row_to_json(report)


@router.post("/{report_id}/review", dependencies=[_SUPERVISOR_OR_MANAGER, _MONTHLY_EVALUATION])
def review_monthly_report(
    report_id: UUID,
    payload: ReportReviewIn,
    conn=_CONN,
    user=_USER,
):
    """Supervisor approval or revision request gate."""
    try:
        report = monthly_reports_repo.review_monthly_report(
            conn=conn,
            report_id=report_id,
            reviewer_user_id=user["id"],
            action=payload.action,
            revision_notes=payload.revision_notes,
        )
        conn.commit()
    except ValueError as e:
        raise ApiError(422, "VALIDATION_ERROR", str(e))

    return row_to_json(report)


@router.post("/{report_id}/publish", dependencies=[_SUPERVISOR_OR_MANAGER, _MONTHLY_EVALUATION])
def publish_monthly_report(
    report_id: UUID,
    conn=_CONN,
    user=_USER,
):
    """Publish an approved report making it visible to parents and broadcasting notification."""
    try:
        report = monthly_reports_repo.publish_monthly_report(
            conn=conn,
            report_id=report_id,
            publisher_user_id=user["id"],
        )
        # Broadcast notification
        monthly_reports_service.broadcast_monthly_report_notification(
            conn=conn,
            report_id=report["id"],
            branch_id=report["branch_id"],
            student_id=report["student_id"],
            student_name=report["student_name"],
            month=report["month"],
            sender_user_id=user["id"],
        )
        conn.commit()
    except ValueError as e:
        raise ApiError(422, "VALIDATION_ERROR", str(e))

    return row_to_json(report)


@router.post("/batch-publish", dependencies=[_SUPERVISOR_OR_MANAGER, _MONTHLY_EVALUATION])
def batch_publish_monthly_reports(
    payload: BatchPublishIn,
    conn=_CONN,
    user=_USER,
):
    """Batch publish all approved reports for a classroom and month."""
    published_list = monthly_reports_repo.batch_publish_monthly_reports(
        conn=conn,
        room_id=payload.room_id,
        month=payload.month,
        publisher_user_id=user["id"],
    )

    for rep in published_list:
        monthly_reports_service.broadcast_monthly_report_notification(
            conn=conn,
            report_id=rep["id"],
            branch_id=rep["branch_id"],
            student_id=rep["student_id"],
            student_name=rep["student_name"],
            month=rep["month"],
            sender_user_id=user["id"],
        )

    conn.commit()
    return [row_to_json(r) for r in published_list]


@router.get("/{report_id}/pdf", dependencies=[_REPORT_READER])
async def download_monthly_report_pdf(
    report_id: UUID,
    conn=_CONN,
    user=_USER,
):
    """Download metadata / link for official PDF of the monthly report."""
    report = monthly_reports_repo.get_monthly_report_by_id(conn, report_id)
    if not report:
        raise ApiError(404, "NOT_FOUND", "التقرير غير موجود")

    # If guardian, must be published and child must belong to guardian
    if user["role"] == "guardian":
        if report["status"] != "published":
            raise ApiError(404, "NOT_FOUND", "التقرير غير متاح حالياً")
        scope = resolve_scope(conn, user)
        if scope.student_ids is not None and report["student_id"] not in scope.student_ids:
            raise ApiError(403, "FORBIDDEN", "غير مصرح لك بتحميل تقرير هذا الطالب")

    # If teacher, verify scope
    if user["role"] == "teacher":
        scope = resolve_scope(conn, user)
        if scope.room_ids is not None and report["room_id"] not in scope.room_ids:
            raise ApiError(403, "FORBIDDEN", "غير مصرح لك بتحميل تقرير هذا الفصل")

    return {
        "report_id": str(report_id),
        "student_name": report["student_name"],
        "month": report["month"],
        "pdf_url": f"/api/v1/print/pdf?template=student_report&student_id={report['student_id']}",
        "status": report["status"],
    }
