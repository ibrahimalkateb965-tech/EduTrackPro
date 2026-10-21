"""SQL persistence and business logic for modern assignments, multi-attempt submissions, and reminders.

Conforms to Milestone M2 Specification:
- Dual-mode attempts: initial attempt and subsequent revisions with parent links.
- Immutable attempts: previous attempts are never modified or soft-deleted.
- ASCII grade codes: 'excellent', 'very_good', 'good', 'needs_improvement'.
- Staged upload verification: users can only attach files they staged.
- Edit lock: title, due_date and students are locked once first submission arrives.
- Strict scope isolation: room and student scope enforcement.
- Dual cooldown reminders: 24h per assignment for bulk, 24h per (assignment, student) for individual.
"""

from __future__ import annotations

import logging
from uuid import UUID

import psycopg
from psycopg.types.json import Jsonb

from edutrack_api.audit import write_audit
from edutrack_api.errors import ApiError
from edutrack_api.scope import Scope

logger = logging.getLogger(__name__)

GRADE_LEVELS = frozenset({"excellent", "very_good", "good", "needs_improvement"})
GRADE_ARABIC_MAP = {
    "excellent": "ممتاز",
    "very_good": "جيد جدًا",
    "good": "جيد",
    "needs_improvement": "يحتاج إلى تحسين",
}


def _file_subquery() -> str:
    return (
        "(SELECT COALESCE(json_agg(json_build_object("
        "'id', f.id, 'storage_key', f.storage_key, 'media_type', f.media_type, "
        "'file_name', f.file_name, 'bytes', f.bytes"
        ") ORDER BY f.created_at), '[]'::json) "
        "FROM assignment_files f WHERE f.assignment_id = a.id AND f.deleted_at IS NULL) AS files"
    )


def _submission_file_subquery() -> str:
    return (
        "(SELECT COALESCE(json_agg(json_build_object("
        "'id', sf.id, 'storage_key', sf.storage_key, 'media_type', sf.media_type, "
        "'file_name', sf.file_name, 'bytes', sf.bytes"
        ") ORDER BY sf.created_at), '[]'::json) "
        "FROM submission_files sf WHERE sf.submission_id = sb.id AND sf.deleted_at IS NULL) AS files"
    )


def get_assignment_by_id(conn, assignment_id: UUID) -> dict | None:
    return conn.execute(
        f"SELECT a.*, {_file_subquery()} FROM assignments a WHERE a.id = %s AND a.deleted_at IS NULL",
        (assignment_id,),
    ).fetchone()


def check_edit_lock(conn, assignment_id: UUID) -> bool:
    """Returns True if any active submissions exist for this assignment (edit locked)."""
    row = conn.execute(
        "SELECT 1 FROM submissions WHERE assignment_id = %s AND deleted_at IS NULL LIMIT 1",
        (assignment_id,),
    ).fetchone()
    return row is not None


def verify_staged_uploads_ownership(conn, user_id: UUID, storage_keys: list[str]) -> None:
    """Verify that all provided storage keys exist in staged_uploads and belong to user_id (R3-2, R4-2)."""
    if not storage_keys:
        return
    rows = conn.execute(
        "SELECT storage_key, uploaded_by_user_id FROM staged_uploads WHERE storage_key = ANY(%s)",
        (storage_keys,),
    ).fetchall()
    found_keys = {r["storage_key"]: r["uploaded_by_user_id"] for r in rows}
    for key in storage_keys:
        if key not in found_keys:
            raise ApiError(404, "file_not_found", f"الملف المرفوع غير موجود: {key}")
        if found_keys[key] != user_id:
            raise ApiError(403, "unauthorized_file", f"لا تملك صلاحية إرفاق هذا الملف: {key}")


