"""Tests for Attendance Excuses API (Batch S3-B4a).

Covers:
1. POST /api/v1/attendance/excuses:
   - Reason validation (>= 5 chars -> 422 if short).
   - Guardian scope enforcement (403 for stranger child).
   - Duplicate active day rejection (409 conflict).
   - Replay idempotency via client_request_id (200 with same row, P10).
   - Staged upload attachment ownership (404 if not owned).
2. GET /api/v1/attendance/excuses:
   - Supervisor permissions check (403 without permissions.attendance).
   - Manager and permitted supervisor access (200).
   - Filtering by status, room_id, date range.
3. POST /api/v1/attendance/excuses/{id}/review:
   - Supervisor permissions check (403 without permissions.attendance).
   - Mandatory notes for reject and needs_info (422).
   - CAS guard: only 'pending' can be reviewed (409 on second review, P6).
   - Attendance status conversion on approve: ONLY 'غائب' -> 'غياب بعذر' (P5, m7).
   - Attendance status 'حاضر' and 'مستأذن' remain untouched upon excuse approval (P5, m7).
   - Immediate notification emission to submitting guardian with target_type='excuse'.
4. PATCH /api/v1/attendance/excuses/{id}:
   - Only excuses in 'needs_info' status can be patched (409 otherwise, P7).
   - Re-submitting moves status back to 'pending'.
   - Preserves supervisor_notes (P7).
   - Foreign guardian cannot patch (403).
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


def _seed_staged_upload(db, user_id: str, storage_key: str | None = None) -> str:
    if not storage_key:
        storage_key = f"{uuid.uuid4().hex}_medical.pdf"
    db.execute(
        """
        INSERT INTO staged_uploads (storage_key, uploaded_by_user_id, media_type, file_name, bytes)
        VALUES (%s, %s, 'document', 'medical.pdf', 1024)
        ON CONFLICT (storage_key) DO NOTHING
        """,
        (storage_key, user_id),
    )
    db.commit()
    return storage_key


def test_create_excuse_validation_and_scope(client, manager, db):
    """Excuse submission validates reason length, guardian scope, and staged attachment ownership."""
    room = _room(client, manager, "قاعة الصفاء")
    st_mine = _student(client, manager, "طالب أ", room)
    st_other = _student(client, manager, "طالب ب", room)

    g_mine_uid, g_mine_hdr = _guardian(db, client, "parent_mine", [st_mine])
    g_other_uid, g_other_hdr = _guardian(db, client, "parent_other", [st_other])

    # 1. Reason text too short (< 5 chars) -> 422
    res_short = client.post(
        "/api/v1/attendance/excuses",
        json={
            "student_id": st_mine,
            "excuse_date": DATE_STR,
            "reason_text": "مرض",
        },
        headers=g_mine_hdr,
    )
    assert res_short.status_code == 422
    assert res_short.json()["error"]["code"] == "validation_error"

    # 2. Guardian trying to submit excuse for foreign child -> 403 Forbidden
    res_foreign = client.post(
        "/api/v1/attendance/excuses",
        json={
            "student_id": st_other,
            "excuse_date": DATE_STR,
            "reason_text": "عذر مرضي معتمد وموثق",
        },
        headers=g_mine_hdr,
    )
    assert res_foreign.status_code == 403
    assert res_foreign.json()["error"]["code"] == "forbidden"

    # 3. Foreign/non-existent staged attachment -> 404 Not Found
    res_bad_att = client.post(
        "/api/v1/attendance/excuses",
        json={
            "student_id": st_mine,
            "excuse_date": DATE_STR,
            "reason_text": "عذر مرضي معتمد وموثق",
            "attachment_storage_key": "non_existent_file.pdf",
        },
        headers=g_mine_hdr,
    )
    assert res_bad_att.status_code == 404

    # 4. Valid submission with own staged attachment -> 201 Created
    staged_key = _seed_staged_upload(db, g_mine_uid)
    res_ok = client.post(
        "/api/v1/attendance/excuses",
        json={
            "student_id": st_mine,
            "excuse_date": DATE_STR,
            "reason_text": "عذر مرضي معتمد وموثق تماماً",
            "attachment_storage_key": staged_key,
        },
        headers=g_mine_hdr,
    )
    assert res_ok.status_code == 201
    body = res_ok.json()
    assert body["status"] == "pending"
    assert body["student_id"] == st_mine
    assert body["attachment_storage_key"] == staged_key


def test_create_excuse_replay_idempotency_and_duplicate_conflict(client, manager, db):
    """Submitting duplicate active excuse -> 409; replaying same client_request_id -> 200 (P10)."""
    room = _room(client, manager, "قاعة المجد")
    st = _student(client, manager, "طالب المجد", room)
    g_uid, g_hdr = _guardian(db, client, "parent_majd", [st])

    req_id = f"client_req_{uuid.uuid4().hex[:8]}"

    # 1. First submission -> 201
    res1 = client.post(
        "/api/v1/attendance/excuses",
        json={
            "student_id": st,
            "excuse_date": DATE_STR,
            "reason_text": "عذر مرضي معتمد وموثق",
            "client_request_id": req_id,
        },
        headers=g_hdr,
    )
    assert res1.status_code == 201
    created_id = res1.json()["id"]

    # 2. Replay with the same client_request_id -> 200 with same row (idempotent, P10)
    res_replay = client.post(
        "/api/v1/attendance/excuses",
        json={
            "student_id": st,
            "excuse_date": DATE_STR,
            "reason_text": "عذر مرضي معتمد وموثق",
            "client_request_id": req_id,
        },
        headers=g_hdr,
    )
    assert res_replay.status_code == 200
    assert res_replay.json()["id"] == created_id

    # 3. New submission without client_request_id for the same date -> 409 Conflict
    res_duplicate = client.post(
        "/api/v1/attendance/excuses",
        json={
            "student_id": st,
            "excuse_date": DATE_STR,
            "reason_text": "عذر آخر لنفس اليوم",
        },
        headers=g_hdr,
    )
    assert res_duplicate.status_code == 409
    assert res_duplicate.json()["error"]["code"] == "conflict"


def test_list_excuses_staff_permissions_and_filtering(client, manager, db):
    """Staff excuse queue enforces permissions.attendance for supervisors and supports filters (G1)."""
    room1 = _room(client, manager, "قاعة أ1")
    room2 = _room(client, manager, "قاعة أ2")
    st1 = _student(client, manager, "طالب س", room1)
    st2 = _student(client, manager, "طالب ص", room2)

    _, g1 = _guardian(db, client, "parent_s", [st1])
    _, g2 = _guardian(db, client, "parent_y", [st2])

    # Seed excuse for st1 (pending)
    client.post(
        "/api/v1/attendance/excuses",
        json={"student_id": st1, "excuse_date": "2026-10-20", "reason_text": "عذر مرضي للطالب الأول"},
        headers=g1,
    )
    # Seed excuse for st2 (pending)
    client.post(
        "/api/v1/attendance/excuses",
        json={"student_id": st2, "excuse_date": "2026-10-21", "reason_text": "عذر مرضي للطالب الثاني"},
        headers=g2,
    )

    # Supervisor without attendance permission
    sup_no_att_id = make_user(db, "sup_no_exc", "supervisor")
    db.execute("INSERT INTO user_permissions (user_id, attendance) VALUES (%s, false)", (sup_no_att_id,))
    db.commit()
    sup_no_att_hdr = login(client, "sup_no_exc")

    # Supervisor with attendance permission
    sup_att_id = make_user(db, "sup_with_exc", "supervisor")
    db.execute("INSERT INTO user_permissions (user_id, attendance) VALUES (%s, true)", (sup_att_id,))
    db.commit()
    sup_att_hdr = login(client, "sup_with_exc")

    # 1. Supervisor without attendance permission -> 403
    res_denied = client.get("/api/v1/attendance/excuses", headers=sup_no_att_hdr)
    assert res_denied.status_code == 403

    # 2. Supervisor with attendance permission -> 200
    res_sup = client.get("/api/v1/attendance/excuses", headers=sup_att_hdr)
    assert res_sup.status_code == 200
    assert res_sup.json()["total"] >= 2

    # 3. Manager -> 200
    res_mgr = client.get("/api/v1/attendance/excuses", headers=manager)
    assert res_mgr.status_code == 200

    # 4. Filter by room_id
    res_filtered = client.get(f"/api/v1/attendance/excuses?room_id={room1}", headers=manager)
    assert res_filtered.status_code == 200
    items = res_filtered.json()["items"]
    assert all(it["room_id"] == room1 for it in items)


def test_review_excuse_cas_guard_and_attendance_conversion(client, manager, db):
    """Reviewing excuse enforces CAS on 'pending', converts ONLY 'غائب' to 'غياب بعذر', emits notification (P5, P6, m7)."""
    room = _room(client, manager, "قاعة الوفاء")
    st1 = _student(client, manager, "طالب غائب أصلاً", room)
    st2 = _student(client, manager, "طالب حاضر أصلاً", room)
    st3 = _student(client, manager, "طالب مستأذن أصلاً", room)

    _, g1 = _guardian(db, client, "p_st1", [st1])
    _, g2 = _guardian(db, client, "p_st2", [st2])
    _, g3 = _guardian(db, client, "p_st3", [st3])

    # Seed attendance rows:
    # st1: غائب
    # st2: حاضر
    # st3: مستأذن
    db.execute(
        """
        INSERT INTO student_attendance (student_id, date, status, is_draft) VALUES
        (%s, %s, 'غائب', FALSE),
        (%s, %s, 'حاضر', FALSE),
        (%s, %s, 'مستأذن', FALSE)
        """,
        (st1, DAY, st2, DAY, st3, DAY),
    )
    db.commit()

    # Submit excuses for all three
    e1_id = client.post(
        "/api/v1/attendance/excuses",
        json={"student_id": st1, "excuse_date": DATE_STR, "reason_text": "عذر الطالب الأول الغائب"},
        headers=g1,
    ).json()["id"]

    e2_id = client.post(
        "/api/v1/attendance/excuses",
        json={"student_id": st2, "excuse_date": DATE_STR, "reason_text": "عذر الطالب الثاني الحاضر"},
        headers=g2,
    ).json()["id"]

    e3_id = client.post(
        "/api/v1/attendance/excuses",
        json={"student_id": st3, "excuse_date": DATE_STR, "reason_text": "عذر الطالب الثالث المستأذن"},
        headers=g3,
    ).json()["id"]

    # 1. Validation: reject or needs_info with missing notes -> 422
    res_bad_reject = client.post(
        f"/api/v1/attendance/excuses/{e1_id}/review",
        json={"action": "reject", "supervisor_notes": "   "},
        headers=manager,
    )
    assert res_bad_reject.status_code == 422

    # 2. Approve e1 (st1 has status 'غائب') -> converted to 'غياب بعذر' with excuse_id linked
    res_app1 = client.post(
        f"/api/v1/attendance/excuses/{e1_id}/review",
        json={"action": "approve", "supervisor_notes": "تم قبول العذر"},
        headers=manager,
    )
    assert res_app1.status_code == 200
    assert res_app1.json()["status"] == "approved"

    # Check st1 attendance: MUST be 'غياب بعذر' with excuse_id linked
    row_st1 = db.execute(
        "SELECT status, excuse_id FROM student_attendance WHERE student_id = %s AND date = %s",
        (st1, DAY),
    ).fetchone()
    assert row_st1["status"] == "غياب بعذر"
    assert str(row_st1["excuse_id"]) == e1_id

    # 3. CAS guard: re-reviewing e1 -> 409 Conflict (P6)
    res_race = client.post(
        f"/api/v1/attendance/excuses/{e1_id}/review",
        json={"action": "reject", "supervisor_notes": "تغيير رأي المشرفة"},
        headers=manager,
    )
    assert res_race.status_code == 409
    assert res_race.json()["error"]["code"] == "conflict"

    # 4. Approve e2 (st2 has status 'حاضر') -> attendance status MUST REMAIN 'حاضر' (P5)
    res_app2 = client.post(
        f"/api/v1/attendance/excuses/{e2_id}/review",
        json={"action": "approve"},
        headers=manager,
    )
    assert res_app2.status_code == 200
    row_st2 = db.execute(
        "SELECT status, excuse_id FROM student_attendance WHERE student_id = %s AND date = %s",
        (st2, DAY),
    ).fetchone()
    assert row_st2["status"] == "حاضر"
    assert row_st2["excuse_id"] is None

    # 5. Approve e3 (st3 has status 'مستأذن') -> attendance status MUST REMAIN 'مستأذن' (m7)
    res_app3 = client.post(
        f"/api/v1/attendance/excuses/{e3_id}/review",
        json={"action": "approve"},
        headers=manager,
    )
    assert res_app3.status_code == 200
    row_st3 = db.execute(
        "SELECT status, excuse_id FROM student_attendance WHERE student_id = %s AND date = %s",
        (st3, DAY),
    ).fetchone()
    assert row_st3["status"] == "مستأذن"
    assert row_st3["excuse_id"] is None

    # 6. Verify notification was sent for e1 with target_type='excuse'
    notif = db.execute(
        "SELECT kind, target_type, target_id, title FROM notifications WHERE target_id = %s",
        (e1_id,),
    ).fetchone()
    assert notif is not None
    assert notif["kind"] == "excuse"
    assert notif["target_type"] == "excuse"
    assert notif["title"] == "تم قبول عذر الغياب"


def test_patch_excuse_needs_info_lifecycle(client, manager, db):
    """Excuse in 'needs_info' can be patched by owner; moves to 'pending' and keeps notes (P7)."""
    room = _room(client, manager, "قاعة الهدى")
    st = _student(client, manager, "طالب الهدى", room)
    g_uid, g_hdr = _guardian(db, client, "parent_huda", [st])

    # Stranger guardian
    st_other = _student(client, manager, "طالب آخر", room)
    _, g_other_hdr = _guardian(db, client, "parent_stranger_huda", [st_other])

    # 1. Submit excuse -> pending
    e_id = client.post(
        "/api/v1/attendance/excuses",
        json={"student_id": st, "excuse_date": DATE_STR, "reason_text": "عذر مبدئي بحاجة تقرير"},
        headers=g_hdr,
    ).json()["id"]

    # 2. Attempting to patch while 'pending' -> 409 Conflict (P7)
    res_early_patch = client.patch(
        f"/api/v1/attendance/excuses/{e_id}",
        json={"reason_text": "تعديل قبل طلب الاستكمال"},
        headers=g_hdr,
    )
    assert res_early_patch.status_code == 409

    # 3. Supervisor reviews with 'needs_info'
    res_rev = client.post(
        f"/api/v1/attendance/excuses/{e_id}/review",
        json={"action": "needs_info", "supervisor_notes": "يرجى إرفاق التقرير الطبي من المستشفى"},
        headers=manager,
    )
    assert res_rev.status_code == 200
    assert res_rev.json()["status"] == "needs_info"

    # 4. Stranger guardian trying to patch -> 403 Forbidden
    res_stranger = client.patch(
        f"/api/v1/attendance/excuses/{e_id}",
        json={"reason_text": "محاولة تعديل من شخص غريب"},
        headers=g_other_hdr,
    )
    assert res_stranger.status_code == 403

    # 4b. X2 check: Staff (manager / supervisor) cannot patch -> 403 Forbidden (guardians only)
    res_mgr_patch = client.patch(
        f"/api/v1/attendance/excuses/{e_id}",
        json={"reason_text": "تعديل غير مصرح من المدير"},
        headers=manager,
    )
    assert res_mgr_patch.status_code == 403

    # 5. Owner guardian patches with updated reason and new attachment -> 200
    staged_key = _seed_staged_upload(db, g_uid)
    res_patch = client.patch(
        f"/api/v1/attendance/excuses/{e_id}",
        json={
            "reason_text": "تم إرفاق التقرير الطبي المعتمد كاملاً",
            "attachment_storage_key": staged_key,
        },
        headers=g_hdr,
    )
    assert res_patch.status_code == 200
    patched_body = res_patch.json()
    assert patched_body["status"] == "pending"  # Back to pending!
    assert patched_body["reason_text"] == "تم إرفاق التقرير الطبي المعتمد كاملاً"
    assert patched_body["attachment_storage_key"] == staged_key
    assert patched_body["supervisor_notes"] == "يرجى إرفاق التقرير الطبي من المستشفى"  # Preserved!


def test_create_excuse_supervisor_permissions_check(client, manager, db):
    """Supervisor must have permissions.attendance to submit excuses via POST (X2)."""
    room = _room(client, manager, "قاعة الفجر")
    st = _student(client, manager, "طالب الفجر", room)

    # Supervisor without attendance permission -> 403
    sup_no_id = make_user(db, "sup_no_create_exc", "supervisor")
    db.execute("INSERT INTO user_permissions (user_id, attendance) VALUES (%s, false)", (sup_no_id,))
    db.commit()
    sup_no_hdr = login(client, "sup_no_create_exc")

    res_no = client.post(
        "/api/v1/attendance/excuses",
        json={"student_id": st, "excuse_date": DATE_STR, "reason_text": "عذر من مشرفة بلا صلاحية"},
        headers=sup_no_hdr,
    )
    assert res_no.status_code == 403

    # Supervisor with attendance permission -> 201
    sup_ok_id = make_user(db, "sup_ok_create_exc", "supervisor")
    db.execute("INSERT INTO user_permissions (user_id, attendance) VALUES (%s, true)", (sup_ok_id,))
    db.commit()
    sup_ok_hdr = login(client, "sup_ok_create_exc")

    res_ok = client.post(
        "/api/v1/attendance/excuses",
        json={"student_id": st, "excuse_date": DATE_STR, "reason_text": "عذر من مشرفة ذات صلاحية"},
        headers=sup_ok_hdr,
    )
    assert res_ok.status_code == 201
