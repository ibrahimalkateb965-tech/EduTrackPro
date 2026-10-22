"""Tests for Attendance Draft, Approval, and Contract Integrity (Batch S3-B2).

Covers:
1. Rejection of client-supplied 'غياب بعذر' on both direct and draft routes (H2, m2 -> 422).
2. Draft saving saves rows with is_draft=TRUE without emitting notifications (P1, G3).
3. Student room membership verification on draft items (G3c -> 422 for foreign/inactive).
4. Approved row preservation: saving draft over an approved row stays approved (G3a).
5. Automatic conversion of absent ('غائب') to excused absence ('غياب بعذر') when an approved
   excuse exists in attendance_excuses, linking excuse_id and suppressing absence notification (G3b, P5).
6. Automatic clearing of excuse_id when status changes from 'غياب بعذر' to another status (H3).
7. Approve route role and room scope checks (H1, G3d -> 403 for foreign teacher or unpermitted supervisor).
8. Approve route completeness check: 422 if any active room student is missing attendance (P1, P2, m5).
9. Approve route atomic commit: flips is_draft=FALSE, emits notifications only for 'غائب' (P1, P2).
10. Approve route excuse conversion: converts 'غائب' to 'غياب بعذر' if excuse was approved before approval.
"""

from __future__ import annotations

import uuid
from datetime import date

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


def _seed_excuse(
    db,
    student_id: str,
    excuse_date: str = DATE_STR,
    status: str = "approved",
    submitted_by_user_id: str | None = None,
) -> str:
    if not submitted_by_user_id:
        submitted_by_user_id = str(make_user(db, f"parent_{uuid.uuid4().hex[:6]}", "guardian"))
    row = db.execute(
        """
        INSERT INTO attendance_excuses (
            branch_id, student_id, excuse_date, submitted_by_user_id,
            reason_text, status
        ) VALUES (
            %s, %s, %s, %s, 'عذر مرضي معتمد', %s
        ) RETURNING id
        """,
        (MAIN_BRANCH, student_id, excuse_date, submitted_by_user_id, status),
    ).fetchone()
    db.commit()
    return str(row["id"])


def test_reject_client_excused_absence_direct_and_draft(client, manager, db):
    """Client cannot supply 'غياب بعذر' directly; it is server-managed only (H2, m2)."""
    room_id = _room(client, manager, "قاعة الزهور")
    st_id = _student(client, manager, "سارة أحمد", room_id)

    # 1. Direct save: POST /api/v1/attendance/students with 'غياب بعذر' -> 422
    res_direct = client.post(
        "/api/v1/attendance/students",
        json=[{"student_id": st_id, "date": DATE_STR, "status": "غياب بعذر"}],
        headers=manager,
    )
    assert res_direct.status_code == 422
    assert res_direct.json()["error"]["code"] == "validation_error"

    # 2. Draft save: PUT /api/v1/attendance/students/draft with 'غياب بعذر' -> 422
    res_draft = client.put(
        "/api/v1/attendance/students/draft",
        json={
            "room_id": room_id,
            "date": DATE_STR,
            "items": [{"student_id": st_id, "status": "غياب بعذر"}],
        },
        headers=manager,
    )
    assert res_draft.status_code == 422
    assert res_draft.json()["error"]["code"] == "validation_error"

    # 3. Invalid status string -> 422
    res_invalid = client.put(
        "/api/v1/attendance/students/draft",
        json={
            "room_id": room_id,
            "date": DATE_STR,
            "items": [{"student_id": st_id, "status": "طائر"}],
        },
        headers=manager,
    )
    assert res_invalid.status_code == 422


def test_draft_attendance_saves_without_notifications(client, manager, db):
    """Draft attendance is saved with is_draft=TRUE and emits ZERO notifications (P1, G3)."""
    room_id = _room(client, manager, "قاعة النور")
    st_id = _student(client, manager, "فاطمة علي", room_id)
    _guardian(db, client, "parent_fatima", [st_id])

    _, teacher_hdr = _teacher(db, client, "teacher_draft_1", room_id)

    res = client.put(
        "/api/v1/attendance/students/draft",
        json={
            "room_id": room_id,
            "date": DATE_STR,
            "items": [{"student_id": st_id, "status": "غائب", "note": "مسودة رصد"}],
        },
        headers=teacher_hdr,
    )
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["saved"] == 1
    assert data["is_draft"] is True
    assert data["items"][0]["is_draft"] is True
    assert data["items"][0]["status"] == "غائب"

    # Verify DB row
    row = db.execute(
        "SELECT is_draft, status, note FROM student_attendance WHERE student_id = %s AND date = %s",
        (st_id, DATE_STR),
    ).fetchone()
    assert row["is_draft"] is True
    assert row["status"] == "غائب"
    assert row["note"] == "مسودة رصد"

    # Verify ZERO notifications exist
    notif_count = db.execute("SELECT count(*) FROM notifications WHERE kind = 'attendance'").fetchone()["count"]
    assert notif_count == 0


