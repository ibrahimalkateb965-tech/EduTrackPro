"""Tests for Student Attendance Draft Filtering and /api/v1/me/excuses (Batch S3-B4b).

Covers:
1. Guardian attendance queries hide all drafts (is_draft=FALSE) strictly (P4, m1).
2. Teacher attendance queries can view draft attendance records for room students.
3. Guardian /me/excuses returns excuses for their children with attachment_url (G1).
4. Cross-family isolation on /me/excuses (Guardian A cannot see Guardian B's excuses).
5. /me/excuses filtering by status, date_from, date_to, and narrowing student_id.
6. Role guards: Manager/Supervisor get 403, unauthenticated gets 401.
7. Teacher /me/excuses visibility of scoped room students.
"""

from __future__ import annotations

import datetime as dt
import uuid

import pytest

from tests.conftest import (
    MAIN_BRANCH,
    _guardian,
    _room,
    _student,
    _teacher,
    login,
    make_user,
)


def _seed_attendance(
    db,
    student_id: str,
    att_date: str,
    status: str = "غائب",
    is_draft: bool = False,
    excuse_id: str | None = None,
) -> str:
    row = db.execute(
        """
        INSERT INTO student_attendance (
            branch_id, student_id, date, status, is_draft, excuse_id
        ) VALUES (
            %s, %s, %s, %s, %s, %s
        ) RETURNING id
        """,
        (MAIN_BRANCH, student_id, att_date, status, is_draft, excuse_id),
    ).fetchone()
    db.commit()
    return str(row["id"])


def _seed_excuse(
    db,
    student_id: str,
    excuse_date: str,
    status: str = "pending",
    submitted_by_user_id: str | None = None,
    attachment_storage_key: str | None = None,
    reason_text: str = "عذر مرضي معتمد",
    supervisor_notes: str | None = None,
) -> str:
    if not submitted_by_user_id:
        submitted_by_user_id = str(make_user(db, f"parent_{uuid.uuid4().hex[:6]}", "guardian"))
    row = db.execute(
        """
        INSERT INTO attendance_excuses (
            branch_id, student_id, excuse_date, submitted_by_user_id,
            reason_text, attachment_storage_key, status, supervisor_notes
        ) VALUES (
            %s, %s, %s, %s,
            %s, %s, %s, %s
        ) RETURNING id
        """,
        (
            MAIN_BRANCH,
            student_id,
            excuse_date,
            submitted_by_user_id,
            reason_text,
            attachment_storage_key,
            status,
            supervisor_notes,
        ),
    ).fetchone()
    db.commit()
    return str(row["id"])


@pytest.fixture()
def setup_env(client, db, manager):
    room_a = _room(client, manager, "قاعة النور")
    room_b = _room(client, manager, "قاعة الأمل")

    s_a1 = _student(client, manager, "سارة أحمد", room_a)
    s_b1 = _student(client, manager, "عمر خالد", room_b)

    teacher_id, teacher_auth = _teacher(db, client, "teacher_nour", room_a)
    g1_id, g1_auth = _guardian(db, client, "guardian_sara", [s_a1])
    g2_id, g2_auth = _guardian(db, client, "guardian_omar", [s_b1])

    return {
        "room_a": room_a,
        "room_b": room_b,
        "s_a1": s_a1,
        "s_b1": s_b1,
        "teacher_auth": teacher_auth,
        "g1_auth": g1_auth,
        "g2_auth": g2_auth,
    }


def test_guardian_attendance_hides_drafts(client, db, setup_env):
    s_a1 = setup_env["s_a1"]
    g1_auth = setup_env["g1_auth"]

    # Seed 1 approved attendance and 1 draft attendance
    _seed_attendance(db, s_a1, "2026-10-20", status="حاضر", is_draft=False)
    _seed_attendance(db, s_a1, "2026-10-21", status="غائب", is_draft=True)

    res = client.get("/api/v1/me/attendance", headers=g1_auth)
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["total"] == 1
    assert len(data["items"]) == 1
    assert data["items"][0]["date"] == "2026-10-20"
    assert data["items"][0]["status"] == "حاضر"
    assert data["items"][0]["is_draft"] is False


def test_teacher_attendance_shows_drafts(client, db, setup_env):
    s_a1 = setup_env["s_a1"]
    teacher_auth = setup_env["teacher_auth"]

    # Seed 1 approved attendance and 1 draft attendance
    _seed_attendance(db, s_a1, "2026-10-20", status="حاضر", is_draft=False)
    _seed_attendance(db, s_a1, "2026-10-21", status="غائب", is_draft=True)

    res = client.get("/api/v1/me/attendance", headers=teacher_auth)
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["total"] == 2
    assert len(data["items"]) == 2
    dates = {item["date"] for item in data["items"]}
    assert dates == {"2026-10-20", "2026-10-21"}


