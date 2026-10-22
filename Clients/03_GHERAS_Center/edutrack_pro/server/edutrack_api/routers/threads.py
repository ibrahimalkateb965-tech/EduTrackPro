"""Messaging and conversation threads endpoints (Milestone M4)."""

from __future__ import annotations

from typing import Any
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from edutrack_api.auth import current_user
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.repositories import threads_repo
from edutrack_api.scope import resolve_scope
from edutrack_api.serializers import row_to_json

router = APIRouter(prefix="/threads", tags=["threads"])

_CONN = Depends(get_conn)
_USER = Depends(current_user)

MAX_ATTACHMENT_SIZE_BYTES = 25 * 1024 * 1024  # 25 MB
ALLOWED_ATTACHMENT_TYPES = {"image", "audio", "video", "pdf", "file"}


def _validate_attachment(
    storage_key: str | None,
    attachment_type: str | None,
    name: str | None,
    size_bytes: int | None,
) -> None:
    """Validate attachment constraints according to M5."""
    if storage_key or attachment_type or size_bytes:
        if not storage_key:
            raise ApiError(status=422, code="invalid_attachment", message="مفتاح تخزين المرفق مطلوب")
        if not attachment_type or attachment_type not in ALLOWED_ATTACHMENT_TYPES:
            raise ApiError(
                status=422,
                code="invalid_attachment",
                message=f"نوع المرفق غير صالح. الأنواع المسموحة: {', '.join(sorted(ALLOWED_ATTACHMENT_TYPES))}",
            )
        if size_bytes is not None:
            if size_bytes <= 0:
                raise ApiError(status=422, code="invalid_attachment", message="حجم المرفق يجب أن يكون أكبر من صفر")
            if size_bytes > MAX_ATTACHMENT_SIZE_BYTES:
                raise ApiError(status=422, code="attachment_too_large", message="حجم المرفق يتجاوز الحد الأقصى (25 ميجابايت)")


class CreateThreadIn(BaseModel):
    student_id: UUID
    subject: str = Field(min_length=2, max_length=200)
    staff_user_id: UUID | None = None
    guardian_user_id: UUID | None = None
    first_message: str | None = None
    linked_assignment_id: UUID | None = None
    linked_summary_id: UUID | None = None
    attachment_storage_key: str | None = None
    attachment_type: str | None = None
    attachment_name: str | None = None
    attachment_size_bytes: int | None = None
    client_message_id: str | None = None


class SendMessageIn(BaseModel):
    body: str = Field(min_length=1)
    attachment_storage_key: str | None = None
    attachment_type: str | None = None
    attachment_name: str | None = None
    attachment_size_bytes: int | None = None
    client_message_id: str | None = None


@router.get("")
def list_threads(
    student_id: UUID | None = None,
    conn=_CONN,
    user=_USER,
):
    """List accessible threads for the current authenticated user."""
    # B2: resolve scope only for mobile roles, bypass for supervisors/managers
    scope = resolve_scope(conn, user) if user["role"] in ("guardian", "teacher", "student") else None
    student_ids = scope.student_ids if scope else None
    room_ids = scope.room_ids if scope else None

    threads = threads_repo.list_threads(
        conn=conn,
        user_id=user["id"],
        role=user["role"],
        student_ids=student_ids,
        room_ids=room_ids,
        filter_student_id=student_id,
    )
    return [row_to_json(t) for t in threads]


