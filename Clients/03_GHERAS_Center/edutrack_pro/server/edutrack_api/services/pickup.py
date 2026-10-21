"""Pickup business rules; callers enforce role and student access scopes."""

from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

import psycopg.errors

from edutrack_api.audit import write_audit
from edutrack_api.errors import ApiError
from edutrack_api.repositories import pickup_repo
from edutrack_api.services import settings

_TZ = ZoneInfo("Asia/Riyadh")
_DIGIT_TABLE = str.maketrans(
    "\u0660\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669"
    "\u06f0\u06f1\u06f2\u06f3\u06f4\u06f5\u06f6\u06f7\u06f8\u06f9",
    "01234567890123456789",
)


def normalize_digits(s: str | None) -> str:
    if not s:
        return ""
    return "".join(c for c in s.translate(_DIGIT_TABLE) if c.isdigit())


def _require_request(conn, request_id: str) -> dict:
    request = pickup_repo.get_request_by_id(conn, request_id)
    if request is None:
        raise ApiError(404, "pickup_not_found", "طلب الاستلام غير موجود")
    return request


def _audit_transition(conn, user: dict, request: dict | None, action: str) -> dict:
    if request is None:
        raise ApiError(409, "invalid_status", "لا يمكن تنفيذ العملية في الحالة الحالية")
    write_audit(
        conn, user["id"], action, "pickup_requests", request["id"],
        {"student_id": str(request["student_id"]), "status": request["status"]},
    )
    return request


def create_delegate_service(
    conn, user: dict, student_id: str, name: str, national_id: str,
    phone: str, relation: str, client_request_id: str | None = None,
):
    if client_request_id:
        existing = pickup_repo.get_delegate_by_client_id(conn, client_request_id, user["id"])
        if existing:
            return existing
    national_id = normalize_digits(national_id)
    if not national_id:
        raise ApiError(422, "invalid_national_id", "رقم هوية المفوض غير صالح")
    if pickup_repo.get_delegate_by_student_and_national_id(conn, student_id, national_id):
        raise ApiError(409, "delegate_exists", "هذا المفوض مسجل بالفعل لهذا الطالب")
    try:
        with conn.transaction():
            row = pickup_repo.create_delegate(
                conn, branch_id=user["branch_id"], student_id=student_id,
                user_id=user["id"], name=name, national_id=national_id,
                phone=phone, relation=relation, client_request_id=client_request_id,
            )
            return dict(row)
    except psycopg.errors.UniqueViolation as exc:
        # Postgres reports the first unique index it checks (by OID), so a concurrent
        # same-client-id replay can surface as the active index; look up the replay first.
        if client_request_id:
            existing = pickup_repo.get_delegate_by_client_id(conn, client_request_id, user["id"])
            if existing:
                return dict(existing)
        if exc.diag.constraint_name == "uq_pickup_delegates_active":
            raise ApiError(409, "delegate_exists", "هذا المفوض مسجل بالفعل لهذا الطالب") from exc
        raise


def delete_delegate_service(conn, user: dict, delegate_id: str):
    if pickup_repo.is_delegate_in_active_request(conn, delegate_id):
        raise ApiError(
            409, "delegate_in_active_pickup",
            "لا يمكن حذف المفوض لارتباطه بطلب استلام نشط",
        )
    delegate = pickup_repo.soft_delete_delegate(conn, delegate_id, user["id"])
    if delegate is None:
        raise ApiError(404, "delegate_not_found", "المفوض المطلوب غير موجود")
    return delegate


def _validate_receiver(conn, user: dict, student_id: str, delegate_id: str | None):
    if delegate_id is None:
        national_id = user.get("national_id")
        if not national_id or not national_id.strip():
            raise ApiError(
                422, "missing_guardian_national_id",
                "يرجى إضافة رقم هوية ولي الأمر في الملف الشخصي قبل تقديم طلب الاستلام",
            )
    else:
        delegate = pickup_repo.get_delegate_by_id(conn, delegate_id)
        if not delegate or not delegate.get("is_approved"):
            raise ApiError(404, "delegate_not_found", "المفوض غير موجود أو غير معتمد لهذا الاستلام")
        if str(delegate["student_id"]) != str(student_id):
            raise ApiError(404, "delegate_not_found", "المفوض المطلوب غير موجود")


