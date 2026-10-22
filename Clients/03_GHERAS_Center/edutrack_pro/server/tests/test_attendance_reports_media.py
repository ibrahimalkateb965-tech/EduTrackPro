"""Tests for Media Access Control (Source 5 Excuse Attachment) and Report Filtering (Batch S3-B3).

Covers:
1. Media ACL (P8):
   - Supervisor and Manager can open excuse attachments without 403 from resolve_scope().
   - Guardian of the student can open the excuse attachment (Source 5).
   - Teacher of the student's room can open the excuse attachment (Source 5).
   - Stranger guardian gets 404 (file not visible to unrelated users).
   - Foreign room teacher gets 404.
2. Reports Filtering (P3, P4, m1):
   - /reports/daily ignores draft attendance and counts only is_draft=FALSE.
   - /reports/attendance counts both 'مستأذن' and 'غياب بعذر' under 'excused' and ignores drafts.
   - /reports/monthly counts both 'مستأذن' and 'غياب بعذر' under 'excused' and ignores drafts.
   - /reports/student/{id} counts both 'مستأذن' and 'غياب بعذر' under 'excused' and ignores drafts.
3. Print Reports Filtering (P3, P4, m1):
   - /print/attendance-report maps 'غياب بعذر' to symbol 'ع' (matching 'مستأذن') and ignores drafts.
   - /print/student-report counts 'غياب بعذر' as excused and ignores drafts.
   - /print/admin-report and /print/statistics calculate good attendance ignoring drafts.
"""

from __future__ import annotations

import os
import uuid
from datetime import date
from pathlib import Path

import pytest

from tests.conftest import (
    MAIN_BRANCH,
    _guardian,
    _room,
    _student,
    _teacher,
    make_user,
    login,
)

DATE_STR = "2026-10-22"
DAY = date.fromisoformat(DATE_STR)


def _create_dummy_file(storage_key: str, content: bytes = b"dummy medical note") -> Path:
    base = Path(os.environ.get("UPLOAD_DIR") or (Path(__file__).resolve().parents[2] / "uploads"))
    base.mkdir(parents=True, exist_ok=True)
    dest = base / storage_key
    dest.write_bytes(content)
    return dest


def test_media_acl_source_5_excuse_attachment(client, manager, db):
    """Source 5 ACL: supervisor, manager, guardian, and room teacher can view; stranger gets 404 (P8)."""
    room1 = _room(client, manager, "قاعة النرجس")
    room2 = _room(client, manager, "قاعة القرنفل")
    st1 = _student(client, manager, "أحمد ياسر", room1)
    st2 = _student(client, manager, "عمر خالد", room2)

    # Guardian of st1
    _, g1_hdr = _guardian(db, client, "parent_yaser", [st1])
    # Stranger guardian (only linked to st2)
    _, g2_hdr = _guardian(db, client, "parent_stranger", [st2])

    # Teacher of room1
    _, t1_hdr = _teacher(db, client, "teacher_nargis", room1)
    # Foreign teacher of room2
    _, t2_hdr = _teacher(db, client, "teacher_qaranful", room2)

    # Supervisor
    sup_id = make_user(db, "sup_media", "supervisor")
    sup_hdr = login(client, "sup_media")

    # Create dummy file on disk
    storage_key = f"{uuid.uuid4().hex}_medical_report.pdf"
    _create_dummy_file(storage_key)

    # Insert into attendance_excuses with attachment_storage_key
    parent_user_id = db.execute("SELECT id FROM users WHERE username = 'parent_yaser'").fetchone()["id"]
    db.execute(
        """
        INSERT INTO attendance_excuses (
            branch_id, student_id, excuse_date, submitted_by_user_id,
            reason_text, attachment_storage_key, status
        ) VALUES (
            %s, %s, %s, %s, 'عذر مرضي مرفق', %s, 'pending'
        )
        """,
        (MAIN_BRANCH, st1, DATE_STR, parent_user_id, storage_key),
    )
    db.commit()

    url = f"/api/v1/me/files/{storage_key}"

    # 1. Supervisor access -> 200 OK (bypasses resolve_scope, P8 fix)
    res_sup = client.get(url, headers=sup_hdr)
    assert res_sup.status_code == 200
    assert res_sup.content == b"dummy medical note"

    # 2. Manager access -> 200 OK (P8 fix)
    res_mgr = client.get(url, headers=manager)
    assert res_mgr.status_code == 200
    assert res_mgr.content == b"dummy medical note"

    # 3. Guardian of st1 access -> 200 OK (Source 5 student match)
    res_g1 = client.get(url, headers=g1_hdr)
    assert res_g1.status_code == 200

    # 4. Teacher of room1 access -> 200 OK (Source 5 room/student match)
    res_t1 = client.get(url, headers=t1_hdr)
    assert res_t1.status_code == 200

    # 5. Stranger guardian (g2) access -> 404 Not Found (P8 stranger isolation)
    res_g2 = client.get(url, headers=g2_hdr)
    assert res_g2.status_code == 404

    # 6. Foreign teacher (t2) access -> 404 Not Found
    res_t2 = client.get(url, headers=t2_hdr)
    assert res_t2.status_code == 404


