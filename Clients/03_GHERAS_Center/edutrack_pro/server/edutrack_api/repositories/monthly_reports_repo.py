"""SQL persistence for monthly student reports and evaluations (Milestone M5)."""

from __future__ import annotations

import json
from datetime import date, datetime
from typing import Any
from uuid import UUID

import psycopg
from psycopg.types.json import Jsonb


def compute_student_attendance_stats(
    conn: psycopg.Connection,
    student_id: UUID,
    month: str,
) -> dict[str, Any]:
    """Compute automated monthly attendance and commitment statistics for a student.
    
    month is formatted as 'YYYY-MM' (e.g. '2026-10').
    Calculates: total_days, present, absent, excused, late, and attendance_pct.
    """
    year, month_num = map(int, month.split("-"))
    start_date = date(year, month_num, 1)
    if month_num == 12:
        end_date = date(year + 1, 1, 1)
    else:
        end_date = date(year, month_num + 1, 1)

    query = """
        SELECT status, count(*) AS cnt
        FROM student_attendance
        WHERE student_id = %s
          AND date >= %s AND date < %s
          AND is_draft = FALSE
          AND deleted_at IS NULL
        GROUP BY status
    """
    rows = conn.execute(query, (student_id, start_date, end_date)).fetchall()

    counts: dict[str, int] = {
        "present": 0,
        "absent": 0,
        "excused": 0,
        "late": 0,
        "permitted": 0,
    }

    for r in rows:
        st = r["status"]
        c = r["cnt"]
        if st == "حاضر":
            counts["present"] += c
        elif st == "غائب":
            counts["absent"] += c
        elif st == "غياب بعذر":
            counts["excused"] += c
        elif st == "متأخر":
            counts["late"] += c
        elif st == "مستأذن":
            counts["permitted"] += c

    total_days = sum(counts.values())
    present_days = counts["present"] + counts["late"] + counts["permitted"]
    attendance_pct = round((present_days / total_days * 100), 1) if total_days > 0 else 0.0

    return {
        "total_days": total_days,
        "present": counts["present"],
        "absent": counts["absent"],
        "excused": counts["excused"],
        "late": counts["late"],
        "attendance_pct": attendance_pct,
    }


def get_monthly_report_by_id(
    conn: psycopg.Connection,
    report_id: UUID,
) -> dict[str, Any] | None:
    """Retrieve a single monthly report by its UUID."""
    query = """
        SELECT 
            mr.id,
            mr.branch_id,
            mr.student_id,
            s.name AS student_name,
            mr.room_id,
            r.name AS room_name,
            mr.month,
            mr.teacher_user_id,
            u_teach.username AS teacher_name,
            mr.status,
            mr.attendance_stats,
            mr.subject_ratings,
            mr.strengths,
            mr.improvements,
            mr.recommendations,
            mr.revision_notes,
            mr.submitted_at,
            mr.reviewed_by_user_id,
            u_rev.username AS reviewer_name,
            mr.reviewed_at,
            mr.published_at,
            mr.created_at,
            mr.updated_at
        FROM monthly_student_reports mr
        JOIN students s ON s.id = mr.student_id AND s.deleted_at IS NULL
        JOIN rooms r ON r.id = mr.room_id AND r.deleted_at IS NULL
        JOIN users u_teach ON u_teach.id = mr.teacher_user_id AND u_teach.deleted_at IS NULL
        LEFT JOIN users u_rev ON u_rev.id = mr.reviewed_by_user_id AND u_rev.deleted_at IS NULL
        WHERE mr.id = %s AND mr.deleted_at IS NULL
    """
    return conn.execute(query, (report_id,)).fetchone()