def test_draft_attendance_foreign_or_inactive_student_rejected(client, manager, db):
    """Students in draft items must be active and belong to the specified room (G3c)."""
    room1 = _room(client, manager, "قاعة 1")
    room2 = _room(client, manager, "قاعة 2")
    st1 = _student(client, manager, "طالب 1", room1)
    st2 = _student(client, manager, "طالب 2", room2)

    _, teacher1 = _teacher(db, client, "teacher_g3c_1", room1)

    # 1. Foreign student from room 2 in room 1 draft -> 422
    res = client.put(
        "/api/v1/attendance/students/draft",
        json={
            "room_id": room1,
            "date": DATE_STR,
            "items": [
                {"student_id": st1, "status": "حاضر"},
                {"student_id": st2, "status": "حاضر"},
            ],
        },
        headers=teacher1,
    )
    assert res.status_code == 422
    assert res.json()["error"]["code"] == "validation_error"

    # 2. Inactive student in draft -> 422
    db.execute("UPDATE students SET status = 'suspended' WHERE id = %s", (st1,))
    db.commit()
    res_inactive = client.put(
        "/api/v1/attendance/students/draft",
        json={
            "room_id": room1,
            "date": DATE_STR,
            "items": [{"student_id": st1, "status": "حاضر"}],
        },
        headers=teacher1,
    )
    assert res_inactive.status_code == 422

    # 3. Soft-deleted student in draft -> 422
    db.execute("UPDATE students SET status = 'active', deleted_at = now() WHERE id = %s", (st1,))
    db.commit()
    res_deleted = client.put(
        "/api/v1/attendance/students/draft",
        json={
            "room_id": room1,
            "date": DATE_STR,
            "items": [{"student_id": st1, "status": "حاضر"}],
        },
        headers=teacher1,
    )
    assert res_deleted.status_code == 422

    # 4. Unknown random UUID -> 422
    res_unknown = client.put(
        "/api/v1/attendance/students/draft",
        json={
            "room_id": room1,
            "date": DATE_STR,
            "items": [{"student_id": str(uuid.uuid4()), "status": "حاضر"}],
        },
        headers=teacher1,
    )
    assert res_unknown.status_code == 422


def test_draft_over_approved_row_remains_approved(client, manager, db):
    """An approved attendance row (is_draft=FALSE) must never revert to draft (G3a)."""
    room_id = _room(client, manager, "قاعة الأمل")
    st_id = _student(client, manager, "مريم حسن", room_id)
    _, teacher_hdr = _teacher(db, client, "teacher_g3a", room_id)

    # 1. Direct save creates an approved record (is_draft=FALSE)
    res_direct = client.post(
        "/api/v1/attendance/students",
        json=[{"student_id": st_id, "date": DATE_STR, "status": "حاضر"}],
        headers=teacher_hdr,
    )
    assert res_direct.status_code == 200
    row_initial = db.execute(
        "SELECT is_draft, status FROM student_attendance WHERE student_id = %s AND date = %s",
        (st_id, DATE_STR),
    ).fetchone()
    assert row_initial["is_draft"] is False
    assert row_initial["status"] == "حاضر"

    # 2. Save a draft over the approved row
    res_draft = client.put(
        "/api/v1/attendance/students/draft",
        json={
            "room_id": room_id,
            "date": DATE_STR,
            "items": [{"student_id": st_id, "status": "متأخر", "note": "تعديل مسودة"}],
        },
        headers=teacher_hdr,
    )
    assert res_draft.status_code == 200

    # 3. Verify in DB: is_draft remains FALSE, but status and note updated
    row_updated = db.execute(
        "SELECT is_draft, status, note FROM student_attendance WHERE student_id = %s AND date = %s",
        (st_id, DATE_STR),
    ).fetchone()
    assert row_updated["is_draft"] is False  # Must NOT revert to draft!
    assert row_updated["status"] == "متأخر"
    assert row_updated["note"] == "تعديل مسودة"


