"""Comprehensive repository & domain logic tests for Monthly Student Reports (Batch S5-B2)."""

from __future__ import annotations

import uuid
from datetime import date

import pytest

from edutrack_api.repositories import monthly_reports_repo
from edutrack_api.services import monthly_reports as monthly_reports_service


def _setup_test_hierarchy(db):
    """Seed branch, room, student, teacher, supervisor, and guardian."""
    branch_id = db.execute("SELECT id FROM branches LIMIT 1").fetchone()["id"]

    room_id = db.execute(
        "INSERT INTO rooms (branch_id, name, group_name) VALUES (%s, 'قاعة النجوم', 'الصباح') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]

    student_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'عمر أحمد') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]

    teacher_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('teacher_m5_repo', 'teacher', 'hash') RETURNING id"
    ).fetchone()["id"]

    supervisor_id = db.execute(
        "INSERT INTO users (username, role, password_hash) VALUES ('supervisor_m5_repo', 'supervisor', 'hash') RETURNING id"
    ).fetchone()["id"]

    # Guardian setup
    guardian_id = db.execute(
        "INSERT INTO guardians (branch_id, name, phone) VALUES (%s, 'أحمد والد عمر', '0551112233') RETURNING id",
        (branch_id,)
    ).fetchone()["id"]

    db.execute(
        "INSERT INTO student_guardians (branch_id, student_id, guardian_id, is_primary) VALUES (%s, %s, %s, TRUE)",
        (branch_id, student_id, guardian_id)
    )

    guardian_user_id = db.execute(
        "INSERT INTO users (username, role, password_hash, guardian_id) VALUES ('guardian_m5_repo', 'guardian', 'hash', %s) RETURNING id",
        (guardian_id,)
    ).fetchone()["id"]

    db.commit()
    return {
        "branch_id": branch_id,
        "room_id": room_id,
        "student_id": student_id,
        "teacher_id": teacher_id,
        "supervisor_id": supervisor_id,
        "guardian_id": guardian_id,
        "guardian_user_id": guardian_user_id,
    }


def test_compute_student_attendance_stats(db):
    """Verify attendance stats calculation aggregates accurately and ignores drafts / other months."""
    ctx = _setup_test_hierarchy(db)
    student_id = ctx["student_id"]
    branch_id = ctx["branch_id"]
    room_id = ctx["room_id"]

    # October 2026 records
    records = [
        (date(2026, 10, 1), "حاضر", False),
        (date(2026, 10, 2), "حاضر", False),
        (date(2026, 10, 3), "حاضر", False),
        (date(2026, 10, 4), "متأخر", False),
        (date(2026, 10, 5), "غياب بعذر", False),
        (date(2026, 10, 6), "غائب", False),
        (date(2026, 10, 7), "مستأذن", False),
        # Draft record (must be ignored)
        (date(2026, 10, 8), "غائب", True),
        # Next month record (must be ignored)
        (date(2026, 11, 1), "حاضر", False),
    ]

    for d, st, is_d in records:
        db.execute("""
            INSERT INTO student_attendance (branch_id, student_id, date, status, is_draft)
            VALUES (%s, %s, %s, %s, %s)
        """, (branch_id, student_id, d, st, is_d))
    db.commit()

    stats = monthly_reports_repo.compute_student_attendance_stats(db, student_id, "2026-10")

    assert stats["total_days"] == 7
    assert stats["present"] == 3
    assert stats["late"] == 1
    assert stats["excused"] == 1
    assert stats["absent"] == 1
    # present + late + permitted = 3 + 1 + 1 = 5 out of 7 -> 71.4%
    assert stats["attendance_pct"] == 71.4


