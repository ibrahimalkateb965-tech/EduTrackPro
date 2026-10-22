"""SQL persistence and querying for messaging threads and thread messages (Milestone M4)."""

from __future__ import annotations

from typing import Any
from uuid import UUID

import psycopg


def list_threads(
    conn: psycopg.Connection,
    user_id: UUID,
    role: str,
    student_ids: list[UUID] | None = None,
    room_ids: list[UUID] | None = None,
    filter_student_id: UUID | None = None,
    branch_id: UUID | None = None,
) -> list[dict[str, Any]]:
    """List threads accessible to the user based on role and scope."""
    base_query = """
        SELECT 
            t.id,
            t.branch_id,
            t.student_id,
            s.name AS student_name,
            s.room_id,
            r.name AS room_name,
            t.guardian_user_id,
            COALESCE(g.name, gu.username) AS guardian_name,
            t.staff_user_id,
            su.username AS staff_name,
            t.subject,
            t.linked_assignment_id,
            a.title AS linked_assignment_title,
            t.linked_summary_id,
            ds.title AS linked_summary_title,
            t.status,
            t.last_message_at,
            t.last_message_preview,
            t.created_at,
            t.updated_at,
            COUNT(tm.id) FILTER (
                WHERE tm.sender_user_id != %(user_id)s 
                AND tm.read_at IS NULL 
                AND tm.deleted_at IS NULL
            ) AS unread_count
        FROM threads t
        JOIN students s ON s.id = t.student_id AND s.deleted_at IS NULL
        LEFT JOIN rooms r ON r.id = s.room_id AND r.deleted_at IS NULL
        JOIN users gu ON gu.id = t.guardian_user_id AND gu.deleted_at IS NULL
        LEFT JOIN guardians g ON g.id = gu.guardian_id AND g.deleted_at IS NULL
        JOIN users su ON su.id = t.staff_user_id AND su.deleted_at IS NULL
        LEFT JOIN assignments a ON a.id = t.linked_assignment_id AND a.deleted_at IS NULL
        LEFT JOIN daily_summaries ds ON ds.id = t.linked_summary_id AND ds.deleted_at IS NULL
        LEFT JOIN thread_messages tm ON tm.thread_id = t.id AND tm.deleted_at IS NULL
        WHERE t.deleted_at IS NULL
    """
    params: dict[str, Any] = {"user_id": user_id}
    conditions = []

    if role == "guardian":
        conditions.append("t.guardian_user_id = %(user_id)s")
        if student_ids:
            conditions.append("t.student_id = ANY(%(student_ids)s)")
            params["student_ids"] = list(student_ids)
    elif role == "teacher":
        conditions.append("t.staff_user_id = %(user_id)s")
    elif role in ("supervisor", "manager"):
        if branch_id:
            conditions.append("t.branch_id = %(branch_id)s")
            params["branch_id"] = branch_id

    if filter_student_id is not None:
        conditions.append("t.student_id = %(filter_student_id)s")
        params["filter_student_id"] = filter_student_id

    if conditions:
        base_query += " AND " + " AND ".join(conditions)

    base_query += """
        GROUP BY 
            t.id, s.name, s.room_id, r.name, gu.username, g.name, 
            su.username, a.title, ds.title
        ORDER BY t.last_message_at DESC
    """

    with conn.cursor() as cur:
        cur.execute(base_query, params)
        return [dict(row) for row in cur.fetchall()]


def get_thread_by_id(
    conn: psycopg.Connection,
    thread_id: UUID,
    user_id: UUID | None = None,
) -> dict[str, Any] | None:
    """Retrieve full thread details including party names and unread count."""
    query = """
        SELECT 
            t.id,
            t.branch_id,
            t.student_id,
            s.name AS student_name,
            s.room_id,
            r.name AS room_name,
            t.guardian_user_id,
            COALESCE(g.name, gu.username) AS guardian_name,
            t.staff_user_id,
            su.username AS staff_name,
            t.subject,
            t.linked_assignment_id,
            a.title AS linked_assignment_title,
            t.linked_summary_id,
            ds.title AS linked_summary_title,
            t.status,
            t.last_message_at,
            t.last_message_preview,
            t.created_at,
            t.updated_at,
            COUNT(tm.id) FILTER (
                WHERE (%(user_id)s IS NULL OR tm.sender_user_id != %(user_id)s)
                AND tm.read_at IS NULL 
                AND tm.deleted_at IS NULL
            ) AS unread_count
        FROM threads t
        JOIN students s ON s.id = t.student_id AND s.deleted_at IS NULL
        LEFT JOIN rooms r ON r.id = s.room_id AND r.deleted_at IS NULL
        JOIN users gu ON gu.id = t.guardian_user_id AND gu.deleted_at IS NULL
        LEFT JOIN guardians g ON g.id = gu.guardian_id AND g.deleted_at IS NULL
        JOIN users su ON su.id = t.staff_user_id AND su.deleted_at IS NULL
        LEFT JOIN assignments a ON a.id = t.linked_assignment_id AND a.deleted_at IS NULL
        LEFT JOIN daily_summaries ds ON ds.id = t.linked_summary_id AND ds.deleted_at IS NULL
        LEFT JOIN thread_messages tm ON tm.thread_id = t.id AND tm.deleted_at IS NULL
        WHERE t.id = %(thread_id)s AND t.deleted_at IS NULL
        GROUP BY 
            t.id, s.name, s.room_id, r.name, gu.username, g.name, 
            su.username, a.title, ds.title
    """
    with conn.cursor() as cur:
        cur.execute(query, {"thread_id": thread_id, "user_id": user_id})
        row = cur.fetchone()
        return dict(row) if row else None