@router.post("")
def create_thread(
    payload: CreateThreadIn,
    conn=_CONN,
    user=_USER,
):
    """Create a new messaging thread linked to a student and optionally post initial message."""
    # B2: resolve scope only for mobile roles, bypass for supervisors/managers
    scope = resolve_scope(conn, user) if user["role"] in ("guardian", "teacher", "student") else None
    role = user["role"]

    # M5: Validate attachment constraints if provided
    _validate_attachment(
        payload.attachment_storage_key,
        payload.attachment_type,
        payload.attachment_name,
        payload.attachment_size_bytes,
    )

    # Verify student exists and resolve branch
    student_row = conn.execute(
        "SELECT id, branch_id, room_id FROM students WHERE id = %s AND deleted_at IS NULL",
        (payload.student_id,),
    ).fetchone()
    if not student_row:
        raise ApiError(status=404, code="not_found", message="الطالب غير موجود")

    branch_id = student_row["branch_id"]
    room_id = student_row["room_id"]

    # B5: Validate linked summary if provided
    if payload.linked_summary_id:
        sum_row = conn.execute(
            "SELECT id, room_id, is_published FROM daily_summaries WHERE id = %s AND branch_id = %s AND deleted_at IS NULL",
            (payload.linked_summary_id, branch_id),
        ).fetchone()
        if not sum_row:
            raise ApiError(status=404, code="summary_not_found", message="الملخص اليومي المرتبط غير موجود")
        if role == "guardian" and not sum_row["is_published"]:
            raise ApiError(status=404, code="summary_not_found", message="الملخص اليومي المرتبط غير موجود")
        if not room_id or sum_row["room_id"] != room_id:
            raise ApiError(status=422, code="summary_room_mismatch", message="الملخص اليومي لا يطابق قاعة الطالب")

    # B5: Validate linked assignment if provided
    if payload.linked_assignment_id:
        ass_row = conn.execute(
            "SELECT id FROM assignments WHERE id = %s AND branch_id = %s AND deleted_at IS NULL",
            (payload.linked_assignment_id, branch_id),
        ).fetchone()
        if not ass_row:
            raise ApiError(status=404, code="assignment_not_found", message="الواجب المرتبط غير موجود")
        ass_stu = conn.execute(
            "SELECT 1 FROM assignment_students WHERE assignment_id = %s AND student_id = %s AND deleted_at IS NULL",
            (payload.linked_assignment_id, payload.student_id),
        ).fetchone()
        if not ass_stu:
            raise ApiError(status=422, code="assignment_student_mismatch", message="الواجب المرتبط غير مسند لهذا الطالب")

    # B4: Determine parties and enforce cross-participant validity (guardian <-> staff)
    if role == "guardian":
        # Guardian initiates conversation
        if scope and scope.student_ids is not None and payload.student_id not in scope.student_ids:
            raise ApiError(status=403, code="forbidden", message="غير مصرح لك ببدء محادثة لهذا الطالب")

        guardian_user_id = user["id"]
        staff_user_id = payload.staff_user_id

        if staff_user_id:
            target_staff = conn.execute(
                "SELECT id, role FROM users WHERE id = %s AND deleted_at IS NULL",
                (staff_user_id,),
            ).fetchone()
            if not target_staff or target_staff["role"] not in ("teacher", "supervisor", "manager", "admin"):
                raise ApiError(status=422, code="invalid_participant", message="الطرف الآخر يجب أن يكون كادراً تعليمياً أو إدارياً")
            if target_staff["role"] == "teacher" and room_id:
                teaches_student = conn.execute("""
                    SELECT 1 FROM schedules s
                    WHERE s.teacher_user_id = %s AND s.room_id = %s AND s.deleted_at IS NULL
                    UNION
                    SELECT 1 FROM users u
                    WHERE u.id = %s AND u.room_id = %s AND u.deleted_at IS NULL
                """, (staff_user_id, room_id, staff_user_id, room_id)).fetchone()
                if not teaches_student:
                    raise ApiError(status=422, code="invalid_teacher", message="المعلمة المحددة لا تدرس هذا الطالب")
        else:
            # If staff_user_id not provided, assign room teacher or first supervisor
            teacher_row = conn.execute("""
                SELECT u.id FROM users u
                LEFT JOIN schedules s ON s.teacher_user_id = u.id AND s.deleted_at IS NULL
                WHERE (u.room_id = %s OR s.room_id = %s) AND u.deleted_at IS NULL AND u.role = 'teacher'
                LIMIT 1
            """, (room_id, room_id)).fetchone()
            if teacher_row:
                staff_user_id = teacher_row["id"]
            else:
                super_row = conn.execute("""
                    SELECT id FROM users 
                    WHERE role IN ('supervisor', 'manager') AND branch_id = %s AND deleted_at IS NULL 
                    ORDER BY id ASC
                    LIMIT 1
                """, (branch_id,)).fetchone()
                if not super_row:
                    raise ApiError(status=422, code="no_staff", message="لا يوجد كادر تعليمي متاح للاستقبال")
                staff_user_id = super_row["id"]

    elif role in ("teacher", "supervisor", "manager", "admin"):
        # Staff initiates conversation
        if role == "teacher" and scope and scope.room_ids is not None and room_id not in scope.room_ids:
            raise ApiError(status=403, code="forbidden", message="الطالب لا ينتمي للقاعات المخصصة لك")

        staff_user_id = user["id"]
        guardian_user_id = payload.guardian_user_id

        if guardian_user_id:
            g_check = conn.execute("""
                SELECT u.id FROM users u
                JOIN student_guardians sg ON sg.guardian_id = u.guardian_id
                WHERE u.id = %s AND sg.student_id = %s AND u.role = 'guardian' AND u.deleted_at IS NULL
            """, (guardian_user_id, payload.student_id)).fetchone()
            if not g_check:
                raise ApiError(status=422, code="invalid_participant", message="ولي الأمر المحدد غير مسجل لهذا الطالب")
        else:
            # Lookup student's registered guardian user
            g_row = conn.execute("""
                SELECT u.id FROM users u
                JOIN student_guardians sg ON sg.guardian_id = u.guardian_id
                WHERE sg.student_id = %s AND u.role = 'guardian' AND u.deleted_at IS NULL
                LIMIT 1
            """, (payload.student_id,)).fetchone()
            if not g_row:
                raise ApiError(status=422, code="no_guardian", message="لم يتم العثور على ولي أمر مسجل لهذا الطالب")
            guardian_user_id = g_row["id"]
    else:
        raise ApiError(status=403, code="forbidden", message="غير مصرح لك بإنشاء محادثات")

    thread = threads_repo.create_thread(
        conn=conn,
        branch_id=branch_id,
        student_id=payload.student_id,
        guardian_user_id=guardian_user_id,
        staff_user_id=staff_user_id,
        subject=payload.subject,
        linked_assignment_id=payload.linked_assignment_id,
        linked_summary_id=payload.linked_summary_id,
        first_message_body=payload.first_message,
        sender_user_id=user["id"],
        sender_role=role,
        attachment_storage_key=payload.attachment_storage_key,
        attachment_type=payload.attachment_type,
        attachment_name=payload.attachment_name,
        attachment_size_bytes=payload.attachment_size_bytes,
        client_message_id=payload.client_message_id,
    )

    # B1 & M2: Notify recipient with gheras:// action_url if initial message was sent and thread is not duplicate
    if payload.first_message and not thread.get("_is_duplicate"):
        recipient_id = staff_user_id if user["id"] == guardian_user_id else guardian_user_id
        conn.execute("""
            INSERT INTO notifications (
                branch_id, user_id, kind, title, body, target_type, target_id,
                priority, sender_user_id, action_url, sent_at
            ) VALUES (
                %s, %s, 'announcement', %s, %s, 'thread_message', %s,
                'normal', %s, %s, now()
            )
        """, (
            branch_id, recipient_id, f"رسالة جديدة: {payload.subject}",
            payload.first_message[:100], thread["id"], user["id"],
            f"gheras://threads/{thread['id']}"
        ))
        conn.commit()

    return row_to_json(threads_repo.get_thread_by_id(conn, thread["id"], user_id=user["id"]))