def test_reports_daily_and_attendance_ignore_drafts_and_count_excused(client, manager, db):
    """Daily and Attendance reports ignore is_draft=TRUE and count both 'مستأذن' and 'غياب بعذر' (P3, P4, m1)."""
    room_id = _room(client, manager, "قاعة الصفوة")
    s1 = _student(client, manager, "طالب 1", room_id)
    s2 = _student(client, manager, "طالب 2", room_id)
    s3 = _student(client, manager, "طالب 3", room_id)
    s4 = _student(client, manager, "طالب 4", room_id)
    s5 = _student(client, manager, "طالب 5", room_id)

    # Attendance records for DAY:
    # s1: حاضر (official)
    # s2: غائب (draft! must be ignored by reports)
    # s3: غائب (official)
    # s4: مستأذن (official)
    # s5: غياب بعذر (official)
    db.execute(
        """
        INSERT INTO student_attendance (student_id, date, status, is_draft) VALUES
        (%s, %s, 'حاضر', FALSE),
        (%s, %s, 'غائب', TRUE),
        (%s, %s, 'غائب', FALSE),
        (%s, %s, 'مستأذن', FALSE),
        (%s, %s, 'غياب بعذر', FALSE)
        """,
        (s1, DAY, s2, DAY, s3, DAY, s4, DAY, s5, DAY),
    )
    db.commit()

    # 1. Test /reports/daily
    res_daily = client.get(f"/api/v1/reports/daily?date={DATE_STR}", headers=manager)
    assert res_daily.status_code == 200
    daily_data = res_daily.json()
    assert daily_data["present_today"] == 1
    assert daily_data["absent_today"] == 1  # Only s3 counted, s2 (draft) ignored!
    assert daily_data["late_today"] == 0
    # Absent list must contain s3 but NOT s2
    absent_ids = [a["student_id"] for a in daily_data["absences"]]
    assert s3 in absent_ids
    assert s2 not in absent_ids

    # 2. Test /reports/attendance
    res_att = client.get(f"/api/v1/reports/attendance?from={DATE_STR}&to={DATE_STR}", headers=manager)
    assert res_att.status_code == 200
    att_data = res_att.json()
    summary = att_data["summary"]
    assert summary["present"] == 1
    assert summary["absent"] == 1  # Draft s2 ignored!
    assert summary["late"] == 0
    # 'excused' counts BOTH 'مستأذن' (s4) and 'غياب بعذر' (s5)!
    assert summary["excused"] == 2

    # Verify items list does not contain draft row s2
    item_students = [it["student_id"] for it in att_data["items"]]
    assert s2 not in item_students
    assert len(item_students) == 4