def test_approved_excuse_automatic_conversion_direct_and_draft(client, manager, db):
    """If an approved excuse exists, absent status is converted to 'غياب بعذر' and notif skipped (G3b, P5)."""
    room_id = _room(client, manager, "قاعة الياسمين")
    st1 = _student(client, manager, "خالد وليد", room_id)
    st2 = _student(client, manager, "زياد طارق", room_id)
    _guardian(db, client, "parent_khaled", [st1])
    _guardian(db, client, "parent_ziyad", [st2])

    # Seed approved excuse for st1
    excuse_id_1 = _seed_excuse(db, st1, DATE_STR, status="approved")

    # Seed pending (unapproved) excuse for st2
    _seed_excuse(db, st2, DATE_STR, status="pending")

    _, teacher_hdr = _teacher(db, client, "teacher_g3b", room_id)

    # 1. Draft save: st1 has approved excuse -> converted to 'غياب بعذر' with excuse_id linked
    res_draft = client.put(
        "/api/v1/attendance/students/draft",
        json={
            "room_id": room_id,
            "date": DATE_STR,
            "items": [
                {"student_id": st1, "status": "غائب"},
                {"student_id": st2, "status": "غائب"},
            ],
        },
        headers=teacher_hdr,
    )
    assert res_draft.status_code == 200

    row_st1 = db.execute(
        "SELECT status, excuse_id, is_draft FROM student_attendance WHERE student_id = %s AND date = %s",
        (st1, DATE_STR),
    ).fetchone()
    assert row_st1["status"] == "غياب بعذر"
    assert str(row_st1["excuse_id"]) == excuse_id_1
    assert row_st1["is_draft"] is True

    row_st2 = db.execute(
        "SELECT status, excuse_id, is_draft FROM student_attendance WHERE student_id = %s AND date = %s",
        (st2, DATE_STR),
    ).fetchone()
    assert row_st2["status"] == "غائب"
    assert row_st2["excuse_id"] is None

    # 2. Direct save test: st1 via direct save also converts and suppresses notifications
    next_day = "2026-10-23"
    excuse_id_next = _seed_excuse(db, st1, next_day, status="approved")
    res_direct = client.post(
        "/api/v1/attendance/students",
        json=[{"student_id": st1, "date": next_day, "status": "غائب"}],
        headers=manager,
    )
    assert res_direct.status_code == 200
    row_direct = db.execute(
        "SELECT status, excuse_id, is_draft FROM student_attendance WHERE student_id = %s AND date = %s",
        (st1, next_day),
    ).fetchone()
    assert row_direct["status"] == "غياب بعذر"
    assert str(row_direct["excuse_id"]) == excuse_id_next
    assert row_direct["is_draft"] is False

    # Verify no absence notification was sent for st1
    notifs_st1 = db.execute(
        "SELECT count(*) FROM notifications WHERE target_id = %s",
        (st1,),
    ).fetchone()["count"]
    assert notifs_st1 == 0


def test_clearing_excuse_id_when_status_changes(client, manager, db):
    """When a 'غياب بعذر' row is re-saved as another status, excuse_id is cleared (H3)."""
    room_id = _room(client, manager, "قاعة الرياحين")
    st_id = _student(client, manager, "سعد فهد", room_id)
    excuse_id = _seed_excuse(db, st_id, DATE_STR, status="approved")

    # 1. Save as 'غائب' -> converted to 'غياب بعذر' with excuse_id
    res1 = client.post(
        "/api/v1/attendance/students",
        json=[{"student_id": st_id, "date": DATE_STR, "status": "غائب"}],
        headers=manager,
    )
    assert res1.status_code == 200
    row1 = db.execute(
        "SELECT status, excuse_id FROM student_attendance WHERE student_id = %s AND date = %s",
        (st_id, DATE_STR),
    ).fetchone()
    assert row1["status"] == "غياب بعذر"
    assert str(row1["excuse_id"]) == excuse_id

    # 2. Teacher re-saves status as 'حاضر'
    res2 = client.post(
        "/api/v1/attendance/students",
        json=[{"student_id": st_id, "date": DATE_STR, "status": "حاضر"}],
        headers=manager,
    )
    assert res2.status_code == 200
    row2 = db.execute(
        "SELECT status, excuse_id FROM student_attendance WHERE student_id = %s AND date = %s",
        (st_id, DATE_STR),
    ).fetchone()
    assert row2["status"] == "حاضر"
    assert row2["excuse_id"] is None  # Must be cleared!


