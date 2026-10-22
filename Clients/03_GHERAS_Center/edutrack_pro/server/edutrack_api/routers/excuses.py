"""Attendance Excuses Flow (Milestone M3): submission, review, patch, and queries."""

from __future__ import annotations

from datetime import date as dt_date
from uuid import UUID

import psycopg
from fastapi import APIRouter, Depends
from pydantic import BaseModel

from edutrack_api.audit import write_audit
from edutrack_api.auth import require_roles
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.scope import resolve_scope
from edutrack_api.serializers import row_to_json

router = APIRouter(prefix="/attendance/excuses")

_USER = Depends(require_roles("guardian", "manager", "supervisor"))
_STAFF = Depends(require_roles("manager", "supervisor"))
MAIN_BRANCH = UUID("00000000-0000-0000-0000-000000000001")


class ExcuseCreateIn(BaseModel):
    student_id: UUID
    excuse_date: dt_date
    reason_text: str
    attachment_storage_key: str | None = None
    client_request_id: str | None = None


class ExcusePatchIn(BaseModel):
    reason_text: str | None = None
    attachment_storage_key: str | None = None


class ExcuseReviewIn(BaseModel):
    action: str  # approve, reject, needs_info
    supervisor_notes: str | None = None


@router.post("", status_code=201)
def create_excuse(
    body: ExcuseCreateIn,
    user: dict = _USER,
    conn=Depends(get_conn),
) -> dict:
    """Submit an absence excuse (P10). Supports client_request_id replay idempotency."""
    reason = body.reason_text.strip()
    if len(reason) < 5:
        raise ApiError(422, "validation_error", "سبب العذر يجب ألا يقل عن 5 أحرف")

    # Scope verification for guardians
    if user["role"] == "guardian":
        scope = resolve_scope(conn, user)
        scope.assert_students([body.student_id])

    # 1. Fast replay idempotency lookup (P10)
    if body.client_request_id:
        existing = conn.execute(
            """
            SELECT ae.*, s.name AS student_name, s.room_id
            FROM attendance_excuses ae
            JOIN students s ON s.id = ae.student_id
            WHERE ae.submitted_by_user_id = %s AND ae.client_request_id = %s AND ae.deleted_at IS NULL
            """,
            (user["id"], body.client_request_id),
        ).fetchone()
        if existing:
            return row_to_json(existing)

    # 2. Check active excuse for this student and day (P10)
    conflict = conn.execute(
        """
        SELECT id FROM attendance_excuses
        WHERE student_id = %s AND excuse_date = %s AND deleted_at IS NULL
          AND status IN ('pending', 'approved', 'needs_info')
        """,
        (body.student_id, body.excuse_date),
    ).fetchone()
    if conflict:
        raise ApiError(409, "conflict", "يوجد عذر مسجل مسبقاً لهذا اليوم")

    # 3. Check staged attachment ownership if provided
    if body.attachment_storage_key:
        staged = conn.execute(
            "SELECT 1 FROM staged_uploads WHERE storage_key = %s AND uploaded_by_user_id = %s",
            (body.attachment_storage_key, user["id"]),
        ).fetchone()
        if not staged:
            raise ApiError(404, "not_found", "الملف المرفق غير موجود أو غير مملوك للمستخدم")

    # 4. Resolve branch from student
    student_row = conn.execute(
        "SELECT branch_id FROM students WHERE id = %s AND deleted_at IS NULL",
        (body.student_id,),
    ).fetchone()
    if not student_row:
        raise ApiError(404, "not_found", "الطالب غير موجود")
    branch_id = student_row["branch_id"] or user.get("branch_id") or MAIN_BRANCH

    # 5. Insert with savepoint for race condition safety
    try:
        with conn.transaction():
            row = conn.execute(
                """
                INSERT INTO attendance_excuses (
                    branch_id, student_id, excuse_date, submitted_by_user_id,
                    reason_text, attachment_storage_key, client_request_id, status
                ) VALUES (
                    %s, %s, %s, %s, %s, %s, %s, 'pending'
                ) RETURNING *
                """,
                (
                    branch_id,
                    body.student_id,
                    body.excuse_date,
                    user["id"],
                    reason,
                    body.attachment_storage_key,
                    body.client_request_id,
                ),
            ).fetchone()
    except psycopg.errors.UniqueViolation:
        if body.client_request_id:
            replay = conn.execute(
                """
                SELECT ae.*, s.name AS student_name, s.room_id
                FROM attendance_excuses ae
                JOIN students s ON s.id = ae.student_id
                WHERE ae.submitted_by_user_id = %s AND ae.client_request_id = %s AND ae.deleted_at IS NULL
                """,
                (user["id"], body.client_request_id),
            ).fetchone()
            if replay:
                return row_to_json(replay)
        raise ApiError(409, "conflict", "يوجد عذر مسجل مسبقاً لهذا اليوم")

    write_audit(conn, user["id"], "create", "attendance_excuses", str(row["id"]), {
        "student_id": str(body.student_id),
        "excuse_date": body.excuse_date.isoformat(),
    })
    return row_to_json(row)