def create_thread(
    conn: psycopg.Connection,
    branch_id: UUID,
    student_id: UUID,
    guardian_user_id: UUID,
    staff_user_id: UUID,
    subject: str,
    linked_assignment_id: UUID | None = None,
    linked_summary_id: UUID | None = None,
    first_message_body: str | None = None,
    sender_user_id: UUID | None = None,
    sender_role: str | None = None,
    attachment_storage_key: str | None = None,
    attachment_type: str | None = None,
    attachment_name: str | None = None,
    attachment_size_bytes: int | None = None,
    client_message_id: str | None = None,
) -> dict[str, Any]:
    """Create a new thread and optionally post its first message atomically."""
    with conn.transaction():
        with conn.cursor() as cur:
            # M2: Check if thread was already created with this initial message idempotently
            if client_message_id and sender_user_id:
                cur.execute("""
                    SELECT tm.thread_id
                    FROM thread_messages tm
                    WHERE tm.sender_user_id = %s AND tm.client_message_id = %s AND tm.deleted_at IS NULL
                    LIMIT 1
                """, (sender_user_id, client_message_id))
                existing_msg = cur.fetchone()
                if existing_msg:
                    cur.execute("SELECT * FROM threads WHERE id = %s AND deleted_at IS NULL", (existing_msg["thread_id"],))
                    t_row = cur.fetchone()
                    if t_row:
                        d = dict(t_row)
                        d["_is_duplicate"] = True
                        return d

            cur.execute("""
                INSERT INTO threads (
                    branch_id, student_id, guardian_user_id, staff_user_id,
                    subject, linked_assignment_id, linked_summary_id,
                    last_message_preview
                ) VALUES (
                    %s, %s, %s, %s,
                    %s, %s, %s,
                    %s
                ) RETURNING *
            """, (
                branch_id, student_id, guardian_user_id, staff_user_id,
                subject, linked_assignment_id, linked_summary_id,
                (first_message_body[:100] if first_message_body else None),
            ))
            thread = dict(cur.fetchone())
            thread_id = thread["id"]

            if first_message_body and sender_user_id and sender_role:
                cur.execute("""
                    INSERT INTO thread_messages (
                        branch_id, thread_id, sender_user_id, sender_role,
                        body, attachment_storage_key, attachment_type,
                        attachment_name, attachment_size_bytes, client_message_id
                    ) VALUES (
                        %s, %s, %s, %s,
                        %s, %s, %s,
                        %s, %s, %s
                    ) RETURNING *
                """, (
                    branch_id, thread_id, sender_user_id, sender_role,
                    first_message_body, attachment_storage_key, attachment_type,
                    attachment_name, attachment_size_bytes, client_message_id,
                ))

            return thread


def list_thread_messages(
    conn: psycopg.Connection,
    thread_id: UUID,
    limit: int = 100,
) -> list[dict[str, Any]]:
    """List most recent messages in a thread chronologically with sender details."""
    safe_limit = max(1, min(limit, 200))
    query = """
        SELECT * FROM (
            SELECT 
                tm.id,
                tm.branch_id,
                tm.thread_id,
                tm.sender_user_id,
                u.username AS sender_name,
                tm.sender_role,
                tm.body,
                tm.attachment_storage_key,
                tm.attachment_type,
                tm.attachment_name,
                tm.attachment_size_bytes,
                tm.sent_at,
                tm.delivered_at,
                tm.read_at,
                tm.client_message_id,
                tm.created_at,
                tm.updated_at
            FROM thread_messages tm
            JOIN users u ON u.id = tm.sender_user_id AND u.deleted_at IS NULL
            WHERE tm.thread_id = %s AND tm.deleted_at IS NULL
            ORDER BY tm.sent_at DESC
            LIMIT %s
        ) sub
        ORDER BY sub.sent_at ASC
    """
    with conn.cursor() as cur:
        cur.execute(query, (thread_id, safe_limit))
        return [dict(row) for row in cur.fetchall()]