def test_approve_room_attendance_role_and_room_scope(client, manager, db):
    """Approve route checks permissions: room scope for teacher, permission for supervisor (H1, G3d)."""
    room1 = _room(client, manager, "قاعة 101")
    room2 = _room(client, manager, "قاعة 102")
    st1 = _student(client, manager, "طالب أ", room1)
    st2 = _student(client, manager, "طالب ب", room2)

    _, teacher1 = _teacher(db, client, "teacher_scope_1", room1)
    _, teacher2 = _teacher(db, client, "teacher_scope_2", room2)

    # Supervisor without attendance permission
    sup_no_att_id = make_user(db, "sup_no_att", "supervisor")
    db.execute(
        "UPDATE users SET permissions = '{\"attendance\": false}'::jsonb WHERE id = %s",
        (sup_no_att_id,),
    )
    db.commit()
    sup_no_att_hdr = login(client, "sup_no_att")

    # Supervisor with attendance permission
    sup_att_id = make_user(db, "sup_att", "supervisor")
    db.execute(
        "UPDATE users SET permissions = '{\"attendance\": true}'::jsonb WHERE id = %s",
        (sup_att_id,),
    )
    db.commit()
    sup_att_hdr = login(client, "sup_att")

    # Record draft attendance for room1
    client.put(
        "/api/v1/attendance/students/draft",
        json={"room_id": room1, "date": DATE_STR, "items": [{"student_id": st1, "status": "حاضر"}]},
        headers=teacher1,
    )

    # 1. Foreign teacher (teacher 2) approving room 1 -> 403 Forbidden (H1)
    res_foreign_teacher = client.post(
        f"/api/v1/attendance/rooms/{room1}/days/{DATE_STR}/approve",
        headers=teacher2,
    )
    assert res_foreign_teacher.status_code == 403
    assert res_foreign_teacher.json()["error"]["code"] == "forbidden"

    # 2. Supervisor without attendance permission -> 403 Forbidden (H1)
    res_sup_denied = client.post(
        f"/api/v1/attendance/rooms/{room1}/days/{DATE_STR}/approve",
        headers=sup_no_att_hdr,
    )
    assert res_sup_denied.status_code == 403

    # 3. Supervisor with attendance permission -> 200 OK
    res_sup_ok = client.post(
        f"/api/v1/attendance/rooms/{room1}/days/{DATE_STR}/approve",
        headers=sup_att_hdr,
    )
    assert res_sup_ok.status_code == 200
    assert res_sup_ok.json()["approved"] is True

    # 4. Teacher 1 approving room 1 -> 200 OK
    res_teacher_ok = client.post(
        f"/api/v1/attendance/rooms/{room1}/days/{DATE_STR}/approve",
        headers=teacher1,
    )
    assert res_teacher_ok.status_code == 200

    # 5. Manager approving room 1 -> 200 OK
    res_mgr_ok = client.post(
        f"/api/v1/attendance/rooms/{room1}/days/{DATE_STR}/approve",
        headers=manager,
    )
    assert res_mgr_ok.status_code == 200


def test_approve_incomplete_room_rejected_422(client, manager, db):
    """Approve fails with 422 if any active student in the room is missing attendance (P1, P2, m5)."""
    room_id = _room(client, manager, "قاعة التحدي")
    st1 = _student(client, manager, "طالب كامل 1", room_id)
    st2 = _student(client, manager, "طالب كامل 2", room_id)
    st3 = _student(client, manager, "طالب كامل 3", room_id)

    _, teacher_hdr = _teacher(db, client, "teacher_incomplete", room_id)

    # Record attendance for only st1 and st2 (st3 is missing)
    client.put(
        "/api/v1/attendance/students/draft",
        json={
            "room_id": room_id,
            "date": DATE_STR,
            "items": [
                {"student_id": st1, "status": "حاضر"},
                {"student_id": st2, "status": "متأخر"},
            ],
        },
        headers=teacher_hdr,
    )

    # Attempt to approve
    res = client.post(
        f"/api/v1/attendance/rooms/{room_id}/days/{DATE_STR}/approve",
        headers=teacher_hdr,
    )
    assert res.status_code == 422
    assert res.json()["error"]["code"] == "validation_error"
    assert "لا يمكن اعتماد الحضور قبل اكتمال رصد كافة طلاب الفصل" in res.json()["error"]["message"]

    # Verify rows remain is_draft = TRUE
    rows = db.execute(
        "SELECT is_draft FROM student_attendance WHERE student_id = ANY(%s) AND date = %s",
        ([st1, st2], DATE_STR),
    ).fetchall()
    assert all(r["is_draft"] is True for r in rows)


