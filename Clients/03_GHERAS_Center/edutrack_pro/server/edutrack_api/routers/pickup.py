"""Child pickup flow (Milestone M1): guardian delegates, pickup requests, gate handover."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends
from pydantic import BaseModel, field_validator

from edutrack_api.auth import current_user, require_roles
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.repositories import pickup_repo
from edutrack_api.scope import resolve_scope
from edutrack_api.serializers import row_to_json
from edutrack_api.services import pickup as pickup_service

router = APIRouter(prefix="/pickup")

_CONN = Depends(get_conn)
_GUARDIAN = Depends(require_roles("guardian"))
_SUPER_OR_MGR = Depends(require_roles("supervisor", "manager"))
_TEACHER = Depends(require_roles("teacher"))
_ANY = Depends(current_user)
_TZ = ZoneInfo("Asia/Riyadh")


class DelegateIn(BaseModel):
    student_id: UUID
    name: str
    national_id: str
    phone: str
    relation: str
    client_request_id: UUID | None = None


class PickupRequestIn(BaseModel):
    student_id: UUID
    delegate_id: UUID | None = None
    is_early_leave: bool = False
    client_request_id: UUID | None = None


class ReadyIn(BaseModel):
    prep_checklist: dict = {}
    teacher_note: str | None = None


class HandoverIn(BaseModel):
    verified_receiver_id_no: str
    verified_receiver_name: str
    verified_receiver_phone: str
    verified_receiver_relation: str


class CancelIn(BaseModel):
    cancel_reason: str

    @field_validator("cancel_reason")
    @classmethod
    def not_blank(cls, v: str) -> str:
        s = (v or "").strip()
        if not s:
            raise ValueError("سبب الإلغاء مطلوب")
        return s


class FailIn(BaseModel):
    failure_reason: str

    @field_validator("failure_reason")
    @classmethod
    def not_blank(cls, v: str) -> str:
        s = (v or "").strip()
        if not s:
            raise ValueError("سبب التعذر مطلوب")
        return s


@router.post("/delegates")
def create_delegate(body: DelegateIn, conn=_CONN, user: dict = _GUARDIAN) -> dict:
    resolve_scope(conn, user).assert_students([body.student_id])
    delegate = pickup_service.create_delegate_service(
        conn, user, str(body.student_id), body.name, body.national_id,
        body.phone, body.relation,
        str(body.client_request_id) if body.client_request_id else None,
    )
    return row_to_json(dict(delegate))


@router.delete("/delegates/{delegate_id}")
def delete_delegate(delegate_id: UUID, conn=_CONN, user: dict = _GUARDIAN) -> dict:
    delegate = pickup_repo.get_delegate_by_id(conn, str(delegate_id))
    if not delegate:
        raise ApiError(404, "delegate_not_found", "المفوض غير موجود")
    resolve_scope(conn, user).assert_students([delegate["student_id"]])
    pickup_service.delete_delegate_service(conn, user, str(delegate_id))
    return {"deleted": True}


@router.post("/requests")
def create_request(body: PickupRequestIn, conn=_CONN, user: dict = _GUARDIAN) -> dict:
    resolve_scope(conn, user).assert_students([body.student_id])
    request = pickup_service.create_pickup_request_service(
        conn, user, str(body.student_id),
        str(body.delegate_id) if body.delegate_id else None,
        body.is_early_leave,
        str(body.client_request_id) if body.client_request_id else None,
    )
    full_request = pickup_repo.get_request_by_id(conn, str(request["id"]), mask=False) or dict(request)
    return row_to_json(pickup_repo.mask_request_row(full_request))


@router.get("/requests/active")
def list_active_requests(conn=_CONN, user: dict = _ANY) -> dict:
    today = datetime.now(_TZ).date()
    if user["role"] in ("supervisor", "manager"):
        rows = pickup_repo.list_active_requests(conn, user["branch_id"], today)
    elif user["role"] == "teacher":
        scope = resolve_scope(conn, user)
        rows = pickup_repo.list_active_requests(
            conn, user["branch_id"], today,
            room_ids=[str(rid) for rid in scope.room_ids],
            is_teacher=True,
        )
    else:
        # Guardian/student callers only see requests for their own children/self;
        # their room scope is empty, so the branch queue is filtered, not room-queried.
        scope = resolve_scope(conn, user)
        all_rows = pickup_repo.list_active_requests(conn, user["branch_id"], today)
        allowed_student_ids = {str(sid) for sid in scope.student_ids}
        rows = [row for row in all_rows if str(row["student_id"]) in allowed_student_ids]
    return {"requests": [row_to_json(pickup_repo.mask_request_row(row)) for row in rows]}


@router.get("/requests/{request_id}")
def get_request(request_id: UUID, conn=_CONN, user: dict = _ANY) -> dict:
    request = pickup_repo.get_request_by_id(conn, str(request_id), mask=False)
    if not request:
        raise ApiError(404, "pickup_not_found", "طلب الاستلام غير موجود")

    if user["role"] in ("supervisor", "manager"):
        if str(request["branch_id"]) != str(user["branch_id"]):
            raise ApiError(403, "forbidden_branch", "ليس لديك صلاحية على هذا الفرع")
    elif user["role"] == "teacher":
        resolve_scope(conn, user).assert_rooms([request["room_id"]])
    else:
        resolve_scope(conn, user).assert_students([request["student_id"]])

    return row_to_json(pickup_repo.mask_request_row(request))


@router.post("/requests/{request_id}/approve-early-leave")
def approve_early_leave(request_id: UUID, conn=_CONN, user: dict = _SUPER_OR_MGR) -> dict:
    request = pickup_service.approve_early_leave_service(conn, user, str(request_id))
    full_request = pickup_repo.get_request_by_id(conn, str(request_id), mask=False) or dict(request)
    return row_to_json(pickup_repo.mask_request_row(full_request))


@router.post("/requests/{request_id}/start-prep")
def start_prep(request_id: UUID, conn=_CONN, user: dict = _TEACHER) -> dict:
    request = pickup_repo.get_request_by_id(conn, str(request_id))
    if not request:
        raise ApiError(404, "pickup_not_found", "طلب الاستلام غير موجود")
    resolve_scope(conn, user).assert_rooms([request["room_id"]])
    prepared = pickup_service.start_prep_service(conn, user, str(request_id))
    full_request = pickup_repo.get_request_by_id(conn, str(request_id), mask=False) or dict(prepared)
    return row_to_json(pickup_repo.mask_request_row(full_request))


@router.post("/requests/{request_id}/ready")
def mark_ready(
    request_id: UUID, body: ReadyIn | None = None, conn=_CONN, user: dict = _TEACHER,
) -> dict:
    request = pickup_repo.get_request_by_id(conn, str(request_id))
    if not request:
        raise ApiError(404, "pickup_not_found", "طلب الاستلام غير موجود")
    resolve_scope(conn, user).assert_rooms([request["room_id"]])
    body = body or ReadyIn()
    readied = pickup_service.mark_ready_service(
        conn, user, str(request_id), body.prep_checklist, body.teacher_note,
    )
    full_request = pickup_repo.get_request_by_id(conn, str(request_id), mask=False) or dict(readied)
    return row_to_json(pickup_repo.mask_request_row(full_request))


@router.post("/requests/{request_id}/handover")
def handover(request_id: UUID, body: HandoverIn, conn=_CONN, user: dict = _SUPER_OR_MGR) -> dict:
    # dismissal_method is fixed server-side; the gate records child pickups only.
    request = pickup_service.handover_service(
        conn, user, str(request_id),
        body.verified_receiver_id_no, body.verified_receiver_name,
        body.verified_receiver_phone, body.verified_receiver_relation,
        dismissal_method="pickup",
    )
    full_request = pickup_repo.get_request_by_id(conn, str(request_id), mask=False) or dict(request)
    return row_to_json(pickup_repo.mask_request_row(full_request))


@router.post("/requests/{request_id}/cancel")
def cancel_request(request_id: UUID, body: CancelIn, conn=_CONN, user: dict = _GUARDIAN) -> dict:
    request = pickup_repo.get_request_by_id(conn, str(request_id))
    if not request:
        raise ApiError(404, "pickup_not_found", "طلب الاستلام غير موجود")
    resolve_scope(conn, user).assert_students([request["student_id"]])
    cancelled = pickup_service.cancel_pickup_service(conn, user, str(request_id), body.cancel_reason)
    full_request = pickup_repo.get_request_by_id(conn, str(request_id), mask=False) or dict(cancelled)
    return row_to_json(pickup_repo.mask_request_row(full_request))


@router.post("/requests/{request_id}/fail")
def fail_request(request_id: UUID, body: FailIn, conn=_CONN, user: dict = _SUPER_OR_MGR) -> dict:
    request = pickup_service.fail_pickup_service(conn, user, str(request_id), body.failure_reason)
    full_request = pickup_repo.get_request_by_id(conn, str(request_id), mask=False) or dict(request)
    return row_to_json(pickup_repo.mask_request_row(full_request))