def get_monthly_report_by_student_month(
    conn: psycopg.Connection,
    student_id: UUID,
    month: str,
) -> dict[str, Any] | None:
    """Retrieve an active report for a specific student and month."""
    query = """
        SELECT 
            mr.id,
            mr.branch_id,
            mr.student_id,
            s.name AS student_name,
            mr.room_id,
            r.name AS room_name,
            mr.month,
            mr.teacher_user_id,
            u_teach.username AS teacher_name,
            mr.status,
            mr.attendance_stats,
            mr.subject_ratings,
            mr.strengths,
            mr.improvements,
            mr.recommendations,
            mr.revision_notes,
            mr.submitted_at,
            mr.reviewed_by_user_id,
            u_rev.username AS reviewer_name,
            mr.reviewed_at,
            mr.published_at,
            mr.created_at,
            mr.updated_at
        FROM monthly_student_reports mr
        JOIN students s ON s.id = mr.student_id AND s.deleted_at IS NULL
        JOIN rooms r ON r.id = mr.room_id AND r.deleted_at IS NULL
        JOIN users u_teach ON u_teach.id = mr.teacher_user_id AND u_teach.deleted_at IS NULL
        LEFT JOIN users u_rev ON u_rev.id = mr.reviewed_by_user_id AND u_rev.deleted_at IS NULL
        WHERE mr.student_id = %s AND mr.month = %s AND mr.deleted_at IS NULL
    """
    return conn.execute(query, (student_id, month)).fetchone()


def list_monthly_reports(
    conn: psycopg.Connection,
    room_id: UUID | None = None,
    room_ids: list[UUID] | frozenset[UUID] | None = None,
    month: str | None = None,
    status: str | None = None,
    student_id: UUID | None = None,
) -> list[dict[str, Any]]:
    """List monthly reports with optional classroom, month, status, and student filters."""
    query = """
        SELECT 
            mr.id,
            mr.branch_id,
            mr.student_id,
            s.name AS student_name,
            mr.room_id,
            r.name AS room_name,
            mr.month,
            mr.teacher_user_id,
            u_teach.username AS teacher_name,
            mr.status,
            mr.attendance_stats,
            mr.subject_ratings,
            mr.strengths,
            mr.improvements,
            mr.recommendations,
            mr.revision_notes,
            mr.submitted_at,
            mr.reviewed_by_user_id,
            u_rev.username AS reviewer_name,
            mr.reviewed_at,
            mr.published_at,
            mr.created_at,
            mr.updated_at
        FROM monthly_student_reports mr
        JOIN students s ON s.id = mr.student_id AND s.deleted_at IS NULL
        JOIN rooms r ON r.id = mr.room_id AND r.deleted_at IS NULL
        JOIN users u_teach ON u_teach.id = mr.teacher_user_id AND u_teach.deleted_at IS NULL
        LEFT JOIN users u_rev ON u_rev.id = mr.reviewed_by_user_id AND u_rev.deleted_at IS NULL
        WHERE mr.deleted_at IS NULL
    """
    params: list[Any] = []

    if room_id is not None:
        query += " AND mr.room_id = %s"
        params.append(room_id)
    elif room_ids is not None:
        query += " AND mr.room_id = ANY(%s)"
        params.append(list(room_ids))

    if month is not None:
        query += " AND mr.month = %s"
        params.append(month)

    if status is not None:
        query += " AND mr.status = %s"
        params.append(status)

    if student_id is not None:
        query += " AND mr.student_id = %s"
        params.append(student_id)

    query += " ORDER BY mr.month DESC, s.name ASC"
    return conn.execute(query, params).fetchall()


