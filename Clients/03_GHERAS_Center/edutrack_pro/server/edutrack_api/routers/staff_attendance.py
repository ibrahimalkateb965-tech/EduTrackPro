"""FastAPI endpoints for Staff Geofenced Attendance & Admin Management (Milestone M6)."""

from __future__ import annotations

from datetime import date as dt_date, datetime
from typing import Any, Literal
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from edutrack_api.auth import current_user, require_roles
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.repositories import staff_attendance_repo
from edutrack_api.serializers import row_to_json

router = APIRouter(prefix="/staff-attendance", tags=["staff-attendance"])

_CONN = Depends(get_conn)
_USER = Depends(current_user)
_STAFF_ROLES = Depends(require_roles("teacher", "supervisor", "manager"))
_SUPERVISOR_ROLES = Depends(require_roles("supervisor", "manager"))


class GeoPunchIn(BaseModel):
    latitude: float = Field(ge=-90.0, le=90.0)
    longitude: float = Field(ge=-180.0, le=180.0)
    accuracy: float = Field(ge=0.0)


AttendanceStatus = Literal[
    "حاضر", "غائب", "متأخر", "مستأذن", "انصراف مبكر", "ناقص",
    "present", "late", "absent", "excused", "early_leave", "incomplete",
]


class AttendanceOverrideIn(BaseModel):
    attendance_id: UUID
    status: AttendanceStatus
    override_reason: str = Field(min_length=3)
    check_in_time: datetime | None = None
    check_out_time: datetime | None = None
    deduction: float = Field(default=0.0, ge=0.0)
    note: str | None = None


@router.post("/check-in", dependencies=[_STAFF_ROLES])
def staff_check_in(
    payload: GeoPunchIn,
    conn=_CONN,
    user=_USER,
) -> dict[str, Any]:
    """Staff GPS check-in validated by center coordinates and radius."""
    record = staff_attendance_repo.check_in_staff(
        conn=conn,
        user=user,
        latitude=payload.latitude,
        longitude=payload.longitude,
        accuracy=payload.accuracy,
    )
    conn.commit()
    return row_to_json(record)


@router.post("/check-out", dependencies=[_STAFF_ROLES])
def staff_check_out(
    payload: GeoPunchIn,
    conn=_CONN,
    user=_USER,
) -> dict[str, Any]:
    """Staff GPS check-out validated by center coordinates."""
    record = staff_attendance_repo.check_out_staff(
        conn=conn,
        user=user,
        latitude=payload.latitude,
        longitude=payload.longitude,
        accuracy=payload.accuracy,
    )
    conn.commit()
    return row_to_json(record)


@router.get("/my-status", dependencies=[_STAFF_ROLES])
def get_my_status(
    conn=_CONN,
    user=_USER,
) -> dict[str, Any]:
    """Retrieve today's shift and attendance check-in status for the logged-in staff member."""
    status_data = staff_attendance_repo.get_my_today_status(conn, user)
    return row_to_json(status_data)


@router.get("/my-history", dependencies=[_STAFF_ROLES])
def get_my_history(
    month: str | None = None,
    conn=_CONN,
    user=_USER,
) -> dict[str, Any]:
    """Retrieve monthly attendance records and aggregate statistics."""
    if not month:
        now_dt = datetime.now()
        month = now_dt.strftime("%Y-%m")

    history = staff_attendance_repo.get_my_attendance_history(conn, user, month)
    return row_to_json(history)


@router.get("/supervisor/summary", dependencies=[_SUPERVISOR_ROLES])
def list_attendance_for_supervisor(
    date: dt_date | None = None,
    month: str | None = None,
    staff_id: UUID | None = None,
    conn=_CONN,
    user=_USER,
) -> list[dict[str, Any]]:
    """Supervisor/Admin overview across all staff."""
    records = staff_attendance_repo.list_staff_attendance_for_supervisor(
        conn=conn,
        date=date,
        month=month,
        staff_id=staff_id,
    )
    return [row_to_json(r) for r in records]


@router.post("/supervisor/override", dependencies=[_SUPERVISOR_ROLES])
def override_staff_attendance(
    payload: AttendanceOverrideIn,
    conn=_CONN,
    user=_USER,
) -> dict[str, Any]:
    """Manual supervisor correction adhering to Rule M11 anti-self-approval."""
    updated = staff_attendance_repo.override_staff_attendance(
        conn=conn,
        approver_user=user,
        attendance_id=payload.attendance_id,
        status=payload.status,
        override_reason=payload.override_reason,
        check_in_time=payload.check_in_time and staff_attendance_repo._as_riyadh(payload.check_in_time),
        check_out_time=payload.check_out_time and staff_attendance_repo._as_riyadh(payload.check_out_time),
        deduction=payload.deduction,
        note=payload.note,
    )
    conn.commit()
    return row_to_json(updated)
