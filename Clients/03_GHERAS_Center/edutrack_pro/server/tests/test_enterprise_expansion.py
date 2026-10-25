"""Enterprise Expansion integration tests for Gheras Center suite.

Covers:
1. Educational Tracks CRUD & Dynamic Curriculum Assignment
2. Teacher Performance & KPI Evaluation (4 Criteria, Rating, Supervisor Notes)
3. Student Behavioral & Communication Audit Log
4. Room Hourly Occupancy Scheduling Grid
5. Room Bulk Student Allocation (/api/v1/rooms/{room_id}/bulk-enroll)
6. Supervisor Strict Financial Blackout Verification
7. Student Enrollment with Automated Initial Down-Payment
"""

from __future__ import annotations

import datetime
from tests.conftest import login, make_user

API = "/api/v1"


def test_educational_tracks_crud(db, client, manager):
    """Verify dynamic creation and listing of educational tracks."""
    res = client.post(
        f"{API}/educational-tracks",
        json={
            "name": "قسم القدرات العامة واللفظية",
            "code": "QUDURAT_VERBAL",
            "description": "تدريب مكثف على اختبار القدرات اللفظية",
            "is_active": True,
        },
        headers=manager,
    )
    assert res.status_code == 200, res.text
    track_id = res.json()["id"]
    assert res.json()["name"] == "قسم القدرات العامة واللفظية"

    listed = client.get(f"{API}/educational-tracks", headers=manager).json()
    assert any(t["id"] == track_id for t in listed["items"])


def test_supervisor_teacher_evaluation(db, client, manager):
    """Verify supervisor can record teacher performance evaluations with 4 quantitative criteria."""
    # 1. Create a staff member (teacher)
    staff_res = client.post(
        f"{API}/staff",
        json={"name": "الأستاذ عبد الله المنصور", "role_title": "معلم", "base_salary": 4500},
        headers=manager,
    )
    assert staff_res.status_code == 200, staff_res.text
    staff_id = staff_res.json()["id"]

    # 2. Create a supervisor user
    client.post(
        f"{API}/users",
        json={
            "username": "sup_evaluator",
            "password": "Password123!",
            "role": "supervisor",
            "permissions": {"students": True, "attendance": True, "daily_evaluation": True, "finance": False},
        },
        headers=manager,
    )
    sup_headers = login(client, "sup_evaluator", "Password123!")

    # 3. Supervisor records evaluation
    today = datetime.date.today().isoformat()
    eval_res = client.post(
        f"{API}/teacher-evaluations",
        json={
            "staff_id": staff_id,
            "period_type": "weekly",
            "evaluation_date": today,
            "interaction_score": 9.5,
            "guardian_comm_score": 8.5,
            "ethics_score": 10.0,
            "curriculum_score": 9.0,
            "rating": "ممتاز",
            "notes": "أداء نموذجي وتفاعل متميز داخل القاعة",
            "improvement_plan": "الاستمرار بنفس مستوى التحفيز والتواصل",
        },
        headers=sup_headers,
    )
    assert eval_res.status_code == 200, eval_res.text
    ev_data = eval_res.json()
    assert ev_data["teacher_id"] == staff_id
    assert float(ev_data["interaction_score"]) == 9.5
    assert ev_data["rating"] == "ممتاز"

    # 4. Query evaluations
    eval_list = client.get(f"{API}/teacher-evaluations?teacher_id={staff_id}", headers=sup_headers).json()
    assert eval_list["total"] >= 1
    assert eval_list["items"][0]["supervisor_notes"] == "أداء نموذجي وتفاعل متميز داخل القاعة"


def test_student_behavioral_logs(db, client, manager):
    """Verify recording student behavioral and communication incidents."""
    st_res = client.post(
        f"{API}/students",
        json={"name": "فهد عبد العزيز", "father_phone": "0555123456", "group_name": "الصباح"},
        headers=manager,
    )
    assert st_res.status_code == 200, st_res.text
    student_id = st_res.json()["id"]

    log_res = client.post(
        f"{API}/student-behavior-logs",
        json={
            "student_id": student_id,
            "type": "positive",
            "title": "مشاركة متميزة في الإلقاء",
            "note": "أظهر تميزاً وإتقاناً في تلاوة سورة الملك أمام الزملاء",
            "points": 5,
            "guardian_notified": True,
        },
        headers=manager,
    )
    assert log_res.status_code == 200, log_res.text
    assert log_res.json()["points"] == 5
    assert log_res.json()["log_type"] == "positive"


