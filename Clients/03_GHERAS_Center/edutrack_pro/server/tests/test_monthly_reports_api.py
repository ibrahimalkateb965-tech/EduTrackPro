"""API integration tests for Staff Monthly Reports endpoints (Batch S5-B3)."""

from __future__ import annotations

import uuid
from datetime import date

import pytest

from edutrack_api.auth import issue_token
from edutrack_api.config import get_settings


def _auth_header(user_id: str | uuid.UUID, role: str) -> dict[str, str]:
    token = issue_token({"id": str(user_id), "role": role, "token_version": 1}, get_settings())
    return {"Authorization": f"Bearer {token}"}


def _seed_staff_fixture(db):
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]

    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة الورود', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]

    other_room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة الياسمين', 'المساء') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]

    # Teacher assigned to room_id via schedules
    teacher_id = db.execute(
        "INSERT INTO users (username, role, password_hash, room_id) VALUES ('teacher_m5_api', 'teacher', 'hash', %s) RETURNING id",
        (room_id,)
    ).fetchone()["id"]

    db.execute("""
        INSERT INTO schedules (branch_id, room_id, teacher_user_id, day, start_time, end_time, subject, group_name)
        VALUES (%s, %s, %s, 'الأحد', '08:00', '10:00', 'القرآن', 'الصباح')
    """, (branch_id, room_id, teacher_id))

    # Foreign teacher
    foreign_teacher_id = db.execute(
        "INSERT INTO users (username, role, password_hash, room_id) VALUES ('teacher_m5_foreign', 'teacher', 'hash', %s) RETURNING id",
        (other_room_id,)
    ).fetchone()["id"]

    # Supervisor
    supervisor_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('supervisor_m5_api', 'supervisor', 'hash') RETURNING id"
    ).fetchone()["id"]
    db.execute(
        "INSERT INTO user_permissions (branch_id, user_id, monthly_evaluation) VALUES (%s, %s, TRUE)",
        (branch_id, supervisor_id),
    )

    # Student in room_id
    student_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'عبدالله محمد') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]

    # Attendance records
    db.execute("""
        INSERT INTO student_attendance (branch_id, student_id, date, status, is_draft)
        VALUES 
            (%s, %s, '2026-10-01', 'حاضر', FALSE),
            (%s, %s, '2026-10-02', 'حاضر', FALSE),
            (%s, %s, '2026-10-03', 'متأخر', FALSE),
            (%s, %s, '2026-10-04', 'غائب', FALSE)
    """, (branch_id, student_id, branch_id, student_id, branch_id, student_id, branch_id, student_id))

    # Guardian setup
    g_rec = db.execute(
        "INSERT INTO guardians (branch_id, name, phone) VALUES (%s, 'محمد والد عبدالله', '0559998877') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]

    db.execute(
        "INSERT INTO student_guardians (branch_id, student_id, guardian_id, is_primary) VALUES (%s, %s, %s, TRUE)",
        (branch_id, student_id, g_rec)
    )

    guardian_user_id = db.execute(
        "INSERT INTO users (username, role, password_hash, guardian_id) VALUES ('user_guardian_m5', 'guardian', 'hash', %s) RETURNING id",
        (g_rec,)
    ).fetchone()["id"]

    db.commit()
    return {
        "branch_id": branch_id,
        "room_id": room_id,
        "other_room_id": other_room_id,
        "teacher_id": teacher_id,
        "foreign_teacher_id": foreign_teacher_id,
        "supervisor_id": supervisor_id,
        "student_id": student_id,
        "guardian_user_id": guardian_user_id,
    }


def test_get_auto_attendance_stats_api(client, db):
    """Test GET /api/v1/monthly-reports/auto-stats with scope enforcement."""
    ctx = _seed_staff_fixture(db)
    t_headers = _auth_header(ctx["teacher_id"], "teacher")
    f_headers = _auth_header(ctx["foreign_teacher_id"], "teacher")
    s_headers = _auth_header(ctx["supervisor_id"], "supervisor")

    # 1. Assigned teacher succeeds
    res = client.get(
        f"/api/v1/monthly-reports/auto-stats?student_id={ctx['student_id']}&month=2026-10",
        headers=t_headers,
    )
    assert res.status_code == 200
    data = res.json()
    assert data["total_days"] == 4
    assert data["present"] == 2
    assert data["late"] == 1
    assert data["absent"] == 1

    # 2. Supervisor succeeds for any student
    res_sup = client.get(
        f"/api/v1/monthly-reports/auto-stats?student_id={ctx['student_id']}&month=2026-10",
        headers=s_headers,
    )
    assert res_sup.status_code == 200

    # 3. Foreign teacher receives 403
    res_foreign = client.get(
        f"/api/v1/monthly-reports/auto-stats?student_id={ctx['student_id']}&month=2026-10",
        headers=f_headers,
    )
    assert res_foreign.status_code == 403

    # 4. Invalid month format returns 422
    res_bad = client.get(
        f"/api/v1/monthly-reports/auto-stats?student_id={ctx['student_id']}&month=bad-month",
        headers=t_headers,
    )
    assert res_bad.status_code == 422


def test_monthly_report_full_lifecycle_api(client, db):
    """Test full cycle: save draft -> submit -> review request revision -> resubmit -> approve -> publish."""
    ctx = _seed_staff_fixture(db)
    t_headers = _auth_header(ctx["teacher_id"], "teacher")
    s_headers = _auth_header(ctx["supervisor_id"], "supervisor")

    # 1. Teacher creates draft
    payload = {
        "student_id": str(ctx["student_id"]),
        "room_id": str(ctx["room_id"]),
        "month": "2026-10",
        "subject_ratings": [
            {"subject": "القرآن الكريم", "rating": "متميز", "notes": "حفظ متقن"},
            {"subject": "اللغة العربية", "rating": "متقن"},
        ],
        "strengths": "سرعة الحفظ",
        "improvements": "التركيز في أحكام التجويد",
        "recommendations": "المراجعة اليومية",
        "action": "save_draft",
    }

    res_draft = client.post("/api/v1/monthly-reports", json=payload, headers=t_headers)
    assert res_draft.status_code == 200
    report_data = res_draft.json()
    report_id = report_data["id"]
    assert report_data["status"] == "draft"
    assert report_data["attendance_stats"]["total_days"] == 4

    # 2. Teacher submits for approval
    payload["action"] = "submit"
    res_submit = client.post("/api/v1/monthly-reports", json=payload, headers=t_headers)
    assert res_submit.status_code == 200
    submitted = res_submit.json()
    assert submitted["status"] == "pending_approval"
    assert submitted["submitted_at"] is not None

    # 3. Teacher cannot review (403)
    res_t_rev = client.post(
        f"/api/v1/monthly-reports/{report_id}/review",
        json={"action": "approve"},
        headers=t_headers,
    )
    assert res_t_rev.status_code == 403

    # 4. Supervisor requests revision
    res_rev = client.post(
        f"/api/v1/monthly-reports/{report_id}/review",
        json={"action": "request_revision", "revision_notes": "يرجى تفصيل الملاحظة في التجويد"},
        headers=s_headers,
    )
    assert res_rev.status_code == 200
    revised = res_rev.json()
    assert revised["status"] == "needs_revision"
    assert revised["revision_notes"] == "يرجى تفصيل الملاحظة في التجويد"

    # 5. Teacher resubmits
    payload["strengths"] = "سرعة الحفظ وتطبيق أحكام النون الساكنة"
    payload["action"] = "submit"
    res_resubmit = client.post("/api/v1/monthly-reports", json=payload, headers=t_headers)
    assert res_resubmit.status_code == 200
    assert res_resubmit.json()["status"] == "pending_approval"

    # 6. Supervisor approves
    res_app = client.post(
        f"/api/v1/monthly-reports/{report_id}/review",
        json={"action": "approve"},
        headers=s_headers,
    )
    assert res_app.status_code == 200
    assert res_app.json()["status"] == "approved"

    # 7. Teacher cannot publish (403)
    res_t_pub = client.post(f"/api/v1/monthly-reports/{report_id}/publish", headers=t_headers)
    assert res_t_pub.status_code == 403

    # 8. Supervisor publishes
    res_pub = client.post(f"/api/v1/monthly-reports/{report_id}/publish", headers=s_headers)
    assert res_pub.status_code == 200
    published = res_pub.json()
    assert published["status"] == "published"
    assert published["published_at"] is not None

    # Verify notification reached guardian
    notif = db.execute("SELECT * FROM notifications WHERE user_id = %s", (ctx["guardian_user_id"],)).fetchone()
    assert notif is not None
    assert notif["target_type"] == "monthly_report"


def test_batch_publish_api(client, db):
    """Test POST /api/v1/monthly-reports/batch-publish."""
    ctx = _seed_staff_fixture(db)
    t_headers = _auth_header(ctx["teacher_id"], "teacher")
    s_headers = _auth_header(ctx["supervisor_id"], "supervisor")

    # Create and approve report
    payload = {
        "student_id": str(ctx["student_id"]),
        "room_id": str(ctx["room_id"]),
        "month": "2026-10",
        "action": "submit",
    }
    r = client.post("/api/v1/monthly-reports", json=payload, headers=t_headers).json()
    client.post(f"/api/v1/monthly-reports/{r['id']}/review", json={"action": "approve"}, headers=s_headers)

    # Batch publish
    res_batch = client.post(
        "/api/v1/monthly-reports/batch-publish",
        json={"room_id": str(ctx["room_id"]), "month": "2026-10"},
        headers=s_headers,
    )
    assert res_batch.status_code == 200
    published_items = res_batch.json()
    assert len(published_items) == 1
    assert published_items[0]["status"] == "published"


def _submit_report(client, ctx, t_headers) -> str:
    payload = {
        "student_id": str(ctx["student_id"]),
        "room_id": str(ctx["room_id"]),
        "month": "2026-10",
        "action": "submit",
    }
    res = client.post("/api/v1/monthly-reports", json=payload, headers=t_headers)
    assert res.status_code == 200
    return res.json()["id"]


def _guardian_notification_count(db, guardian_user_id) -> int:
    return db.execute(
        "SELECT count(*) AS n FROM notifications WHERE user_id = %s AND target_type = 'monthly_report'",
        (guardian_user_id,),
    ).fetchone()["n"]


def test_publish_requires_approval(client, db):
    """B1: a pending report cannot be published (single or batch) and guardians are not notified."""
    ctx = _seed_staff_fixture(db)
    t_headers = _auth_header(ctx["teacher_id"], "teacher")
    s_headers = _auth_header(ctx["supervisor_id"], "supervisor")
    report_id = _submit_report(client, ctx, t_headers)

    res = client.post(f"/api/v1/monthly-reports/{report_id}/publish", headers=s_headers)
    assert res.status_code == 422

    res_batch = client.post(
        "/api/v1/monthly-reports/batch-publish",
        json={"room_id": str(ctx["room_id"]), "month": "2026-10"},
        headers=s_headers,
    )
    assert res_batch.status_code == 200
    assert res_batch.json() == []

    status = db.execute("SELECT status FROM monthly_student_reports WHERE id = %s", (report_id,)).fetchone()["status"]
    assert status == "pending_approval"
    assert _guardian_notification_count(db, ctx["guardian_user_id"]) == 0


def test_review_and_publish_require_monthly_evaluation_permission(client, db):
    """B2: a supervisor without the monthly_evaluation flag cannot review, publish or batch-publish."""
    ctx = _seed_staff_fixture(db)
    t_headers = _auth_header(ctx["teacher_id"], "teacher")
    report_id = _submit_report(client, ctx, t_headers)

    plain_sup_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('supervisor_m5_noperm', 'supervisor', 'hash') RETURNING id"
    ).fetchone()["id"]
    db.execute(
        "INSERT INTO user_permissions (branch_id, user_id, daily_evaluation, students) VALUES (%s, %s, TRUE, TRUE)",
        (ctx["branch_id"], plain_sup_id),
    )
    db.commit()
    p_headers = _auth_header(plain_sup_id, "supervisor")

    res_rev = client.post(f"/api/v1/monthly-reports/{report_id}/review", json={"action": "approve"}, headers=p_headers)
    assert res_rev.status_code == 403

    # Approve with the permitted supervisor, then the plain one still cannot publish
    s_headers = _auth_header(ctx["supervisor_id"], "supervisor")
    assert client.post(f"/api/v1/monthly-reports/{report_id}/review", json={"action": "approve"}, headers=s_headers).status_code == 200

    assert client.post(f"/api/v1/monthly-reports/{report_id}/publish", headers=p_headers).status_code == 403
    res_batch = client.post(
        "/api/v1/monthly-reports/batch-publish",
        json={"room_id": str(ctx["room_id"]), "month": "2026-10"},
        headers=p_headers,
    )
    assert res_batch.status_code == 403
    assert _guardian_notification_count(db, ctx["guardian_user_id"]) == 0