def create_assignment(conn, scope: Scope, body: dict) -> dict:
    """Create or update assignment with staged media attachments and draft support."""
    if scope.role != "teacher":
        raise ApiError(403, "forbidden", "المعلمة فقط مصرح لها بإنشاء الواجبات")

    room_id = body.get("room_id")
    if room_id and scope.room_ids and room_id not in scope.room_ids:
        raise ApiError(403, "out_of_scope", "الفصل المحدد خارج نطاق المعلمة")

    assignment_id = body.get("id")
    if assignment_id:
        # Check edit lock if updating
        if check_edit_lock(conn, assignment_id):
            raise ApiError(409, "edit_locked", "لا يمكن تعديل الواجب بعد بدء تسليم الطلاب")

    branch_id = body.get("branch_id") or UUID("00000000-0000-0000-0000-000000000001")
    title = body["title"]
    subject = body.get("subject", "عام")
    instructions = body.get("instructions", "")
    due_date = body.get("due_date")  # Nullable in M2 (H7)
    is_draft = body.get("is_draft", False)
    files = body.get("files", [])  # list of dicts: storage_key, media_type, file_name, bytes

    # Verify staged media files ownership
    storage_keys = [f["storage_key"] for f in files if "storage_key" in f]
    verify_staged_uploads_ownership(conn, scope.user_id, storage_keys)

    if assignment_id:
        row = conn.execute("""
            INSERT INTO assignments (
                id, branch_id, title, subject, instructions, due_date,
                teacher_user_id, room_id, is_draft, updated_at
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, now())
            ON CONFLICT (id) DO UPDATE SET
                title = EXCLUDED.title,
                subject = EXCLUDED.subject,
                instructions = EXCLUDED.instructions,
                due_date = EXCLUDED.due_date,
                room_id = EXCLUDED.room_id,
                is_draft = EXCLUDED.is_draft,
                updated_at = now()
            WHERE assignments.teacher_user_id = EXCLUDED.teacher_user_id
            RETURNING *
        """, (assignment_id, branch_id, title, subject, instructions, due_date, scope.user_id, room_id, is_draft)).fetchone()
        if not row:
            raise ApiError(404, "not_found", "الواجب غير موجود أو لا تملكه المعلمة")
    else:
        row = conn.execute("""
            INSERT INTO assignments (
                branch_id, title, subject, instructions, due_date,
                teacher_user_id, room_id, is_draft
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            RETURNING *
        """, (branch_id, title, subject, instructions, due_date, scope.user_id, room_id, is_draft)).fetchone()

    ass_id = row["id"]

    # Insert assignment_files
    for f in files:
        conn.execute("""
            INSERT INTO assignment_files (
                branch_id, assignment_id, uploaded_by_user_id, storage_key,
                media_type, file_name, bytes
            ) VALUES (%s, %s, %s, %s, %s, %s, %s)
        """, (
            branch_id, ass_id, scope.user_id, f["storage_key"],
            f.get("media_type", "image"), f.get("file_name"), f.get("bytes", 0)
        ))

    # Link students: expand room students or explicit list
    target_students = body.get("student_ids")
    if not target_students and room_id:
        st_rows = conn.execute(
            "SELECT id FROM students WHERE room_id = %s AND deleted_at IS NULL",
            (room_id,)
        ).fetchall()
        target_students = [r["id"] for r in st_rows]

    if target_students:
        for sid in target_students:
            conn.execute("""
                INSERT INTO assignment_students (branch_id, assignment_id, student_id)
                VALUES (%s, %s, %s)
                ON CONFLICT (assignment_id, student_id) DO NOTHING
            """, (branch_id, ass_id, sid))

    write_audit(conn, scope.user_id, "create_assignment", "assignments", ass_id, {"title": title, "is_draft": is_draft})

    # If published immediately (not draft), send notifications
    if not is_draft and target_students:
        _notify_new_assignment(conn, branch_id, ass_id, scope.user_id, title, subject, due_date, target_students)

    return get_assignment_by_id(conn, ass_id)


def publish_assignment(conn, scope: Scope, assignment_id: UUID) -> dict:
    """Publish a draft assignment (idempotent, M-1).

    Verifies owner and non-deleted status; returns 404 for foreign or missing ID.
    If already published, returns 200 without duplicate notifications.
    """
    row = conn.execute(
        "SELECT * FROM assignments WHERE id = %s AND deleted_at IS NULL",
        (assignment_id,),
    ).fetchone()
    if not row or row["teacher_user_id"] != scope.user_id:
        raise ApiError(404, "not_found", "الواجب غير موجود أو لا تملكه المعلمة")

    if not row["is_draft"]:
        return get_assignment_by_id(conn, assignment_id)

    # Transition draft -> published
    updated = conn.execute(
        "UPDATE assignments SET is_draft = FALSE, updated_at = now() WHERE id = %s RETURNING *",
        (assignment_id,),
    ).fetchone()

    st_rows = conn.execute(
        "SELECT student_id FROM assignment_students WHERE assignment_id = %s AND deleted_at IS NULL",
        (assignment_id,),
    ).fetchall()
    target_students = [r["student_id"] for r in st_rows]

    if target_students:
        _notify_new_assignment(
            conn, updated["branch_id"], assignment_id, scope.user_id,
            updated["title"], updated["subject"], updated["due_date"], target_students
        )

    write_audit(conn, scope.user_id, "publish_assignment", "assignments", assignment_id, {"title": updated["title"]})
    return get_assignment_by_id(conn, assignment_id)