def test_room_bulk_enroll(db, client, manager):
    """Verify bulk assigning multiple students to a room in one operation."""
    # Create room
    room = client.post(f"{API}/rooms", json={"name": "قاعة الفاروق 3", "group_name": "الصباح"}, headers=manager).json()
    room_id = room["id"]

    # Create 3 students without room
    s1 = client.post(f"{API}/students", json={"name": "طالب 1", "father_phone": "0500000001"}, headers=manager).json()["id"]
    s2 = client.post(f"{API}/students", json={"name": "طالب 2", "father_phone": "0500000002"}, headers=manager).json()["id"]
    s3 = client.post(f"{API}/students", json={"name": "طالب 3", "father_phone": "0500000003"}, headers=manager).json()["id"]

    # Bulk enroll
    bulk_res = client.post(
        f"{API}/rooms/{room_id}/bulk-enroll",
        json={"student_ids": [s1, s2, s3]},
        headers=manager,
    )
    assert bulk_res.status_code == 200, bulk_res.text
    data = bulk_res.json()
    assert data["enrolled_count"] == 3
    assert data["room_id"] == room_id

    # Verify each student now belongs to room_id
    for sid in [s1, s2, s3]:
        student = client.get(f"{API}/students/{sid}", headers=manager).json()
        assert student["room_id"] == room_id


def test_supervisor_strict_financial_blackout(db, client, manager):
    """Verify supervisor with finance=False is strictly forbidden from financial endpoints."""
    client.post(
        f"{API}/users",
        json={
            "username": "sup_academic_only",
            "password": "Password123!",
            "role": "supervisor",
            "permissions": {"students": True, "attendance": True, "daily_evaluation": True, "finance": False},
        },
        headers=manager,
    )
    sup_headers = login(client, "sup_academic_only", "Password123!")

    # Must be 403 Forbidden across general financial tables
    assert client.get(f"{API}/expenses", headers=sup_headers).status_code == 403
    assert client.get(f"{API}/expense-categories", headers=sup_headers).status_code == 403
    assert client.get(f"{API}/ledger-accounts", headers=sup_headers).status_code == 403
    assert client.get(f"{API}/ledger-entries", headers=sup_headers).status_code == 403
    assert client.get(f"{API}/payroll-runs", headers=sup_headers).status_code == 403

    # Decoupled student fees: supervisor with students permission CAN read payments and receipts
    assert client.get(f"{API}/payments", headers=sup_headers).status_code == 200
    assert client.get(f"{API}/receipts", headers=sup_headers).status_code == 200
    # But cannot modify or delete them
    assert client.delete(f"{API}/payments/00000000-0000-0000-0000-000000000001", headers=sup_headers).status_code == 403
    assert client.delete(f"{API}/receipts/00000000-0000-0000-0000-000000000001", headers=sup_headers).status_code == 403

    # Decoupled: supervisor with students permission CAN enroll a student carrying fee/payment
    enroll_res = client.post(
        f"{API}/students",
        json={"name": "طالب مسموح مالياً", "father_phone": "0577700001", "registration_fee": 500, "initial_payment_amount": 500},
        headers=sup_headers,
    )
    assert enroll_res.status_code == 200

    # Regression 2: supervisor cannot bulk-enroll students into rooms
    room = client.post(f"{API}/rooms", json={"name": "قاعة الحظر", "group_name": "الصباح"}, headers=manager).json()
    assert client.post(
        f"{API}/rooms/{room['id']}/bulk-enroll",
        json={"student_ids": ["00000000-0000-0000-0000-0000000000aa"]},
        headers=sup_headers,
    ).status_code == 403


def test_student_enrollment_with_quick_down_payment(db, client, manager):
    """Verify student registration with initial payment creates payment and receipt automatically."""
    # Create student with initial payment
    st_res = client.post(
        f"{API}/students",
        json={
            "name": "يوسف محمد",
            "father_phone": "0512345678",
            "registration_fee": 500,
            "initial_payment_amount": 500,
            "payment_method": "مدى",
        },
        headers=manager,
    )
    assert st_res.status_code == 200, st_res.text
    student_id = st_res.json()["id"]

    # Attach a fee plan to the created student
    plan = client.post(
        f"{API}/fee-plans",
        json={
            "student_id": student_id,
            "total_amount": 1500,
            "count": 3,
            "start_date": datetime.date.today().isoformat(),
            "interval_days": 30,
        },
        headers=manager,
    )
    assert plan.status_code == 200, plan.text

    # Verify payment record was created
    payments = client.get(f"{API}/payments?student_id={student_id}", headers=manager).json()
    assert payments["total"] == 1
    assert float(payments["items"][0]["amount"]) == 500.0
    assert payments["items"][0]["method"] == "مدى"

    # Verify receipt record was created (receipts link to payments via payment_id)
    payment_id = payments["items"][0]["id"]
    receipts = client.get(f"{API}/receipts?payment_id={payment_id}", headers=manager).json()
    assert receipts["total"] == 1
    assert receipts["items"][0]["payment_id"] == payment_id