def upsert_monthly_report(
    conn: psycopg.Connection,
    branch_id: UUID,
    student_id: UUID,
    room_id: UUID,
    month: str,
    teacher_user_id: UUID,
    attendance_stats: dict[str, Any],
    subject_ratings: list[dict[str, Any]],
    strengths: str | None = None,
    improvements: str | None = None,
    recommendations: str | None = None,
    action: str = "save_draft",
) -> dict[str, Any]:
    """Create or update a student monthly report draft or submit for approval."""
    if action not in ("save_draft", "submit"):
        raise ValueError("Invalid action; must be 'save_draft' or 'submit'")

    existing = get_monthly_report_by_student_month(conn, student_id, month)

    target_status = "pending_approval" if action == "submit" else "draft"

    if existing:
        curr_status = existing["status"]
        if curr_status in ("pending_approval", "approved", "published"):
            raise ValueError(f"لا يمكن تعديل التقرير لأنه في حالة '{curr_status}'")

        # If it was needs_revision and action is save_draft, keep needs_revision
        if curr_status == "needs_revision" and action == "save_draft":
            target_status = "needs_revision"

        update_query = """
            UPDATE monthly_student_reports
            SET 
                room_id = %s,
                teacher_user_id = %s,
                status = %s,
                attendance_stats = %s,
                subject_ratings = %s,
                strengths = %s,
                improvements = %s,
                recommendations = %s,
                submitted_at = CASE WHEN %s = 'submit' THEN now() ELSE submitted_at END,
                revision_notes = CASE WHEN %s = 'submit' THEN NULL ELSE revision_notes END,
                updated_at = now()
            WHERE id = %s
            RETURNING id
        """
        conn.execute(
            update_query,
            (
                room_id,
                teacher_user_id,
                target_status,
                Jsonb(attendance_stats),
                Jsonb(subject_ratings),
                strengths,
                improvements,
                recommendations,
                action,
                action,
                existing["id"],
            ),
        )
        return get_monthly_report_by_id(conn, existing["id"])  # type: ignore

    insert_query = """
        INSERT INTO monthly_student_reports (
            branch_id,
            student_id,
            room_id,
            month,
            teacher_user_id,
            status,
            attendance_stats,
            subject_ratings,
            strengths,
            improvements,
            recommendations,
            submitted_at
        ) VALUES (
            %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
            CASE WHEN %s = 'submit' THEN now() ELSE NULL END
        )
        ON CONFLICT (student_id, month) WHERE deleted_at IS NULL DO NOTHING
        RETURNING id
    """
    row = conn.execute(
        insert_query,
        (
            branch_id,
            student_id,
            room_id,
            month,
            teacher_user_id,
            target_status,
            Jsonb(attendance_stats),
            Jsonb(subject_ratings),
            strengths,
            improvements,
            recommendations,
            action,
        ),
    ).fetchone()

    if row is None:
        # A concurrent writer created the report after our lookup; retry as an update.
        return upsert_monthly_report(
            conn, branch_id, student_id, room_id, month, teacher_user_id, attendance_stats,
            subject_ratings, strengths, improvements, recommendations, action,
        )

    return get_monthly_report_by_id(conn, row["id"])  # type: ignore


def review_monthly_report(
    conn: psycopg.Connection,
    report_id: UUID,
    reviewer_user_id: UUID,
    action: str,
    revision_notes: str | None = None,
) -> dict[str, Any]:
    """Supervisor review: approve or request revision."""
    report = get_monthly_report_by_id(conn, report_id)
    if not report:
        raise ValueError("التقرير غير موجود")

    if report["status"] != "pending_approval":
        raise ValueError(f"لا يمكن مراجعة التقرير في حالته الحالية: '{report['status']}'")

    if action == "approve":
        row = conn.execute("""
            UPDATE monthly_student_reports
            SET 
                status = 'approved',
                reviewed_by_user_id = %s,
                reviewed_at = now(),
                revision_notes = NULL,
                updated_at = now()
            WHERE id = %s AND status = 'pending_approval' AND deleted_at IS NULL
            RETURNING id
        """, (reviewer_user_id, report_id)).fetchone()
    elif action == "request_revision":
        if not revision_notes or len(revision_notes.strip()) < 3:
            raise ValueError("ملاحظات طلب التعديل مطلوبة ولا تقل عن 3 أحرف")
        row = conn.execute("""
            UPDATE monthly_student_reports
            SET 
                status = 'needs_revision',
                reviewed_by_user_id = %s,
                reviewed_at = now(),
                revision_notes = %s,
                updated_at = now()
            WHERE id = %s AND status = 'pending_approval' AND deleted_at IS NULL
            RETURNING id
        """, (reviewer_user_id, revision_notes.strip(), report_id)).fetchone()
    else:
        raise ValueError("إجراء غير صالح؛ يجب أن يكون 'approve' أو 'request_revision'")

    # Another reviewer changed the status between the read and the update
    if not row:
        raise ValueError("تمت مراجعة التقرير بالفعل من مستخدم آخر")

    return get_monthly_report_by_id(conn, report_id)  # type: ignore


