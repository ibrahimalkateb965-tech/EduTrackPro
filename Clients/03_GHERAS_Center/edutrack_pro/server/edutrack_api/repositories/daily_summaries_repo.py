"""SQL persistence for daily lesson summaries and private student notes (Milestone M4)."""

from __future__ import annotations

import json
from datetime import date
from typing import Any
from uuid import UUID

import psycopg
from psycopg.types.json import Jsonb


def list_daily_summaries(
    conn: psycopg.Connection,
    room_id: UUID | None = None,
    room_ids: list[UUID] | frozenset[UUID] | None = None,
    summary_date: date | str | None = None,
    is_published: bool | None = None,
) -> list[dict[str, Any]]:
    """List daily summaries with optional filters for staff views."""
    query = """
        SELECT 
            ds.id,
            ds.branch_id,
            ds.room_id,
            r.name AS room_name,
            ds.teacher_user_id,
            u.username AS teacher_name,
            ds.summary_date,
            ds.subject,
            ds.title,
            ds.class_summary,
            ds.homework_summary,
            ds.attachments,
            ds.is_published,
            ds.published_at,
            ds.created_at,
            ds.updated_at,
            COUNT(dsn.id) AS notes_count
        FROM daily_summaries ds
        JOIN rooms r ON r.id = ds.room_id AND r.deleted_at IS NULL
        JOIN users u ON u.id = ds.teacher_user_id AND u.deleted_at IS NULL
        LEFT JOIN daily_summary_notes dsn ON dsn.summary_id = ds.id AND dsn.deleted_at IS NULL
        WHERE ds.deleted_at IS NULL
    """
    params: list[Any] = []

    if room_id is not None:
        query += " AND ds.room_id = %s"
        params.append(room_id)
    elif room_ids is not None:
        query += " AND ds.room_id = ANY(%s)"
        params.append(list(room_ids))

    if summary_date is not None:
        query += " AND ds.summary_date = %s"
        params.append(summary_date)

    if is_published is not None:
        query += " AND ds.is_published = %s"
        params.append(is_published)

    query += """
        GROUP BY ds.id, r.name, u.username
        ORDER BY ds.summary_date DESC, ds.created_at DESC
    """

    with conn.cursor() as cur:
        cur.execute(query, params)
        return [dict(row) for row in cur.fetchall()]


def get_daily_summary_by_id(
    conn: psycopg.Connection,
    summary_id: UUID,
    include_notes: bool = True,
) -> dict[str, Any] | None:
    """Get single daily summary with all private student notes (staff view)."""
    query = """
        SELECT 
            ds.id,
            ds.branch_id,
            ds.room_id,
            r.name AS room_name,
            ds.teacher_user_id,
            u.username AS teacher_name,
            ds.summary_date,
            ds.subject,
            ds.title,
            ds.class_summary,
            ds.homework_summary,
            ds.attachments,
            ds.is_published,
            ds.published_at,
            ds.created_at,
            ds.updated_at
        FROM daily_summaries ds
        JOIN rooms r ON r.id = ds.room_id AND r.deleted_at IS NULL
        JOIN users u ON u.id = ds.teacher_user_id AND u.deleted_at IS NULL
        WHERE ds.id = %s AND ds.deleted_at IS NULL
    """
    with conn.cursor() as cur:
        cur.execute(query, (summary_id,))
        row = cur.fetchone()
        if not row:
            return None

        result = dict(row)

        if include_notes:
            cur.execute("""
                SELECT 
                    dsn.id,
                    dsn.student_id,
                    s.name AS student_name,
                    dsn.private_note,
                    dsn.created_at,
                    dsn.updated_at
                FROM daily_summary_notes dsn
                JOIN students s ON s.id = dsn.student_id AND s.deleted_at IS NULL
                WHERE dsn.summary_id = %s AND dsn.deleted_at IS NULL
                ORDER BY s.name ASC
            """, (summary_id,))
            result["student_notes"] = [dict(n) for n in cur.fetchall()]
        else:
            result["student_notes"] = []

        return result