def _notify_new_assignment(conn, branch_id, assignment_id, sender_user_id, title, subject, due_date, student_ids):
    g_rows = conn.execute("""
        SELECT u.id AS user_id, sg.student_id, s.name AS student_name
        FROM users u
        JOIN student_guardians sg ON sg.guardian_id = u.guardian_id
        JOIN students s ON s.id = sg.student_id
        WHERE sg.student_id = ANY(%s) AND sg.deleted_at IS NULL
          AND u.is_active = true AND u.deleted_at IS NULL AND u.id <> %s
    """, (student_ids, sender_user_id)).fetchall()

    seen_targets = set()
    due_str = str(due_date) if due_date else "غير محدد"
    for r in g_rows:
        key = (r["user_id"], r["student_id"])
        if key in seen_targets:
            continue
        seen_targets.add(key)
        conn.execute("""
            INSERT INTO notifications (
                branch_id, user_id, kind, title, body, target_type, target_id,
                priority, sender_user_id, action_url, sent_at
            ) VALUES (
                %s, %s, 'assignment', %s, %s, 'assignment', %s,
                'normal', %s, %s, now()
            )
        """, (
            branch_id, r["user_id"],
            f"واجب جديد: {title}",
            f"تم إضافة واجب جديد للطالب/ـة {r['student_name']} لمادة {subject}، موعد التسليم: {due_str}",
            str(assignment_id), sender_user_id,
            f"gheras://assignment?id={assignment_id}&student_id={r['student_id']}"
        ))