@router.get("")
def list_excuses_staff(
    status: str | None = None,
    room_id: UUID | None = None,
    date_from: dt_date | None = None,
    date_to: dt_date | None = None,
    student_id: UUID | None = None,
    user: dict = _STAFF,
    conn=Depends(get_conn),
) -> dict:
    """Staff online excuse review queue (G1 part b). Supervisor requires attendance permission."""
    if user["role"] == "supervisor" and not user.get("permissions", {}).get("attendance", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية استعراض الأعذار")

    query = """
        SELECT
            ae.*,
            s.name AS student_name,
            s.room_id,
            r.name AS room_name,
            u.username AS submitted_by_name,
            ru.username AS reviewed_by_name
        FROM attendance_excuses ae
        JOIN students s ON s.id = ae.student_id AND s.deleted_at IS NULL
        LEFT JOIN rooms r ON r.id = s.room_id AND r.deleted_at IS NULL
        LEFT JOIN users u ON u.id = ae.submitted_by_user_id
        LEFT JOIN users ru ON ru.id = ae.reviewed_by_user_id
        WHERE ae.deleted_at IS NULL
    """
    params = []

    if status:
        query += " AND ae.status = %s"
        params.append(status)
    if room_id:
        query += " AND s.room_id = %s"
        params.append(room_id)
    if student_id:
        query += " AND ae.student_id = %s"
        params.append(student_id)
    if date_from:
        query += " AND ae.excuse_date >= %s"
        params.append(date_from)
    if date_to:
        query += " AND ae.excuse_date <= %s"
        params.append(date_to)

    query += " ORDER BY ae.created_at DESC"

    rows = conn.execute(query, params).fetchall()
    return {"items": [row_to_json(r) for r in rows], "total": len(rows)}


@router.get("/{id}")
def get_excuse_detail(
    id: UUID,
    user: dict = _USER,
    conn=Depends(get_conn),
) -> dict:
    """Get single excuse details with role/scope check."""
    row = conn.execute(
        """
        SELECT
            ae.*,
            s.name AS student_name,
            s.room_id,
            r.name AS room_name,
            u.username AS submitted_by_name,
            ru.username AS reviewed_by_name
        FROM attendance_excuses ae
        JOIN students s ON s.id = ae.student_id AND s.deleted_at IS NULL
        LEFT JOIN rooms r ON r.id = s.room_id AND r.deleted_at IS NULL
        LEFT JOIN users u ON u.id = ae.submitted_by_user_id
        LEFT JOIN users ru ON ru.id = ae.reviewed_by_user_id
        WHERE ae.id = %s AND ae.deleted_at IS NULL
        """,
        (id,),
    ).fetchone()
    if not row:
        raise ApiError(404, "not_found", "العذر غير موجود")

    if user["role"] == "guardian":
        scope = resolve_scope(conn, user)
        scope.assert_students([row["student_id"]])
    elif user["role"] == "supervisor" and not user.get("permissions", {}).get("attendance", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية استعراض الأعذار")

    return row_to_json(row)


@router.patch("/{id}")
def patch_excuse(
    id: UUID,
    body: ExcusePatchIn,
    user: dict = _USER,
    conn=Depends(get_conn),
) -> dict:
    """Guardian updates/completes an excuse in 'needs_info' status (P7)."""
    excuse = conn.execute(
        "SELECT * FROM attendance_excuses WHERE id = %s AND deleted_at IS NULL",
        (id,),
    ).fetchone()
    if not excuse:
        raise ApiError(404, "not_found", "العذر غير موجود")

    if user["role"] == "guardian":
        if str(excuse["submitted_by_user_id"]) != str(user["id"]):
            scope = resolve_scope(conn, user)
            scope.assert_students([excuse["student_id"]])

    # Only excuses in needs_info can be patched by guardian
    if excuse["status"] != "needs_info":
        raise ApiError(409, "conflict", "لا يمكن تعديل عذر ليس بحالة طلب استكمال")

    new_reason = None
    if body.reason_text is not None:
        new_reason = body.reason_text.strip()
        if len(new_reason) < 5:
            raise ApiError(422, "validation_error", "سبب العذر يجب ألا يقل عن 5 أحرف")

    new_attachment = None
    if body.attachment_storage_key is not None:
        new_attachment = body.attachment_storage_key
        staged = conn.execute(
            "SELECT 1 FROM staged_uploads WHERE storage_key = %s AND uploaded_by_user_id = %s",
            (new_attachment, user["id"]),
        ).fetchone()
        if not staged:
            raise ApiError(404, "not_found", "الملف المرفق غير موجود أو غير مملوك للمستخدم")

    with conn.transaction():
        updated = conn.execute(
            """
            UPDATE attendance_excuses
            SET reason_text = COALESCE(%s, reason_text),
                attachment_storage_key = COALESCE(%s, attachment_storage_key),
                status = 'pending',
                updated_at = now()
            WHERE id = %s AND status = 'needs_info' AND deleted_at IS NULL
            RETURNING *
            """,
            (new_reason, new_attachment, id),
        ).fetchone()

    if not updated:
        raise ApiError(409, "conflict", "تم تعديل حالة العذر مسبقاً")

    write_audit(conn, user["id"], "update", "attendance_excuses", str(id), {
        "status": "pending",
        "action": "resubmit_needs_info",
    })
    return row_to_json(updated)


@router.post("/{id}/review")
def review_excuse(
    id: UUID,
    body: ExcuseReviewIn,
    user: dict = _STAFF,
    conn=Depends(get_conn),
) -> dict:
    """Supervisor/Manager reviews excuse: approve, reject, or request info (P5, P6, n3, m7)."""
    if user["role"] == "supervisor" and not user.get("permissions", {}).get("attendance", False):
        raise ApiError(403, "forbidden", "ليس لديك صلاحية مراجعة الأعذار")

    if body.action not in ("approve", "reject", "needs_info"):
        raise ApiError(422, "validation_error", "إجراء المراجعة غير صحيح")

    notes = body.supervisor_notes.strip() if body.supervisor_notes else None
    if body.action in ("reject", "needs_info") and not notes:
        raise ApiError(422, "validation_error", "ملاحظات المشرفة إلزامية عند الرفض أو طلب الاستكمال")

    status_map = {
        "approve": "approved",
        "reject": "rejected",
        "needs_info": "needs_info",
    }
    new_status = status_map[body.action]

    with conn.transaction():
        # CAS Guard: atomic transition conditioned on status = 'pending' (P6)
        updated = conn.execute(
            """
            UPDATE attendance_excuses
            SET status = %s,
                supervisor_notes = COALESCE(%s, supervisor_notes),
                reviewed_by_user_id = %s,
                reviewed_at = now(),
                updated_at = now()
            WHERE id = %s AND status = 'pending' AND deleted_at IS NULL
            RETURNING *
            """,
            (new_status, notes, user["id"], id),
        ).fetchone()

        if not updated:
            existing = conn.execute(
                "SELECT id, status FROM attendance_excuses WHERE id = %s AND deleted_at IS NULL",
                (id,),
            ).fetchone()
            if not existing:
                raise ApiError(404, "not_found", "العذر غير موجود")
            raise ApiError(409, "conflict", "تمت مراجعة هذا العذر مسبقاً ولا يمكن تعديل حالته")

        # P5 & m7: If approved, convert corresponding attendance to 'غياب بعذر' ONLY IF status is 'غائب'
        if body.action == "approve":
            conn.execute(
                """
                UPDATE student_attendance
                SET status = 'غياب بعذر',
                    excuse_id = %s,
                    updated_at = now()
                WHERE student_id = %s AND date = %s AND status = 'غائب' AND deleted_at IS NULL
                """,
                (updated["id"], updated["student_id"], updated["excuse_date"]),
            )

        # Emit notification to guardian
        st_info = conn.execute(
            "SELECT name, branch_id FROM students WHERE id = %s",
            (updated["student_id"],),
        ).fetchone()
        st_name = st_info["name"] if st_info else "الطالب"
        br_id = st_info["branch_id"] if st_info else (user.get("branch_id") or MAIN_BRANCH)

        titles = {
            "approve": "تم قبول عذر الغياب",
            "reject": "تم رفض عذر الغياب",
            "needs_info": "مطلوب استكمال بيانات العذر",
        }
        notif_body = notes or f"تمت مراجعة عذر الطالب/ـة {st_name} لتاريخ {updated['excuse_date']}"

        conn.execute(
            """
            INSERT INTO notifications (
                branch_id, user_id, kind, title, body, target_type, target_id,
                priority, sender_user_id, action_url, sent_at
            ) VALUES (
                %s, %s, 'excuse', %s, %s, 'excuse', %s,
                'normal', %s, %s, now()
            )
            """,
            (
                br_id,
                updated["submitted_by_user_id"],
                titles[body.action],
                notif_body,
                updated["id"],
                user["id"],
                f"gheras://excuses?id={updated['id']}",
            ),
        )

        write_audit(conn, user["id"], "review", "attendance_excuses", str(updated["id"]), {
            "action": body.action,
            "status": new_status,
            "student_id": str(updated["student_id"]),
            "excuse_date": updated["excuse_date"].isoformat(),
        })

    return row_to_json(updated)