def test_approve_complete_room_success_and_notifications(client, manager, db):
    """Complete room approval flips is_draft=FALSE and notifies ONLY absent students (P1, P2)."""
    room_id = _room(client, manager, "قاعة التفوق")
    st_present = _student(client, manager, "طالب حاضر", room_id)
    st_absent = _student(client, manager, "طالب غائب", room_id)
    st_excused = _student(client, manager, "طالب مستأذن", room_id)

    _guardian(db, client, "parent_present", [st_present])
    _guardian(db, client, "parent_absent", [st_absent])
    _guardian(db, client, "parent_excused", [st_excused])

    _, teacher_hdr = _teacher(db, client, "teacher_complete", room_id)

    # 1. Save draft with all 3 students
    client.put(
        "/api/v1/attendance/students/draft",
        json={
            "room_id": room_id,
            "date": DATE_STR,
            "items": [
                {"student_id": st_present, "status": "حاضر"},
                {"student_id": st_absent, "status": "غائب"},
                {"student_id": st_excused, "status": "مستأذن"},
            ],
        },
        headers=teacher_hdr,
    )

    # Prior to approval: 0 notifications
    assert db.execute("SELECT count(*) FROM notifications WHERE kind = 'attendance'").fetchone()["count"] == 0

    # 2. Approve room attendance
    res = client.post(
        f"/api/v1/attendance/rooms/{room_id}/days/{DATE_STR}/approve",
        headers=teacher_hdr,
    )
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["approved"] is True
    assert body["total_students"] == 3
    assert body["notifications_sent"] == 1

    # Verify is_draft is now FALSE for all 3
    rows = db.execute(
        "SELECT student_id, is_draft, status FROM student_attendance WHERE student_id = ANY(%s) AND date = %s",
        ([st_present, st_absent, st_excused], DATE_STR),
    ).fetchall()
    assert len(rows) == 3
    assert all(r["is_draft"] is False for r in rows)

    # Verify notification sent ONLY for st_absent
    notifs = db.execute("SELECT target_id, title FROM notifications WHERE kind = 'attendance'").fetchall()
    assert len(notifs) == 1
    assert str(notifs[0]["target_id"]) == st_absent
    assert notifs[0]["title"] == "تسجيل غياب"

    # 3. Idempotent replay does not create duplicate notifications
    res_replay = client.post(
        f"/api/v1/attendance/rooms/{room_id}/days/{DATE_STR}/approve",
        headers=teacher_hdr,
    )
    assert res_replay.status_code == 200
    assert res_replay.json()["notifications_sent"] == 0
    assert db.execute("SELECT count(*) FROM notifications WHERE kind = 'attendance'").fetchone()["count"] == 1


def test_approve_converts_absent_to_excused_if_excuse_approved(client, manager, db):
    """If excuse was approved after draft was taken, approve converts 'غائب' to 'غياب بعذر'."""
    room_id = _room(client, manager, "قاعة الإيمان")
    st1 = _student(client, manager, "طالب أ1", room_id)
    st2 = _student(client, manager, "طالب أ2", room_id)
    _guardian(db, client, "parent_a1", [st1])
    _guardian(db, client, "parent_a2", [st2])

    _, teacher_hdr = _teacher(db, client, "teacher_late_excuse", room_id)

    # 1. Draft recorded: st1 is حاضر, st2 is غائب (no excuse at this point)
    client.put(
        "/api/v1/attendance/students/draft",
        json={
            "room_id": room_id,
            "date": DATE_STR,
            "items": [
                {"student_id": st1, "status": "حاضر"},
                {"student_id": st2, "status": "غائب"},
            ],
        },
        headers=teacher_hdr,
    )

    # 2. Later, supervisor approves an excuse for st2
    excuse_id = _seed_excuse(db, st2, DATE_STR, status="approved")

    # 3. Teacher calls approve
    res = client.post(
        f"/api/v1/attendance/rooms/{room_id}/days/{DATE_STR}/approve",
        headers=teacher_hdr,
    )
    assert res.status_code == 200
    assert res.json()["approved"] is True
    assert res.json()["notifications_sent"] == 0  # No notification since st2 is excused!

    # Verify st2 row in DB is converted to 'غياب بعذر' with excuse_id and is_draft=FALSE
    row_st2 = db.execute(
        "SELECT status, excuse_id, is_draft FROM student_attendance WHERE student_id = %s AND date = %s",
        (st2, DATE_STR),
    ).fetchone()
    assert row_st2["status"] == "غياب بعذر"
    assert str(row_st2["excuse_id"]) == excuse_id
    assert row_st2["is_draft"] is False

    # Zero notifications in table
    assert db.execute("SELECT count(*) FROM notifications WHERE kind = 'attendance'").fetchone()["count"] == 0