def submit_assignment_cas(
    conn,
    scope: Scope,
    assignment_id: UUID,
    student_id: UUID,
    client_request_id: UUID | None,
    files: list[dict],
    text_content: str | None = None,
) -> dict:
    """Sole submit surface: submit initial attempt or subsequent revision (H1, H2, R3-1).

    Enforces:
    1. Student is enrolled in assignment_students (no auto-linking).
    2. Uploaded files exist in staged_uploads and belong to caller.
    3. client_request_id idempotency.
    4. SELECT ... FOR UPDATE on latest attempt.
    5. Rejects with 409 if latest attempt is submitted or graded.
    6. If revision_requested/returned, creates attempt = prev + 1 with parent_submission_id = prev.id.
    7. is_late calculated timezone-safely on all attempts.
    8. Savepoint converts uq_submissions_attempt conflict into 409 attempt_conflict.
    9. Previous attempts and files are frozen and immutable.
    """
    # 1. Enrolled check
    enrolled = conn.execute(
        "SELECT 1 FROM assignment_students WHERE assignment_id = %s AND student_id = %s AND deleted_at IS NULL",
        (assignment_id, student_id),
    ).fetchone()
    if not enrolled:
        raise ApiError(403, "not_enrolled", "الطالب غير مرتبط بهذا الواجب")

    # Scope check
    if student_id not in scope.student_ids:
        raise ApiError(403, "out_of_scope", "الطالب خارج نطاق ولي الأمر")

    # 2. Files ownership check
    storage_keys = [f["storage_key"] for f in files if "storage_key" in f]
    verify_staged_uploads_ownership(conn, scope.user_id, storage_keys)

    # 3. Idempotency check via client_request_id
    if client_request_id:
        existing = conn.execute(
            f"SELECT sb.*, {_submission_file_subquery()} FROM submissions sb "
            "WHERE sb.client_request_id = %s AND sb.deleted_at IS NULL",
            (client_request_id,),
        ).fetchone()
        if existing:
            return existing

    # Assignment verification
    ass = conn.execute(
        "SELECT id, branch_id, title, teacher_user_id, due_date, is_draft FROM assignments WHERE id = %s AND deleted_at IS NULL",
        (assignment_id,),
    ).fetchone()
    if not ass or ass["is_draft"]:
        raise ApiError(404, "not_found", "الواجب غير موجود أو لم يتم نشره بعد")

    branch_id = ass["branch_id"]

    # 4. Lock latest attempt
    latest = conn.execute("""
        SELECT id, attempt_number, status, grade_level 
        FROM submissions 
        WHERE assignment_id = %s AND student_id = %s AND deleted_at IS NULL 
        ORDER BY attempt_number DESC 
        LIMIT 1 
        FOR UPDATE
    """, (assignment_id, student_id)).fetchone()

    if latest:
        if latest["status"] in ("submitted", "graded"):
            raise ApiError(409, "already_submitted", "تم تقديم الحل مسبقاً وبانتظار المراجعة أو تم اعتماده")
        new_attempt = latest["attempt_number"] + 1
        parent_id = latest["id"]
    else:
        new_attempt = 1
        parent_id = None

    # Calculate is_late safely (R3-5)
    is_late_row = conn.execute(
        "SELECT COALESCE((now() AT TIME ZONE 'Asia/Riyadh')::date > %s, FALSE) AS is_late",
        (ass["due_date"],)
    ).fetchone()
    is_late = bool(is_late_row["is_late"])

    # 8. Savepoint CAS insert
    with conn.savepoint("submit_attempt_savepoint"):
        try:
            sub = conn.execute("""
                INSERT INTO submissions (
                    branch_id, assignment_id, student_id, attempt_number,
                    parent_submission_id, status, is_late, submitted_at, client_request_id
                ) VALUES (%s, %s, %s, %s, %s, 'submitted', %s, now(), %s)
                RETURNING *
            """, (branch_id, assignment_id, student_id, new_attempt, parent_id, is_late, client_request_id)).fetchone()
        except psycopg.errors.UniqueViolation:
            raise ApiError(409, "attempt_conflict", "تعارض في محاولة التسليم، يرجى المحاولة مرة أخرى")

    sub_id = sub["id"]

    # Attach files
    for f in files:
        conn.execute("""
            INSERT INTO submission_files (
                branch_id, submission_id, storage_key, uploaded_by_user_id,
                media_type, file_name, bytes
            ) VALUES (%s, %s, %s, %s, %s, %s, %s)
        """, (
            branch_id, sub_id, f["storage_key"], scope.user_id,
            f.get("media_type", "image"), f.get("file_name"), f.get("bytes", 0)
        ))

    # Notify teacher
    st = conn.execute("SELECT name FROM students WHERE id = %s", (student_id,)).fetchone()
    st_name = st["name"] if st else "طالب"
    conn.execute("""
        INSERT INTO notifications (
            branch_id, user_id, kind, title, body, target_type, target_id,
            priority, sender_user_id, action_url, sent_at
        ) VALUES (
            %s, %s, 'assignment', %s, %s, 'submission', %s,
            'normal', %s, %s, now()
        )
    """, (
        branch_id, ass["teacher_user_id"],
        f"حل جديد: {ass['title']}",
        f"قام الطالب/ـة {st_name} بتسليم حل واجب {ass['title']} (المحاولة {new_attempt})",
        str(sub_id), scope.user_id,
        f"gheras://assignment?id={assignment_id}&student_id={student_id}"
    ))

    write_audit(conn, scope.user_id, "submit_assignment", "submissions", sub_id, {
        "assignment_id": str(assignment_id), "student_id": str(student_id),
        "attempt_number": new_attempt, "is_late": is_late
    })

    return conn.execute(
        f"SELECT sb.*, {_submission_file_subquery()} FROM submissions sb WHERE sb.id = %s",
        (sub_id,),
    ).fetchone()