def test_cannot_approve_report_awaiting_revision(client, db):
    """M1: a report sent back for revision must be resubmitted before approval."""
    ctx = _seed_staff_fixture(db)
    t_headers = _auth_header(ctx["teacher_id"], "teacher")
    s_headers = _auth_header(ctx["supervisor_id"], "supervisor")
    report_id = _submit_report(client, ctx, t_headers)

    res_rev = client.post(
        f"/api/v1/monthly-reports/{report_id}/review",
        json={"action": "request_revision", "revision_notes": "يرجى استكمال الملاحظات"},
        headers=s_headers,
    )
    assert res_rev.status_code == 200

    res_app = client.post(f"/api/v1/monthly-reports/{report_id}/review", json={"action": "approve"}, headers=s_headers)
    assert res_app.status_code == 422
    status = db.execute("SELECT status FROM monthly_student_reports WHERE id = %s", (report_id,)).fetchone()["status"]
    assert status == "needs_revision"


def test_double_publish_notifies_guardian_once(client, db):
    """M2: a repeated publish is rejected and guardians get exactly one notification."""
    ctx = _seed_staff_fixture(db)
    t_headers = _auth_header(ctx["teacher_id"], "teacher")
    s_headers = _auth_header(ctx["supervisor_id"], "supervisor")
    report_id = _submit_report(client, ctx, t_headers)
    client.post(f"/api/v1/monthly-reports/{report_id}/review", json={"action": "approve"}, headers=s_headers)

    assert client.post(f"/api/v1/monthly-reports/{report_id}/publish", headers=s_headers).status_code == 200
    assert client.post(f"/api/v1/monthly-reports/{report_id}/publish", headers=s_headers).status_code == 422
    assert _guardian_notification_count(db, ctx["guardian_user_id"]) == 1


