from uuid import UUID

from fastapi import APIRouter, Depends

from edutrack_api.auth import require_roles
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.scope import Scope, resolve_scope
from ..services.attendance_service import AttendanceService

router = APIRouter()
_ROLES = Depends(require_roles("manager", "supervisor", "teacher"))
_STAFF_ROLES = Depends(require_roles("manager", "supervisor"))  # staff attendance drives payroll deductions


def _teacher_scope(conn, user: dict) -> Scope | None:
    return resolve_scope(conn, user) if user["role"] == "teacher" else None


@router.post("/attendance/students")
def save_students(items: list[dict], conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    if user["role"] == "supervisor" and not user.get("permissions", {}).get("attendance", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية تسجيل حضور الطلاب")
    return AttendanceService.save_students(conn, items, user, scope=_teacher_scope(conn, user))


@router.put("/attendance/students/draft")
def draft_students(
    payload: dict,
    conn=Depends(get_conn),
    user: dict = _ROLES,
) -> dict:
    if user["role"] == "supervisor" and not user.get("permissions", {}).get("attendance", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية تسجيل حضور الطلاب")
    scope = _teacher_scope(conn, user)
    return AttendanceService.draft_students(conn, payload, user, scope=scope)


@router.post("/attendance/rooms/{room_id}/days/{date}/approve")
def approve_room_attendance(
    room_id: UUID,
    date: str,
    conn=Depends(get_conn),
    user: dict = _ROLES,
) -> dict:
    if user["role"] == "supervisor" and not user.get("permissions", {}).get("attendance", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية تسجيل حضور الطلاب")
    scope = _teacher_scope(conn, user)
    return AttendanceService.approve_room_attendance(conn, room_id, date, user, scope=scope)


@router.post("/attendance/staff")
def save_staff(items: list[dict], conn=Depends(get_conn), user: dict = _STAFF_ROLES) -> dict:
    if user["role"] == "supervisor" and not user.get("permissions", {}).get("attendance", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية تسجيل حضور الموظفين")
    return AttendanceService.save_staff(conn, items, user)


@router.get("/daily-evaluations")
@router.get("/evaluations/daily")
def list_daily_evaluations(
    date: str | None = None,
    room_id: str | None = None,
    student_id: str | None = None,
    conn=Depends(get_conn),
    user: dict = _ROLES,
) -> dict:
    if user["role"] == "supervisor" and not user.get("permissions", {}).get("daily_evaluation", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم اليومي")
    scope = _teacher_scope(conn, user)
    return AttendanceService.list_daily_evaluations(
        conn, date=date, room_id=room_id, student_id=student_id, scope=scope
    )


@router.post("/daily-evaluations")
@router.post("/evaluations/daily")
def save_daily_evaluations(
    items: list[dict],
    conn=Depends(get_conn),
    user: dict = _ROLES,
) -> dict:
    if user["role"] == "supervisor" and not user.get("permissions", {}).get("daily_evaluation", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم اليومي")
    scope = _teacher_scope(conn, user)
    return AttendanceService.save_daily_evaluations(conn, items, user, scope=scope)