def review_submission_cas(
    conn,
    scope: Scope,
    submission_id: UUID,
    status: str,
    grade_level: str | None = None,
    revision_note: str | None = None,
    teacher_feedback_key: str | None = None,
) -> dict:
    """Sole review & grade surface (R3-1, R4-2).

    Enforces:
    1. Caller is teacher and student is in scope.student_ids.
    2. teacher_feedback_key verified in staged_uploads.
    3. CAS lock on latest attempt in 'submitted' status.
    4. Validates ASCII grade_level for 'graded'.
    5. Validates non-blank revision_note for 'revision_requested'.
    6. Sends targeted Arabic notification to guardian.
    """
    if scope.role != "teacher":
        raise ApiError(403, "forbidden", "المعلمة فقط مصرح لها بمراجعة وتقييم الحلول")

    # Fetch submission + assignment with lock
    sub = conn.execute("""
        SELECT sb.*, a.teacher_user_id, a.title AS assignment_title, s.name AS student_name
        FROM submissions sb
        JOIN assignments a ON a.id = sb.assignment_id
        JOIN students s ON s.id = sb.student_id
        WHERE sb.id = %s AND sb.deleted_at IS NULL
        FOR UPDATE
    """, (submission_id,)).fetchone()

    if not sub:
        raise ApiError(404, "not_found", "التسليم غير موجود")

    if scope.student_ids and sub["student_id"] not in scope.student_ids:
        raise ApiError(403, "out_of_scope", "الطالب خارج نطاق المعلمة")

    # Verify teacher feedback media key ownership if present
    if teacher_feedback_key:
        verify_staged_uploads_ownership(conn, scope.user_id, [teacher_feedback_key])

    # CAS check: only submitted can be reviewed
    if sub["status"] != "submitted":
        raise ApiError(409, "invalid_state", "يمكن تقييم المحاولات قيد المراجعة فقط")

    # Must be the latest attempt
    latest = conn.execute("""
        SELECT id FROM submissions 
        WHERE assignment_id = %s AND student_id = %s AND deleted_at IS NULL 
        ORDER BY attempt_number DESC LIMIT 1
    """, (sub["assignment_id"], sub["student_id"])).fetchone()

    if latest["id"] != sub["id"]:
        raise ApiError(409, "not_latest_attempt", "لا يمكن تقييم إلا أحدث محاولة تسليم للطالب")

    branch_id = sub["branch_id"]

    if status == "graded":
        if not grade_level or grade_level not in GRADE_LEVELS:
            raise ApiError(422, "invalid_grade", f"التقدير غير صالح: {grade_level}")

        updated = conn.execute("""
            UPDATE submissions SET
                status = 'graded',
                grade_level = %s,
                teacher_feedback_key = %s,
                reviewed_by_user_id = %s,
                reviewed_at = now(),
                updated_at = now()
            WHERE id = %s
            RETURNING *
        """, (grade_level, teacher_feedback_key, scope.user_id, submission_id)).fetchone()

        arabic_grade = GRADE_ARABIC_MAP.get(grade_level, grade_level)
        notif_title = f"تم تقييم الواجب: {sub['assignment_title']}"
        notif_body = f"حصل الطالب/ـة {sub['student_name']} على تقدير ({arabic_grade}) في واجب {sub['assignment_title']}"

    elif status == "revision_requested":
        if not revision_note or not revision_note.strip():
            raise ApiError(422, "missing_revision_note", "يجب كتابة ملاحظة توجيهية عند طلب إعادة الحل")

        updated = conn.execute("""
            UPDATE submissions SET
                status = 'revision_requested',
                revision_note = %s,
                teacher_feedback_key = %s,
                reviewed_by_user_id = %s,
                reviewed_at = now(),
                updated_at = now()
            WHERE id = %s
            RETURNING *
        """, (revision_note.strip(), teacher_feedback_key, scope.user_id, submission_id)).fetchone()

        notif_title = f"مطلوب إعادة حل الواجب: {sub['assignment_title']}"
        notif_body = f"طلبت المعلمة إعادة حل واجب {sub['assignment_title']} للطالب/ـة {sub['student_name']}: {revision_note.strip()}"
    else:
        raise ApiError(422, "invalid_status", f"حالة مراجعة غير صالحة: {status}")

    # Notify guardians
    g_rows = conn.execute("""
        SELECT u.id AS user_id FROM users u
        JOIN student_guardians sg ON sg.guardian_id = u.guardian_id
        WHERE sg.student_id = %s AND sg.deleted_at IS NULL
          AND u.is_active = true AND u.deleted_at IS NULL
    """, (sub["student_id"],)).fetchall()

    for g in g_rows:
        conn.execute("""
            INSERT INTO notifications (
                branch_id, user_id, kind, title, body, target_type, target_id,
                priority, sender_user_id, action_url, sent_at
            ) VALUES (
                %s, %s, 'assignment', %s, %s, 'submission', %s,
                'normal', %s, %s, now()
            )
        """, (
            branch_id, g["user_id"], notif_title, notif_body, str(submission_id),
            scope.user_id, f"gheras://assignment?id={sub['assignment_id']}&student_id={sub['student_id']}"
        ))

    write_audit(conn, scope.user_id, f"review_{status}", "submissions", submission_id, {
        "status": status, "grade_level": grade_level, "has_feedback_key": bool(teacher_feedback_key)
    })

    return conn.execute(
        f"SELECT sb.*, {_submission_file_subquery()} FROM submissions sb WHERE sb.id = %s",
        (submission_id,),
    ).fetchone()