def test_guardian_me_excuses_isolation_and_envelope(client, db, setup_env):
    s_a1 = setup_env["s_a1"]
    s_b1 = setup_env["s_b1"]
    g1_auth = setup_env["g1_auth"]
    g2_auth = setup_env["g2_auth"]

    exc1_id = _seed_excuse(
        db,
        s_a1,
        "2026-10-22",
        status="pending",
        attachment_storage_key="med_doc_abc.pdf",
        reason_text="مرض مفاجئ وارتفاع حرارة",
    )
    exc2_id = _seed_excuse(
        db,
        s_b1,
        "2026-10-22",
        status="approved",
        attachment_storage_key=None,
        reason_text="سفر عائلي",
    )

    # Guardian 1 check
    res1 = client.get("/api/v1/me/excuses", headers=g1_auth)
    assert res1.status_code == 200, res1.text
    data1 = res1.json()
    assert data1["total"] == 1
    assert len(data1["items"]) == 1
    item1 = data1["items"][0]
    assert item1["id"] == exc1_id
    assert item1["student_name"] == "سارة أحمد"
    assert item1["status"] == "pending"
    assert item1["attachment_storage_key"] == "med_doc_abc.pdf"
    assert item1["attachment_url"] == "/api/v1/me/files/med_doc_abc.pdf"

    # Guardian 2 check (isolation: cannot see exc1)
    res2 = client.get("/api/v1/me/excuses", headers=g2_auth)
    assert res2.status_code == 200, res2.text
    data2 = res2.json()
    assert data2["total"] == 1
    assert len(data2["items"]) == 1
    item2 = data2["items"][0]
    assert item2["id"] == exc2_id
    assert item2["student_name"] == "عمر خالد"
    assert item2["status"] == "approved"
    assert item2["attachment_storage_key"] is None
    assert item2["attachment_url"] is None


def test_guardian_me_excuses_filtering(client, db, setup_env):
    s_a1 = setup_env["s_a1"]
    s_b1 = setup_env["s_b1"]
    g1_auth = setup_env["g1_auth"]

    _seed_excuse(db, s_a1, "2026-10-18", status="approved", reason_text="عذر سابق مقبول")
    _seed_excuse(db, s_a1, "2026-10-19", status="pending", reason_text="عذر قيد المراجعة")
    _seed_excuse(db, s_a1, "2026-10-20", status="needs_info", reason_text="عذر يحتاج تقرير")

    # Filter status=pending
    res = client.get("/api/v1/me/excuses?status=pending", headers=g1_auth)
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["total"] == 1
    assert data["items"][0]["status"] == "pending"
    assert data["items"][0]["excuse_date"] == "2026-10-19"

    # Filter date_from and date_to
    res = client.get("/api/v1/me/excuses?date_from=2026-10-19&date_to=2026-10-20", headers=g1_auth)
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["total"] == 2

    # Filter out-of-scope student_id (s_b1) returns empty list, does not leak
    res = client.get(f"/api/v1/me/excuses?student_id={s_b1}", headers=g1_auth)
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["total"] == 0
    assert data["items"] == []


def test_me_excuses_role_guards(client, manager, supervisor):
    # Manager
    res = client.get("/api/v1/me/excuses", headers=manager)
    assert res.status_code == 403, res.text

    # Supervisor
    res = client.get("/api/v1/me/excuses", headers=supervisor)
    assert res.status_code == 403, res.text

    # Unauthenticated
    res = client.get("/api/v1/me/excuses")
    assert res.status_code == 401, res.text


def test_teacher_me_excuses(client, db, setup_env):
    s_a1 = setup_env["s_a1"]
    s_b1 = setup_env["s_b1"]
    teacher_auth = setup_env["teacher_auth"]

    # Teacher teaches room_a (which has s_a1, but not s_b1)
    _seed_excuse(db, s_a1, "2026-10-22", status="pending", reason_text="عذر طالب في قاعتي")
    _seed_excuse(db, s_b1, "2026-10-22", status="pending", reason_text="عذر طالب في قاعة أخرى")

    res = client.get("/api/v1/me/excuses", headers=teacher_auth)
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["total"] == 1
    assert data["items"][0]["student_name"] == "سارة أحمد"
