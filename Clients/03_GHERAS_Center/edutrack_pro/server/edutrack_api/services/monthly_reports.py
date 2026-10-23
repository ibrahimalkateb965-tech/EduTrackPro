"""Domain service logic and notification orchestration for Monthly Student Reports (Milestone M5)."""

from __future__ import annotations

from typing import Any
from uuid import UUID

import psycopg

VALID_RATINGS = ("متميز", "متقن", "جيد", "يحتاج متابعة", "يحتاج دعم")

MONTH_NAMES_AR = {
    1: "يناير",
    2: "فبراير",
    3: "مارس",
    4: "أبريل",
    5: "مايو",
    6: "يونيو",
    7: "يوليو",
    8: "أغسطس",
    9: "سبتمبر",
    10: "أكتوبر",
    11: "نوفمبر",
    12: "ديسمبر",
}


def format_arabic_month(month_str: str) -> str:
    """Convert '2026-10' to 'أكتوبر 2026'."""
    try:
        year_str, month_part = month_str.split("-")
        m_num = int(month_part)
        ar_name = MONTH_NAMES_AR.get(m_num, month_part)
        return f"{ar_name} {year_str}"
    except Exception:
        return month_str


def validate_subject_ratings(ratings: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Validate subject ratings structure and sanitize inputs."""
    cleaned = []
    for item in ratings:
        subj = str(item.get("subject", "")).strip()
        rat = str(item.get("rating", "")).strip()
        notes = str(item.get("notes", "")).strip() if item.get("notes") else None
        if not subj:
            continue
        cleaned.append({
            "subject": subj,
            "rating": rat if rat else "متقن",
            "notes": notes,
        })
    return cleaned


def broadcast_monthly_report_notification(
    conn: psycopg.Connection,
    report_id: UUID,
    branch_id: UUID,
    student_id: UUID,
    student_name: str,
    month: str,
    sender_user_id: UUID,
) -> int:
    """Send notification to the student's guardian(s) upon report publication."""
    query = """
        SELECT DISTINCT u.id AS user_id
        FROM students s
        JOIN student_guardians sg ON sg.student_id = s.id
        JOIN users u ON u.guardian_id = sg.guardian_id AND u.deleted_at IS NULL
        WHERE s.id = %s AND s.deleted_at IS NULL
    """
    guardians = conn.execute(query, (student_id,)).fetchall()
    if not guardians:
        return 0

    month_ar = format_arabic_month(month)
    title = f"التقرير الشهري ({month_ar})"
    body = f"تم اعتماد ونشر التقرير الشهري للطالب {student_name} لشهر {month_ar}."
    action_url = f"gheras://monthly-reports/{report_id}"

    count = 0
    for g in guardians:
        conn.execute("""
            INSERT INTO notifications (
                branch_id,
                user_id,
                kind,
                title,
                body,
                target_type,
                target_id,
                priority,
                sender_user_id,
                action_url,
                sent_at
            ) VALUES (
                %s, %s, 'announcement', %s, %s, 'monthly_report', %s,
                'normal', %s, %s, now()
            )
        """, (
            branch_id,
            g["user_id"],
            title,
            body,
            report_id,
            sender_user_id,
            action_url,
        ))
        count += 1

    return count