def send_assignment_reminders(
    conn,
    scope: Scope,
    assignment_id: UUID,
    kind: str,
    student_id: UUID | None = None,
    dry_run: bool = False,
    client_request_id: UUID | None = None,
    message_text: str | None = None,
) -> dict:
    """Send reminders with dual cooldown and name preview (R4-4, T05).

    Cooldowns:
    - 24 hours per assignment for bulk reminders.
    - 24 hours per (assignment, student) for individual reminders.
    - Bulk reminder skips students who received an individual reminder within 24h.
    """
    if scope.role != "teacher":
        raise ApiError(403, "forbidden", "المعلمة فقط مصرح لها بإرسال التذكيرات")

    if kind not in ("bulk", "individual"):
        raise ApiError(422, "invalid_kind", f"نوع تذكير غير صالح: {kind}")

    ass = conn.execute(
        "SELECT id, branch_id, teacher_user_id, title, is_draft FROM assignments WHERE id = %s AND deleted_at IS NULL FOR UPDATE",
        (assignment_id,),
    ).fetchone()
    if not ass:
        raise ApiError(404, "not_found", "الواجب غير موجود")
    if ass["is_draft"]:
        raise ApiError(409, "draft_assignment", "لا يمكن إرسال تذكيرات لواجب مسودة")
    if ass["teacher_user_id"] != scope.user_id:
        raise ApiError(403, "out_of_scope", "الواجب خارج نطاق المعلمة")

    # Idempotency via client_request_id
    if client_request_id:
        existing = conn.execute(
            "SELECT * FROM assignment_reminders WHERE client_request_id = %s AND deleted_at IS NULL",
            (client_request_id,),
        ).fetchone()
        if existing:
            return {"id": existing["id"], "kind": existing["kind"], "already_sent": True}

    branch_id = ass["branch_id"]

    if kind == "individual":
        if not student_id:
            raise ApiError(422, "missing_student", "يجب تحديد الطالب للتذكير الفردي")
        enrolled = conn.execute(
            "SELECT 1 FROM assignment_students WHERE assignment_id = %s AND student_id = %s AND deleted_at IS NULL",
            (assignment_id, student_id),
        ).fetchone()
        if not enrolled:
            raise ApiError(404, "student_not_enrolled", "الطالب غير مسجل في هذا الواجب")

        # 24h cooldown for (assignment, student)
        cooldown = conn.execute("""
            SELECT 1 FROM assignment_reminders ar
            JOIN assignment_reminder_recipients arr ON arr.reminder_id = ar.id
            WHERE ar.assignment_id = %s AND arr.student_id = %s
              AND ar.sent_at >= now() - INTERVAL '24 hours'
              AND ar.deleted_at IS NULL
            LIMIT 1
        """, (assignment_id, student_id)).fetchone()
        if cooldown:
            raise ApiError(429, "reminder_cooldown", "تم إرسال تذكير لهذا الطالب خلال الـ 24 ساعة الماضية")

        target_ids = [student_id]
        skipped_ids = []

    else:  # bulk
        # 24h cooldown for bulk on this assignment
        cooldown = conn.execute("""
            SELECT 1 FROM assignment_reminders
            WHERE assignment_id = %s AND kind = 'bulk'
              AND sent_at >= now() - INTERVAL '24 hours'
              AND deleted_at IS NULL
            LIMIT 1
        """, (assignment_id,)).fetchone()
        if cooldown:
            raise ApiError(429, "reminder_cooldown", "تم إرسال تذكير جماعي لهذا الواجب خلال الـ 24 ساعة الماضية")

        # Students needing reminder: no submission or latest is revision_requested
        st_rows = conn.execute("""
            SELECT s.id, s.name,
                (SELECT sb.status FROM submissions sb 
                 WHERE sb.assignment_id = %s AND sb.student_id = s.id AND sb.deleted_at IS NULL 
                 ORDER BY sb.attempt_number DESC LIMIT 1) AS latest_status
            FROM assignment_students ast
            JOIN students s ON s.id = ast.student_id
            WHERE ast.assignment_id = %s AND ast.deleted_at IS NULL
        """, (assignment_id, assignment_id)).fetchall()

        eligible = [r for r in st_rows if r["latest_status"] is None or r["latest_status"] in ("revision_requested", "returned")]

        # Filter out those who received individual reminder in last 24h
        recent_indiv = conn.execute("""
            SELECT arr.student_id FROM assignment_reminders ar
            JOIN assignment_reminder_recipients arr ON arr.reminder_id = ar.id
            WHERE ar.assignment_id = %s AND ar.kind = 'individual'
              AND ar.sent_at >= now() - INTERVAL '24 hours'
              AND ar.deleted_at IS NULL
        """, (assignment_id,)).fetchall()
        indiv_set = {r["student_id"] for r in recent_indiv}

        target_ids = [r["id"] for r in eligible if r["id"] not in indiv_set]
        skipped_ids = [r["id"] for r in eligible if r["id"] in indiv_set]

    # Fetch names for preview/reporting
    all_involved = target_ids + skipped_ids
    names_map = {}
    if all_involved:
        names_rows = conn.execute("SELECT id, name FROM students WHERE id = ANY(%s)", (all_involved,)).fetchall()
        names_map = {r["id"]: r["name"] for r in names_rows}

    target_names = [names_map.get(sid, "طالب") for sid in target_ids]
    skipped_names = [names_map.get(sid, "طالب") for sid in skipped_ids]

    if dry_run:
        return {
            "dry_run": True,
            "kind": kind,
            "target_count": len(target_ids),
            "target_students": target_names,
            "skipped_count": len(skipped_ids),
            "skipped_students": skipped_names,
        }

    # Record reminder
    msg = message_text or f"تذكير بتسليم واجب: {ass['title']}"
    rem = conn.execute("""
        INSERT INTO assignment_reminders (
            branch_id, assignment_id, sent_by_user_id, kind, message_text, client_request_id
        ) VALUES (%s, %s, %s, %s, %s, %s)
        RETURNING *
    """, (branch_id, assignment_id, scope.user_id, kind, msg, client_request_id)).fetchone()

    rem_id = rem["id"]
    for sid in target_ids:
        conn.execute("""
            INSERT INTO assignment_reminder_recipients (reminder_id, student_id)
            VALUES (%s, %s)
        """, (rem_id, sid))

        # Notify guardians
        g_rows = conn.execute("""
            SELECT u.id AS user_id FROM users u
            JOIN student_guardians sg ON sg.guardian_id = u.guardian_id
            WHERE sg.student_id = %s AND sg.deleted_at IS NULL
              AND u.is_active = true AND u.deleted_at IS NULL
        """, (sid,)).fetchall()
        for g in g_rows:
            conn.execute("""
                INSERT INTO notifications (
                    branch_id, user_id, kind, title, body, target_type, target_id,
                    priority, sender_user_id, action_url, sent_at
                ) VALUES (
                    %s, %s, 'assignment', %s, %s, 'assignment', %s,
                    'normal', %s, %s, now()
                )
            """, (
                branch_id, g["user_id"],
                f"تذكير واجب: {ass['title']}",
                f"تذكير للطالب/ـة {names_map.get(sid, '')}: {msg}",
                str(assignment_id), scope.user_id,
                f"gheras://assignment?id={assignment_id}&student_id={sid}"
            ))

    write_audit(conn, scope.user_id, "send_assignment_reminders", "assignment_reminders", rem_id, {
        "assignment_id": str(assignment_id), "kind": kind, "target_count": len(target_ids)
    })

    return {
        "id": rem_id,
        "kind": kind,
        "target_count": len(target_ids),
        "target_students": target_names,
        "skipped_count": len(skipped_ids),
        "skipped_students": skipped_names,
    }