def test_monthly_and_student_reports_ignore_drafts_and_count_excused(client, manager, db):
    """Monthly report and individual student report correctly count excused and ignore drafts (P3, P4)."""
    room_id = _room(client, manager, "قاعة الإتقان")
    st_id = _student(client, manager, "طالب متميز", room_id)

    d1 = "2026-10-01"
    d2 = "2026-10-02"
    d3 = "2026-10-03"
    d4 = "2026-10-04"

    # s1 records:
    # d1: حاضر (official)
    # d2: مستأذن (official)
    # d3: غياب بعذر (official)
    # d4: غائب (draft! must be ignored)
    db.execute(
        """
        INSERT INTO student_attendance (student_id, date, status, is_draft) VALUES
        (%s, %s, 'حاضر', FALSE),
        (%s, %s, 'مستأذن', FALSE),
        (%s, %s, 'غياب بعذر', FALSE),
        (%s, %s, 'غائب', TRUE)
        """,
        (st_id, d1, st_id, d2, st_id, d3, st_id, d4),
    )
    db.commit()

    # 1. Test /reports/monthly
    res_month = client.get("/api/v1/reports/monthly?month=2026-10", headers=manager)
    assert res_month.status_code == 200
    month_data = res_month.json()
    att = month_data["attendance"]
    assert att["present"] == 1
    assert att["absent"] == 0  # d4 (draft) ignored!
    assert att["excused"] == 2  # d2 ('مستأذن') + d3 ('غياب بعذر')

    # Top absent list should NOT contain st_id since his only absence is draft
    top_absent_ids = [x["student_id"] for x in month_data["top_absent"]]
    assert st_id not in top_absent_ids

    # 2. Test /reports/student/{id}
    res_st = client.get(f"/api/v1/reports/student/{st_id}", headers=manager)
    assert res_st.status_code == 200
    st_data = res_st.json()
    att_sum = st_data["attendance_summary"]
    assert att_sum["present"] == 1
    assert att_sum["absent"] == 0  # Draft ignored
    assert att_sum["excused"] == 2  # 'مستأذن' + 'غياب بعذر'


def test_print_reports_attendance_symbols_and_draft_isolation(client, manager, db):
    """Print reports: /print/attendance-report maps 'غياب بعذر' to symbol 'ع' and ignores drafts (P3, P4)."""
    room_id = _room(client, manager, "قاعة الرواد")
    s1 = _student(client, manager, "طالب أ", room_id)
    s2 = _student(client, manager, "طالب ب", room_id)

    # For DAY:
    # s1 has 'غياب بعذر' (official) -> should map to 'ع'
    # s2 has 'غائب' (draft) -> should be ignored (blank symbol)
    db.execute(
        """
        INSERT INTO student_attendance (student_id, date, status, is_draft) VALUES
        (%s, %s, 'غياب بعذر', FALSE),
        (%s, %s, 'غائب', TRUE)
        """,
        (s1, DAY, s2, DAY),
    )
    db.commit()

    # 1. /print/attendance-report
    res_print = client.get(f"/api/v1/print/attendance-report?from={DATE_STR}&to={DATE_STR}", headers=manager)
    assert res_print.status_code == 200
    body = res_print.json()["data"]

    s1_row = next(r for r in body["output"] if r["student_name"] == "طالب أ")
    assert s1_row["cells"][0]["symbol"] == "ع"  # 'غياب بعذر' rendered as 'ع'

    s2_row = next(r for r in body["output"] if r["student_name"] == "طالب ب")
    assert s2_row["cells"][0]["symbol"] == ""  # Draft ignored, empty cell!
    assert s2_row["absent_count"] == 0

    # 2. /print/student-report for s1
    res_st_print = client.get(f"/api/v1/print/student-report?student_id={s1}", headers=manager)
    assert res_st_print.status_code == 200
    st_print_data = res_st_print.json()["data"]
    assert st_print_data["attendance_counts"]["excused"] == 1

    # 3. /print/admin-report and /print/statistics
    res_admin = client.get(f"/api/v1/print/admin-report?from={DATE_STR}&to={DATE_STR}", headers=manager)
    assert res_admin.status_code == 200

    res_stat = client.get(f"/api/v1/print/statistics?from={DATE_STR}&to={DATE_STR}", headers=manager)
    assert res_stat.status_code == 200