def publish_monthly_report(
    conn: psycopg.Connection,
    report_id: UUID,
    publisher_user_id: UUID,
) -> dict[str, Any]:
    """Publish an approved monthly report to make it visible to guardians."""
    report = get_monthly_report_by_id(conn, report_id)
    if not report:
        raise ValueError("التقرير غير موجود")

    if report["status"] != "approved":
        raise ValueError(f"لا يمكن نشر التقرير في حالته الحالية: '{report['status']}'")

    row = conn.execute("""
        UPDATE monthly_student_reports
        SET 
            status = 'published',
            published_at = now(),
            reviewed_by_user_id = COALESCE(reviewed_by_user_id, %s),
            reviewed_at = COALESCE(reviewed_at, now()),
            updated_at = now()
        WHERE id = %s AND status = 'approved' AND deleted_at IS NULL
        RETURNING id
    """, (publisher_user_id, report_id)).fetchone()

    # A concurrent publish won the race; do not notify guardians twice
    if not row:
        raise ValueError("تم نشر التقرير بالفعل من مستخدم آخر")

    return get_monthly_report_by_id(conn, report_id)  # type: ignore


def batch_publish_monthly_reports(
    conn: psycopg.Connection,
    room_id: UUID,
    month: str,
    publisher_user_id: UUID,
) -> list[dict[str, Any]]:
    """Publish all approved monthly reports for a classroom and month in a single batch."""
    rows = conn.execute("""
        UPDATE monthly_student_reports
        SET 
            status = 'published',
            published_at = now(),
            reviewed_by_user_id = COALESCE(reviewed_by_user_id, %s),
            reviewed_at = COALESCE(reviewed_at, now()),
            updated_at = now()
        WHERE room_id = %s
          AND month = %s
          AND status = 'approved'
          AND deleted_at IS NULL
        RETURNING id
    """, (publisher_user_id, room_id, month)).fetchall()

    return [get_monthly_report_by_id(conn, r["id"]) for r in rows]  # type: ignore


def list_guardian_monthly_reports(
    conn: psycopg.Connection,
    guardian_student_ids: list[UUID],
    month: str | None = None,
) -> list[dict[str, Any]]:
    """Retrieve published monthly reports for guardian's children (Anti-Draft Invariant)."""
    if not guardian_student_ids:
        return []

    query = """
        SELECT 
            mr.id,
            mr.branch_id,
            mr.student_id,
            s.name AS student_name,
            mr.room_id,
            r.name AS room_name,
            mr.month,
            mr.teacher_user_id,
            u_teach.username AS teacher_name,
            mr.status,
            mr.attendance_stats,
            mr.subject_ratings,
            mr.strengths,
            mr.improvements,
            mr.recommendations,
            mr.published_at,
            mr.created_at,
            mr.updated_at
        FROM monthly_student_reports mr
        JOIN students s ON s.id = mr.student_id AND s.deleted_at IS NULL
        JOIN rooms r ON r.id = mr.room_id AND r.deleted_at IS NULL
        JOIN users u_teach ON u_teach.id = mr.teacher_user_id AND u_teach.deleted_at IS NULL
        WHERE mr.student_id = ANY(%s)
          AND mr.status = 'published'
          AND mr.deleted_at IS NULL
    """
    params: list[Any] = [guardian_student_ids]

    if month is not None:
        query += " AND mr.month = %s"
        params.append(month)

    query += " ORDER BY mr.month DESC, s.name ASC"
    return conn.execute(query, params).fetchall()


def get_guardian_monthly_report_by_id(
    conn: psycopg.Connection,
    report_id: UUID,
    guardian_student_ids: list[UUID],
) -> dict[str, Any] | None:
    """Retrieve single published report for guardian verifying student ownership."""
    if not guardian_student_ids:
        return None

    query = """
        SELECT 
            mr.id,
            mr.branch_id,
            mr.student_id,
            s.name AS student_name,
            mr.room_id,
            r.name AS room_name,
            mr.month,
            mr.teacher_user_id,
            u_teach.username AS teacher_name,
            mr.status,
            mr.attendance_stats,
            mr.subject_ratings,
            mr.strengths,
            mr.improvements,
            mr.recommendations,
            mr.published_at,
            mr.created_at,
            mr.updated_at
        FROM monthly_student_reports mr
        JOIN students s ON s.id = mr.student_id AND s.deleted_at IS NULL
        JOIN rooms r ON r.id = mr.room_id AND r.deleted_at IS NULL
        JOIN users u_teach ON u_teach.id = mr.teacher_user_id AND u_teach.deleted_at IS NULL
        WHERE mr.id = %s
          AND mr.student_id = ANY(%s)
          AND mr.status = 'published'
          AND mr.deleted_at IS NULL
    """
    return conn.execute(query, (report_id, guardian_student_ids)).fetchone()