@router.get("/{thread_id}")
def get_thread(
    thread_id: UUID,
    conn=_CONN,
    user=_USER,
):
    """Get single thread details."""
    thread = threads_repo.get_thread_by_id(conn, thread_id, user_id=user["id"])
    if not thread:
        raise ApiError(status=404, code="not_found", message="المحادثة غير موجودة")

    # B3: Authorize participant
    role = user["role"]
    if role not in ("supervisor", "manager", "admin"):
        if user["id"] not in (thread["guardian_user_id"], thread["staff_user_id"]):
            raise ApiError(status=403, code="forbidden", message="غير مصرح لك بالوصول لهذه المحادثة")

    return row_to_json(thread)


@router.get("/{thread_id}/messages")
def list_messages(
    thread_id: UUID,
    limit: int = 100,
    conn=_CONN,
    user=_USER,
):
    """List messages in a thread chronologically."""
    # M6: Validate message limit
    if limit < 1 or limit > 200:
        raise ApiError(status=422, code="invalid_limit", message="يجب أن يكون حد الرسائل بين 1 و 200")

    thread = threads_repo.get_thread_by_id(conn, thread_id, user_id=user["id"])
    if not thread:
        raise ApiError(status=404, code="not_found", message="المحادثة غير موجودة")

    # B3: Authorize participant
    role = user["role"]
    if role not in ("supervisor", "manager", "admin"):
        if user["id"] not in (thread["guardian_user_id"], thread["staff_user_id"]):
            raise ApiError(status=403, code="forbidden", message="غير مصرح لك بالاطلاع على رسائل هذه المحادثة")

    messages = threads_repo.list_thread_messages(conn, thread_id, limit=limit)
    return [row_to_json(m) for m in messages]