def create_pickup_request_service(
    conn, user: dict, student_id: str, delegate_id: str | None,
    is_early_leave: bool, client_request_id: str | None = None,
):
    if client_request_id:
        existing = pickup_repo.get_request_by_client_id(conn, client_request_id, user["id"])
        if existing:
            return existing
    current = datetime.now(_TZ)
    if current.strftime("%H:%M") > settings.get_pickup_cutoff(conn):
        raise ApiError(400, "pickup_cutoff_passed", "انتهى وقت استقبال طلبات الاستلام اليوم")
    _validate_receiver(conn, user, student_id, delegate_id)
    if pickup_repo.get_active_request_for_student(conn, student_id, current.date()):
        raise ApiError(409, "pickup_already_active", "يوجد طلب استلام نشط لهذا الطالب بالفعل")
    try:
        with conn.transaction():
            row = pickup_repo.create_request(
                conn, branch_id=user["branch_id"], student_id=student_id,
                requested_by_user_id=user["id"], delegate_id=delegate_id,
                is_early_leave=is_early_leave, pickup_date=current.date(),
                client_request_id=client_request_id,
            )
            created = dict(row)
            return _audit_transition(conn, user, created, "pickup.request")
    except psycopg.errors.UniqueViolation as exc:
        # Same index-order caveat as create_delegate_service: replay lookup first.
        if client_request_id:
            existing = pickup_repo.get_request_by_client_id(conn, client_request_id, user["id"])
            if existing:
                return dict(existing)
        if exc.diag.constraint_name == "uq_pickup_requests_active_cycle":
            raise ApiError(409, "pickup_already_active", "يوجد طلب استلام نشط لهذا الطالب بالفعل") from exc
        raise


def approve_early_leave_service(conn, user: dict, request_id: str):
    request = pickup_repo.approve_early_leave_cas(conn, request_id, user["id"])
    return _audit_transition(conn, user, request, "pickup.early_leave_approve")


def start_prep_service(conn, user: dict, request_id: str):
    request = _require_request(conn, request_id)
    if request["is_early_leave"] and not request["early_leave_approved"]:
        raise ApiError(
            409, "early_leave_not_approved",
            "لا يمكن بدء تجهيز الطالب قبل الموافقة على طلب الانصراف المبكر",
        )
    updated = pickup_repo.update_request_status_cas(
        conn, request_id, "in_prep", ["notified"], prep_started_at="now()",
    )
    return _audit_transition(conn, user, updated, "pickup.start_prep")


def mark_ready_service(
    conn, user: dict, request_id: str, prep_checklist: dict, teacher_note: str | None,
):
    request = pickup_repo.update_request_status_cas(
        conn, request_id, "ready", ["in_prep"], ready_at="now()",
        prep_checklist=prep_checklist, teacher_note=teacher_note,
    )
    return _audit_transition(conn, user, request, "pickup.ready")


def _record_dismissal(conn, request: dict, dismissal_method: str):
    # The existing dismissal schema stores Arabic method labels, not API tokens.
    if dismissal_method == "pickup":
        dismissal_method = (
            "استلام من شخص مصرح" if request["delegate_id"] else "استلام من ولي الأمر"
        )
    conn.execute(
        "INSERT INTO dismissals "
        "(student_id, branch_id, date, method, pickup_request_id, receiver_name, "
        "receiver_id_no, receiver_phone, receiver_relation, recorded_by_user_id, created_at) "
        "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, now())",
        (request["student_id"], request["branch_id"], request["pickup_date"],
         dismissal_method, request["id"], request["verified_receiver_name"],
         request["verified_receiver_id_no"], request["verified_receiver_phone"],
         request["verified_receiver_relation"], request["verified_by_user_id"]),
    )


def handover_service(
    conn, user: dict, request_id: str, verified_receiver_id_no: str,
    verified_receiver_name: str, verified_receiver_phone: str,
    verified_receiver_relation: str, dismissal_method: str = "pickup",
):
    verified_receiver_id_no = normalize_digits(verified_receiver_id_no)
    request = _require_request(conn, request_id)
    expected_id = normalize_digits(
        request["delegate_national_id"] if request["delegate_id"]
        else request["guardian_national_id"]
    )
    if not expected_id or verified_receiver_id_no != expected_id:
        raise ApiError(
            422, "receiver_id_mismatch", "رقم هوية المستلم لا يطابق هوية الشخص المصرح له",
        )
    with conn.transaction():
        updated = pickup_repo.update_request_status_cas(
            conn, request_id, "completed", ["ready"], completed_at="now()",
            verified_receiver_name=verified_receiver_name,
            verified_receiver_id_no=verified_receiver_id_no,
            verified_receiver_phone=verified_receiver_phone,
            verified_receiver_relation=verified_receiver_relation,
            verified_by_user_id=user["id"],
        )
        updated = _audit_transition(conn, user, updated, "pickup.handover")
        _record_dismissal(conn, updated, dismissal_method)
    return updated


def cancel_pickup_service(conn, user: dict, request_id: str, cancel_reason: str):
    request = pickup_repo.update_request_status_cas(
        conn, request_id, "cancelled", ["notified", "in_prep", "ready"],
        cancelled_at="now()", cancel_reason=cancel_reason,
    )
    return _audit_transition(conn, user, request, "pickup.cancel")


def fail_pickup_service(conn, user: dict, request_id: str, failure_reason: str):
    request = pickup_repo.update_request_status_cas(
        conn, request_id, "failed", ["notified", "in_prep", "ready"],
        failure_reason=failure_reason,
    )
    return _audit_transition(conn, user, request, "pickup.fail")