def test_upsert_draft_and_submit_workflow(db):
    """Test creating draft, updating draft, and submitting for approval."""
    ctx = _setup_test_hierarchy(db)

    stats = {"total_days": 20, "present": 18, "absent": 1, "excused": 1, "late": 0, "attendance_pct": 95.0}
    ratings = [
        {"subject": "القرآن الكريم", "rating": "متميز", "notes": "حفظ متقن لسورة الملك"},
        {"subject": "اللغة العربية", "rating": "متقن", "notes": "قراءة سليمة"},
    ]

    # 1. Save as draft
    report = monthly_reports_repo.upsert_monthly_report(
        conn=db,
        branch_id=ctx["branch_id"],
        student_id=ctx["student_id"],
        room_id=ctx["room_id"],
        month="2026-10",
        teacher_user_id=ctx["teacher_id"],
        attendance_stats=stats,
        subject_ratings=ratings,
        strengths="المواظبة على الحضور",
        improvements="تحسين الخط",
        recommendations="القراءة اليومية 15 دقيقة",
        action="save_draft",
    )
    db.commit()

    assert report["id"] is not None
    assert report["status"] == "draft"
    assert report["submitted_at"] is None
    assert report["strengths"] == "المواظبة على الحضور"

    # 2. Update draft
    report_updated = monthly_reports_repo.upsert_monthly_report(
        conn=db,
        branch_id=ctx["branch_id"],
        student_id=ctx["student_id"],
        room_id=ctx["room_id"],
        month="2026-10",
        teacher_user_id=ctx["teacher_id"],
        attendance_stats=stats,
        subject_ratings=ratings,
        strengths="المواظبة والمشاركة الفعالة",
        improvements="تحسين الخط",
        recommendations="القراءة اليومية",
        action="save_draft",
    )
    db.commit()
    assert report_updated["id"] == report["id"]
    assert report_updated["strengths"] == "المواظبة والمشاركة الفعالة"
    assert report_updated["status"] == "draft"

    # 3. Submit for approval
    submitted = monthly_reports_repo.upsert_monthly_report(
        conn=db,
        branch_id=ctx["branch_id"],
        student_id=ctx["student_id"],
        room_id=ctx["room_id"],
        month="2026-10",
        teacher_user_id=ctx["teacher_id"],
        attendance_stats=stats,
        subject_ratings=ratings,
        strengths="المواظبة والمشاركة الفعالة",
        improvements="تحسين الخط",
        recommendations="القراءة اليومية",
        action="submit",
    )
    db.commit()
    assert submitted["status"] == "pending_approval"
    assert submitted["submitted_at"] is not None

    # 4. Modifying pending report must fail
    with pytest.raises(ValueError, match="لا يمكن تعديل التقرير"):
        monthly_reports_repo.upsert_monthly_report(
            conn=db,
            branch_id=ctx["branch_id"],
            student_id=ctx["student_id"],
            room_id=ctx["room_id"],
            month="2026-10",
            teacher_user_id=ctx["teacher_id"],
            attendance_stats=stats,
            subject_ratings=ratings,
            action="save_draft",
        )


def test_supervisor_review_and_revision_cycle(db):
    """Test requesting revision, teacher re-submission, and approval."""
    ctx = _setup_test_hierarchy(db)
    stats = {"total_days": 10, "present": 10, "absent": 0, "excused": 0, "late": 0, "attendance_pct": 100.0}

    # Create and submit report
    report = monthly_reports_repo.upsert_monthly_report(
        conn=db,
        branch_id=ctx["branch_id"],
        student_id=ctx["student_id"],
        room_id=ctx["room_id"],
        month="2026-10",
        teacher_user_id=ctx["teacher_id"],
        attendance_stats=stats,
        subject_ratings=[],
        action="submit",
    )
    db.commit()

    # 1. Supervisor requests revision
    revised = monthly_reports_repo.review_monthly_report(
        conn=db,
        report_id=report["id"],
        reviewer_user_id=ctx["supervisor_id"],
        action="request_revision",
        revision_notes="يرجى إضافة تقييم مادة القرآن والملاحظات الفردية",
    )
    db.commit()

    assert revised["status"] == "needs_revision"
    assert revised["revision_notes"] == "يرجى إضافة تقييم مادة القرآن والملاحظات الفردية"
    assert revised["reviewed_by_user_id"] == ctx["supervisor_id"]

    # 2. Teacher can now edit and re-submit
    resubmitted = monthly_reports_repo.upsert_monthly_report(
        conn=db,
        branch_id=ctx["branch_id"],
        student_id=ctx["student_id"],
        room_id=ctx["room_id"],
        month="2026-10",
        teacher_user_id=ctx["teacher_id"],
        attendance_stats=stats,
        subject_ratings=[{"subject": "القرآن الكريم", "rating": "متميز"}],
        strengths="تمت الإضافة",
        action="submit",
    )
    db.commit()

    assert resubmitted["status"] == "pending_approval"
    assert resubmitted["revision_notes"] is None

    # 3. Supervisor approves
    approved = monthly_reports_repo.review_monthly_report(
        conn=db,
        report_id=report["id"],
        reviewer_user_id=ctx["supervisor_id"],
        action="approve",
    )
    db.commit()

    assert approved["status"] == "approved"
    assert approved["reviewed_at"] is not None