def upsert_daily_summary(
    conn: psycopg.Connection,
    branch_id: UUID,
    room_id: UUID,
    teacher_user_id: UUID,
    summary_date: date | str,
    subject: str,
    title: str,
    class_summary: str,
    homework_summary: str | None = None,
    attachments: list[dict[str, Any]] | None = None,
    is_published: bool = False,
    student_notes: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Upsert class lesson summary and student private notes atomically."""
    att_json = Jsonb(attachments or [])

    with conn.transaction():
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO daily_summaries (
                    branch_id, room_id, teacher_user_id, summary_date,
                    subject, title, class_summary, homework_summary,
                    attachments, is_published, published_at
                ) VALUES (
                    %s, %s, %s, %s,
                    %s, %s, %s, %s,
                    %s, %s, CASE WHEN %s THEN now() ELSE NULL END
                )
                ON CONFLICT (room_id, summary_date, subject) WHERE deleted_at IS NULL
                DO UPDATE SET
                    title = EXCLUDED.title,
                    class_summary = EXCLUDED.class_summary,
                    homework_summary = EXCLUDED.homework_summary,
                    attachments = EXCLUDED.attachments,
                    is_published = CASE WHEN daily_summaries.is_published THEN TRUE ELSE EXCLUDED.is_published END,
                    published_at = CASE 
                        WHEN daily_summaries.published_at IS NOT NULL THEN daily_summaries.published_at
                        WHEN EXCLUDED.is_published THEN now() 
                        ELSE NULL 
                    END,
                    updated_at = now()
                RETURNING *
            """, (
                branch_id, room_id, teacher_user_id, summary_date,
                subject, title, class_summary, homework_summary,
                att_json, is_published, is_published
            ))
            summary = dict(cur.fetchone())
            summary_id = summary["id"]

            # B6: Synchronize student private notes (remove notes no longer in payload)
            if student_notes is not None:
                submitted_student_ids = [note.get("student_id") for note in student_notes if note.get("student_id")]
                if submitted_student_ids:
                    cur.execute("""
                        UPDATE daily_summary_notes
                        SET deleted_at = now()
                        WHERE summary_id = %s AND deleted_at IS NULL AND student_id != ALL(%s)
                    """, (summary_id, submitted_student_ids))
                else:
                    cur.execute("""
                        UPDATE daily_summary_notes
                        SET deleted_at = now()
                        WHERE summary_id = %s AND deleted_at IS NULL
                    """, (summary_id,))

                for note in student_notes:
                    st_id = note.get("student_id")
                    p_note = note.get("private_note", "").strip()
                    if st_id and p_note:
                        cur.execute("""
                            SELECT id, deleted_at FROM daily_summary_notes
                            WHERE summary_id = %s AND student_id = %s
                            ORDER BY created_at DESC LIMIT 1
                        """, (summary_id, st_id))
                        existing_note = cur.fetchone()
                        if existing_note:
                            cur.execute("""
                                UPDATE daily_summary_notes
                                SET private_note = %s, deleted_at = NULL, updated_at = now()
                                WHERE id = %s
                            """, (p_note, existing_note["id"]))
                        else:
                            cur.execute("""
                                INSERT INTO daily_summary_notes (
                                    branch_id, summary_id, student_id, private_note
                                ) VALUES (
                                    %s, %s, %s, %s
                                )
                            """, (branch_id, summary_id, st_id, p_note))

            return get_daily_summary_by_id(conn, summary_id, include_notes=True)


def publish_daily_summary(
    conn: psycopg.Connection,
    summary_id: UUID,
) -> dict[str, Any] | None:
    """Transition summary to published state and record published_at timestamp."""
    with conn.transaction():
        with conn.cursor() as cur:
            cur.execute("""
                UPDATE daily_summaries
                SET is_published = TRUE,
                    published_at = COALESCE(published_at, now()),
                    updated_at = now()
                WHERE id = %s AND deleted_at IS NULL
                RETURNING *
            """, (summary_id,))
            row = cur.fetchone()
            return dict(row) if row else None


def get_guardian_daily_summaries(
    conn: psycopg.Connection,
    student_id: UUID,
    summary_date: date | str | None = None,
) -> list[dict[str, Any]]:
    """
    Retrieve published summaries for a student's assigned classroom.
    ENFORCES STRICT ANTI-LEAK: Only the private note for THIS student is included.
    """
    query = """
        SELECT 
            ds.id,
            ds.branch_id,
            ds.room_id,
            r.name AS room_name,
            ds.teacher_user_id,
            u.username AS teacher_name,
            ds.summary_date,
            ds.subject,
            ds.title,
            ds.class_summary,
            ds.homework_summary,
            ds.attachments,
            ds.is_published,
            ds.published_at,
            dsn.private_note AS student_private_note
        FROM daily_summaries ds
        JOIN students s ON s.id = %(student_id)s AND s.room_id = ds.room_id AND s.deleted_at IS NULL
        JOIN rooms r ON r.id = ds.room_id AND r.deleted_at IS NULL
        JOIN users u ON u.id = ds.teacher_user_id AND u.deleted_at IS NULL
        LEFT JOIN daily_summary_notes dsn ON dsn.summary_id = ds.id AND dsn.student_id = %(student_id)s AND dsn.deleted_at IS NULL
        WHERE ds.is_published = TRUE 
        AND ds.deleted_at IS NULL
    """
    params: dict[str, Any] = {"student_id": student_id}

    if summary_date is not None:
        query += " AND ds.summary_date = %(summary_date)s"
        params["summary_date"] = summary_date

    query += " ORDER BY ds.summary_date DESC, ds.created_at DESC"

    with conn.cursor() as cur:
        cur.execute(query, params)
        return [dict(row) for row in cur.fetchall()]