@router.post("/{thread_id}/messages")
def send_message(
    thread_id: UUID,
    payload: SendMessageIn,
    conn=_CONN,
    user=_USER,
):
    """Send a message in an existing thread and update thread activity."""
    # M5: Validate attachment constraints
    _validate_attachment(
        payload.attachment_storage_key,
        payload.attachment_type,
        payload.attachment_name,
        payload.attachment_size_bytes,
    )

    thread = threads_repo.get_thread_by_id(conn, thread_id, user_id=user["id"])
    if not thread:
        raise ApiError(status=404, code="not_found", message="المحادثة غير موجودة")

    # B3: Authorize participant
    role = user["role"]
    if role not in ("supervisor", "manager", "admin"):
        if user["id"] not in (thread["guardian_user_id"], thread["staff_user_id"]):
            raise ApiError(status=403, code="forbidden", message="غير مصرح لك بالمراسلة في هذه المحادثة")

    msg = threads_repo.create_thread_message(
        conn=conn,
        branch_id=thread["branch_id"],
        thread_id=thread_id,
        sender_user_id=user["id"],
        sender_role=role,
        body=payload.body,
        attachment_storage_key=payload.attachment_storage_key,
        attachment_type=payload.attachment_type,
        attachment_name=payload.attachment_name,
        attachment_size_bytes=payload.attachment_size_bytes,
        client_message_id=payload.client_message_id,
    )

    # M2: Only notify if not a duplicate client_message_id
    if not msg.get("_is_duplicate"):
        recipient_id = thread["staff_user_id"] if user["id"] == thread["guardian_user_id"] else thread["guardian_user_id"]
        # B1: action_url starts with gheras://
        conn.execute("""
            INSERT INTO notifications (
                branch_id, user_id, kind, title, body, target_type, target_id,
                priority, sender_user_id, action_url, sent_at
            ) VALUES (
                %s, %s, 'announcement', %s, %s, 'thread_message', %s,
                'normal', %s, %s, now()
            )
        """, (
            thread["branch_id"], recipient_id, f"رسالة جديدة في: {thread['subject']}",
            payload.body[:100], thread_id, user["id"],
            f"gheras://threads/{thread_id}"
        ))
        conn.commit()

    return row_to_json(msg)


@router.post("/{thread_id}/messages/delivered")
def mark_delivered(
    thread_id: UUID,
    conn=_CONN,
    user=_USER,
):
    """Mark incoming messages in thread as delivered for the current recipient."""
    # B3: Ensure participant
    thread = threads_repo.get_thread_by_id(conn, thread_id, user_id=user["id"])
    if not thread:
        raise ApiError(status=404, code="not_found", message="المحادثة غير موجودة")
    if user["id"] not in (thread["guardian_user_id"], thread["staff_user_id"]):
        raise ApiError(status=403, code="forbidden", message="غير مصرح لك بتحديث حالة رسائل هذه المحادثة")

    count = threads_repo.mark_messages_delivered(conn, thread_id, user["id"])
    return {"delivered_count": count}


@router.post("/{thread_id}/messages/read")
def mark_read(
    thread_id: UUID,
    conn=_CONN,
    user=_USER,
):
    """Mark incoming messages in thread as read for the current recipient."""
    # B3: Ensure participant
    thread = threads_repo.get_thread_by_id(conn, thread_id, user_id=user["id"])
    if not thread:
        raise ApiError(status=404, code="not_found", message="المحادثة غير موجودة")
    if user["id"] not in (thread["guardian_user_id"], thread["staff_user_id"]):
        raise ApiError(status=403, code="forbidden", message="غير مصرح لك بتحديث حالة رسائل هذه المحادثة")

    count = threads_repo.mark_messages_read(conn, thread_id, user["id"])
    return {"read_count": count}