def test_publish_and_anti_draft_guardian_invariants(db):
    """Verify report publication, batch publishing, and strict Anti-Draft visibility for guardians."""
    ctx = _setup_test_hierarchy(db)
    stats = {"total_days": 10, "present": 10, "absent": 0, "excused": 0, "late": 0, "attendance_pct": 100.0}

    # Report initially in draft
    report = monthly_reports_repo.upsert_monthly_report(
        conn=db,
        branch_id=ctx["branch_id"],
        student_id=ctx["student_id"],
        room_id=ctx["room_id"],
        month="2026-10",
        teacher_user_id=ctx["teacher_id"],
        attendance_stats=stats,
        subject_ratings=[],
        action="save_draft",
    )
    db.commit()

    # In draft: guardian must see 0 reports (Anti-Draft Invariant)
    guardian_reports = monthly_reports_repo.list_guardian_monthly_reports(db, [ctx["student_id"]])
    assert len(guardian_reports) == 0

    # Submit and Approve
    monthly_reports_repo.upsert_monthly_report(
        conn=db,
        branch_id=ctx["branch_id"],
        student_id=ctx["student_id"],
        room_id=ctx["room_id"],
        month="2026-10",
        teacher_user_id=ctx["teacher_id"],
        attendance_stats=stats,
        subject_ratings=[],
        action="submit",
    )
    monthly_reports_repo.review_monthly_report(
        conn=db,
        report_id=report["id"],
        reviewer_user_id=ctx["supervisor_id"],
        action="approve",
    )
    db.commit()

    # Still approved (not published): guardian must see 0 reports
    assert len(monthly_reports_repo.list_guardian_monthly_reports(db, [ctx["student_id"]])) == 0

    # Publish report
    published = monthly_reports_repo.publish_monthly_report(
        conn=db,
        report_id=report["id"],
        publisher_user_id=ctx["supervisor_id"],
    )
    db.commit()

    assert published["status"] == "published"
    assert published["published_at"] is not None

    # Now guardian sees the report
    g_reports = monthly_reports_repo.list_guardian_monthly_reports(db, [ctx["student_id"]])
    assert len(g_reports) == 1
    assert g_reports[0]["id"] == report["id"]
    assert g_reports[0]["student_name"] == "عمر أحمد"

    # Single guardian report fetch
    single_g = monthly_reports_repo.get_guardian_monthly_report_by_id(db, report["id"], [ctx["student_id"]])
    assert single_g is not None
    assert single_g["month"] == "2026-10"

    # Notification broadcast
    sent_count = monthly_reports_service.broadcast_monthly_report_notification(
        conn=db,
        report_id=report["id"],
        branch_id=ctx["branch_id"],
        student_id=ctx["student_id"],
        student_name="عمر أحمد",
        month="2026-10",
        sender_user_id=ctx["supervisor_id"],
    )
    db.commit()
    assert sent_count == 1

    notif = db.execute("SELECT * FROM notifications WHERE user_id = %s", (ctx["guardian_user_id"],)).fetchone()
    assert notif is not None
    assert notif["target_type"] == "monthly_report"
    assert "أكتوبر 2026" in notif["title"]


def test_batch_publish_monthly_reports(db):
    """Test batch publishing all approved reports for a room and month."""
    ctx = _setup_test_hierarchy(db)
    branch_id = ctx["branch_id"]
    room_id = ctx["room_id"]
    teacher_id = ctx["teacher_id"]
    supervisor_id = ctx["supervisor_id"]

    # Create second student in same room
    student2_id = db.execute(
        "INSERT INTO students (branch_id, room_id, name) VALUES (%s, %s, 'سارة خالد') RETURNING id",
        (branch_id, room_id)
    ).fetchone()["id"]
    db.commit()

    stats = {"total_days": 10, "present": 10, "absent": 0, "excused": 0, "late": 0, "attendance_pct": 100.0}

    # Student 1 report -> approved
    r1 = monthly_reports_repo.upsert_monthly_report(
        db, branch_id, ctx["student_id"], room_id, "2026-10", teacher_id, stats, [], action="submit"
    )
    monthly_reports_repo.review_monthly_report(db, r1["id"], supervisor_id, action="approve")

    # Student 2 report -> approved
    r2 = monthly_reports_repo.upsert_monthly_report(
        db, branch_id, student2_id, room_id, "2026-10", teacher_id, stats, [], action="submit"
    )
    monthly_reports_repo.review_monthly_report(db, r2["id"], supervisor_id, action="approve")
    db.commit()

    # Batch publish for room
    published_list = monthly_reports_repo.batch_publish_monthly_reports(db, room_id, "2026-10", supervisor_id)
    db.commit()

    assert len(published_list) == 2
    statuses = {p["status"] for p in published_list}
    assert statuses == {"published"}