def test_publish_rejects_stale_read(db, monkeypatch):
    """M2: when another publisher wins between the read and the update, the update matches no row."""
    from edutrack_api.repositories import monthly_reports_repo

    ctx = _seed_staff_fixture(db)
    report = monthly_reports_repo.upsert_monthly_report(
        db, ctx["branch_id"], ctx["student_id"], ctx["room_id"], "2026-10", ctx["teacher_id"], {}, [], action="submit"
    )
    monthly_reports_repo.review_monthly_report(db, report["id"], ctx["supervisor_id"], action="approve")
    stale = dict(monthly_reports_repo.get_monthly_report_by_id(db, report["id"]))
    db.execute("UPDATE monthly_student_reports SET status = 'published' WHERE id = %s", (report["id"],))

    monkeypatch.setattr(monthly_reports_repo, "get_monthly_report_by_id", lambda conn, rid: stale)
    with pytest.raises(ValueError):
        monthly_reports_repo.publish_monthly_report(db, report["id"], ctx["supervisor_id"])


def test_pdf_endpoint_rejects_student_role(client, db):
    """M3: only staff and guardians may read the report PDF link; a student account cannot read a draft."""
    ctx = _seed_staff_fixture(db)
    t_headers = _auth_header(ctx["teacher_id"], "teacher")
    report_id = _submit_report(client, ctx, t_headers)

    student_user_id = db.execute(
        "INSERT INTO users (username, role, password_hash, student_id) VALUES ('student_m5_pdf', 'student', 'hash', %s) RETURNING id",
        (ctx["student_id"],),
    ).fetchone()["id"]
    db.commit()

    res = client.get(f"/api/v1/monthly-reports/{report_id}/pdf", headers=_auth_header(student_user_id, "student"))
    assert res.status_code == 403