def create_thread_message(
    conn: psycopg.Connection,
    branch_id: UUID,
    thread_id: UUID,
    sender_user_id: UUID,
    sender_role: str,
    body: str,
    attachment_storage_key: str | None = None,
    attachment_type: str | None = None,
    attachment_name: str | None = None,
    attachment_size_bytes: int | None = None,
    client_message_id: str | None = None,
) -> dict[str, Any]:
    """Insert a new message into a thread and update thread activity timestamp."""
    with conn.transaction():
        with conn.cursor() as cur:
            # Check for existing message by client_message_id to guarantee idempotency
            if client_message_id:
                cur.execute("""
                    SELECT tm.*, u.username AS sender_name
                    FROM thread_messages tm
                    JOIN users u ON u.id = tm.sender_user_id
                    WHERE tm.thread_id = %s 
                    AND tm.sender_user_id = %s 
                    AND tm.client_message_id = %s
                    AND tm.deleted_at IS NULL
                """, (thread_id, sender_user_id, client_message_id))
                existing = cur.fetchone()
                if existing:
                    d = dict(existing)
                    d["_is_duplicate"] = True
                    return d

            cur.execute("""
                INSERT INTO thread_messages (
                    branch_id, thread_id, sender_user_id, sender_role,
                    body, attachment_storage_key, attachment_type,
                    attachment_name, attachment_size_bytes, client_message_id
                ) VALUES (
                    %s, %s, %s, %s,
                    %s, %s, %s,
                    %s, %s, %s
                ) RETURNING *
            """, (
                branch_id, thread_id, sender_user_id, sender_role,
                body, attachment_storage_key, attachment_type,
                attachment_name, attachment_size_bytes, client_message_id,
            ))
            msg = dict(cur.fetchone())
            msg["_is_duplicate"] = False

            # Update thread last message metadata
            cur.execute("""
                UPDATE threads
                SET last_message_at = now(),
                    last_message_preview = %s,
                    updated_at = now()
                WHERE id = %s
            """, (body[:100], thread_id))

            # Fetch sender name for return
            cur.execute("SELECT username FROM users WHERE id = %s", (sender_user_id,))
            user_row = cur.fetchone()
            msg["sender_name"] = user_row["username"] if user_row else "Unknown"

            return msg


def mark_messages_delivered(
    conn: psycopg.Connection,
    thread_id: UUID,
    recipient_user_id: UUID,
) -> int:
    """Mark all unconfirmed messages in thread as delivered for this recipient."""
    with conn.transaction():
        with conn.cursor() as cur:
            # Check thread participation
            cur.execute("SELECT guardian_user_id, staff_user_id FROM threads WHERE id = %s AND deleted_at IS NULL", (thread_id,))
            tr = cur.fetchone()
            if not tr or recipient_user_id not in (tr["guardian_user_id"], tr["staff_user_id"]):
                return 0

            cur.execute("""
                UPDATE thread_messages
                SET delivered_at = now(),
                    updated_at = now()
                WHERE thread_id = %s
                AND sender_user_id != %s
                AND delivered_at IS NULL
                AND deleted_at IS NULL
            """, (thread_id, recipient_user_id))
            return cur.rowcount


def mark_messages_read(
    conn: psycopg.Connection,
    thread_id: UUID,
    recipient_user_id: UUID,
) -> int:
    """Mark all unread messages in thread as read (and delivered) for this recipient."""
    with conn.transaction():
        with conn.cursor() as cur:
            # Check thread participation
            cur.execute("SELECT guardian_user_id, staff_user_id FROM threads WHERE id = %s AND deleted_at IS NULL", (thread_id,))
            tr = cur.fetchone()
            if not tr or recipient_user_id not in (tr["guardian_user_id"], tr["staff_user_id"]):
                return 0

            cur.execute("""
                UPDATE thread_messages
                SET read_at = now(),
                    delivered_at = COALESCE(delivered_at, now()),
                    updated_at = now()
                WHERE thread_id = %s
                AND sender_user_id != %s
                AND read_at IS NULL
                AND deleted_at IS NULL
            """, (thread_id, recipient_user_id))
            return cur.rowcount
