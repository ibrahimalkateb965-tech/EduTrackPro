"""Tests for supervisor permissions, RBAC guards, and daily evaluations."""

from __future__ import annotations

import datetime

from tests.conftest import login, make_user


def test_manager_creates_supervisor_with_permissions(client, manager):
    payload = {
        "username": "sup_academic",
        "password": "Password123!",
        "role": "supervisor",
        "permissions": {
            "students": True,
            "attendance": True,
            "daily_evaluation": True,
            "monthly_evaluation": False,
            "finance": False,
        },
    }
    res = client.post("/api/v1/users", json=payload, headers=manager)
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["username"] == "sup_academic"
    assert data["role"] == "supervisor"
    assert data["permissions"]["students"] is True
    assert data["permissions"]["attendance"] is True
    assert data["permissions"]["daily_evaluation"] is True
    assert data["permissions"]["monthly_evaluation"] is False
    assert data["permissions"]["finance"] is False


def test_supervisor_me_endpoint_returns_permissions(client, manager):
    client.post(
        "/api/v1/users",
        json={
            "username": "sup_me",
            "password": "Password123!",
            "role": "supervisor",
            "permissions": {
                "students": True,
                "attendance": False,
                "daily_evaluation": True,
                "monthly_evaluation": False,
                "finance": False,
            },
        },
        headers=manager,
    )
    sup_headers = login(client, "sup_me", "Password123!")
    res = client.get("/api/v1/me", headers=sup_headers)
    assert res.status_code == 200, res.text
    me = res.json()
    assert me["role"] == "supervisor"
    assert me["permissions"]["students"] is True
    assert me["permissions"]["attendance"] is False
    assert me["permissions"]["daily_evaluation"] is True
    assert me["permissions"]["monthly_evaluation"] is False
    assert me["permissions"]["finance"] is False


def test_supervisor_read_users_allowed_but_write_forbidden(client, manager):
    client.post(
        "/api/v1/users",
        json={
            "username": "sup_reader",
            "password": "Password123!",
            "role": "supervisor",
            "permissions": {"students": True, "attendance": True},
        },
        headers=manager,
    )
    sup_headers = login(client, "sup_reader", "Password123!")

    # Supervisor CAN read users list (reconciles test_api.py:106)
    get_res = client.get("/api/v1/users", headers=sup_headers)
    assert get_res.status_code == 200, get_res.text

    # Supervisor CANNOT create user
    post_res = client.post(
        "/api/v1/users",
        json={"username": "hacker", "password": "Password123!", "role": "manager"},
        headers=sup_headers,
    )
    assert post_res.status_code == 403
    assert post_res.json()["error"]["code"] == "forbidden"

    # Supervisor CANNOT read audit log
    audit_res = client.get("/api/v1/audit-log", headers=sup_headers)
    assert audit_res.status_code == 403


def test_supervisor_student_permission_negative(client, manager):
    client.post(
        "/api/v1/users",
        json={
            "username": "sup_nostudent",
            "password": "Password123!",
            "role": "supervisor",
            "permissions": {"students": False, "attendance": True},
        },
        headers=manager,
    )
    sup_headers = login(client, "sup_nostudent", "Password123!")

    # Blocked from student list and create
    assert client.get("/api/v1/students", headers=sup_headers).status_code == 403
    assert client.post("/api/v1/students", json={"name": "طالب"}, headers=sup_headers).status_code == 403

    # Blocked from student print templates
    assert client.get("/api/v1/print/guardian-card/00000000-0000-0000-0000-000000000001", headers=sup_headers).status_code == 403
    assert client.get("/api/v1/print/student-report/00000000-0000-0000-0000-000000000001", headers=sup_headers).status_code == 403
    assert client.get("/api/v1/print/certificate?student_id=00000000-0000-0000-0000-000000000001", headers=sup_headers).status_code == 403


def test_supervisor_attendance_permission_negative(client, manager):
    client.post(
        "/api/v1/users",
        json={
            "username": "sup_noatt",
            "password": "Password123!",
            "role": "supervisor",
            "permissions": {"students": True, "attendance": False},
        },
        headers=manager,
    )
    sup_headers = login(client, "sup_noatt", "Password123!")

    today = datetime.date.today().isoformat()
    assert client.post("/api/v1/attendance/students", json=[], headers=sup_headers).status_code == 403
    assert client.post("/api/v1/attendance/staff", json=[], headers=sup_headers).status_code == 403
    assert client.get("/api/v1/reports/attendance?from=2026-09-01&to=2026-09-02", headers=sup_headers).status_code == 403
    assert client.get(f"/api/v1/print/attendance-report?from={today}&to={today}", headers=sup_headers).status_code == 403
    assert client.get(f"/api/v1/print/lesson-log?room=00000000-0000-0000-0000-000000000001&from={today}&to={today}", headers=sup_headers).status_code == 403


def test_supervisor_evaluation_granularity(client, manager):
    # Manager creates student
    st = client.post(
        "/api/v1/students",
        json={"name": "يوسف الصديق", "guardian_phone": "0599991122", "gender": "بنين"},
        headers=manager,
    ).json()
    student_id = st["id"]

    # Supervisor A: daily_evaluation ONLY
    client.post(
        "/api/v1/users",
        json={
            "username": "sup_daily_only",
            "password": "Password123!",
            "role": "supervisor",
            "permissions": {"daily_evaluation": True, "monthly_evaluation": False, "students": True},
        },
        headers=manager,
    )
    sup_a = login(client, "sup_daily_only", "Password123!")
    today = datetime.date.today().isoformat()

    # Sup A can record daily evaluation
    daily_post = client.post(
        "/api/v1/evaluations",
        json={"student_id": student_id, "subject": "القرآن", "eval_type": "daily", "date": today, "value": 10},
        headers=sup_a,
    )
    assert daily_post.status_code == 200, daily_post.text

    # Sup A CANNOT record monthly evaluation
    monthly_post = client.post(
        "/api/v1/evaluations",
        json={"student_id": student_id, "subject": "القرآن", "eval_type": "monthly", "date": today, "value": 90},
        headers=sup_a,
    )
    assert monthly_post.status_code == 403

    # Sup A filtered query for monthly returns 403
    assert client.get("/api/v1/evaluations?eval_type=monthly", headers=sup_a).status_code == 403

    # Supervisor B: monthly_evaluation ONLY
    client.post(
        "/api/v1/users",
        json={
            "username": "sup_monthly_only",
            "password": "Password123!",
            "role": "supervisor",
            "permissions": {"daily_evaluation": False, "monthly_evaluation": True, "students": True},
        },
        headers=manager,
    )
    sup_b = login(client, "sup_monthly_only", "Password123!")

    # Sup B can record monthly evaluation
    monthly_b = client.post(
        "/api/v1/evaluations",
        json={"student_id": student_id, "subject": "القرآن", "eval_type": "monthly", "date": today, "value": 95},
        headers=sup_b,
    )
    assert monthly_b.status_code == 200, monthly_b.text

    # Sup B CANNOT record daily evaluation
    daily_b = client.post(
        "/api/v1/evaluations",
        json={"student_id": student_id, "subject": "القرآن", "eval_type": "daily", "date": today, "value": 8},
        headers=sup_b,
    )
    assert daily_b.status_code == 403

    # Sup B blocked on daily evaluation endpoints
    assert client.get(f"/api/v1/evaluations/daily?date={today}", headers=sup_b).status_code == 403
    assert client.post("/api/v1/evaluations/daily", json=[], headers=sup_b).status_code == 403


def test_supervisor_staff_salary_masking_and_write_guard(client, manager):
    # Manager creates staff member with salary
    staff_res = client.post(
        "/api/v1/staff",
        json={"name": "أحمد المعلم", "role_title": "معلم قرآن", "base_salary": 4500, "phone": "0551234567"},
        headers=manager,
    )
    assert staff_res.status_code == 200, staff_res.text
    staff_id = staff_res.json()["id"]

    # Supervisor without finance
    client.post(
        "/api/v1/users",
        json={
            "username": "sup_staff_nofin",
            "password": "Password123!",
            "role": "supervisor",
            "permissions": {"students": True, "attendance": True, "finance": False},
        },
        headers=manager,
    )
    sup = login(client, "sup_staff_nofin", "Password123!")

    # Staff list masks base_salary to None
    list_res = client.get("/api/v1/staff", headers=sup)
    assert list_res.status_code == 200
    item = next(s for s in list_res.json()["items"] if s["id"] == staff_id)
    assert item["base_salary"] is None

    # Single item get masks base_salary to None
    get_res = client.get(f"/api/v1/staff/{staff_id}", headers=sup)
    assert get_res.status_code == 200
    assert get_res.json()["base_salary"] is None

    # Attempt to update base_salary returns 403
    patch_salary = client.patch(f"/api/v1/staff/{staff_id}", json={"base_salary": 5000}, headers=sup)
    assert patch_salary.status_code == 403
    assert patch_salary.json()["error"]["code"] == "forbidden"

    # Updating other fields like phone succeeds
    patch_phone = client.patch(f"/api/v1/staff/{staff_id}", json={"phone": "0559998877"}, headers=sup)
    assert patch_phone.status_code == 200
    assert patch_phone.json()["phone"] == "0559998877"


def test_supervisor_finance_guard_strict(client, manager):
    client.post(
        "/api/v1/users",
        json={
            "username": "sup_nofinance",
            "password": "Password123!",
            "role": "supervisor",
            "permissions": {
                "students": True,
                "attendance": True,
                "daily_evaluation": True,
                "finance": False,
            },
        },
        headers=manager,
    )
    sup_headers = login(client, "sup_nofinance", "Password123!")

    # 403 on financial CRUD resources
    assert client.get("/api/v1/payments", headers=sup_headers).status_code == 403
    assert client.get("/api/v1/expenses", headers=sup_headers).status_code == 403
    assert client.get("/api/v1/ledger-accounts", headers=sup_headers).status_code == 403
    assert client.get("/api/v1/payroll-runs", headers=sup_headers).status_code == 403

    # 403 on financial report
    assert client.get("/api/v1/reports/finance", headers=sup_headers).status_code == 403

    # 403 on financial print endpoints
    assert client.get("/api/v1/print/receipt?payment_id=00000000-0000-0000-0000-000000000001", headers=sup_headers).status_code == 403
    assert client.get("/api/v1/print/student-receipt/00000000-0000-0000-0000-000000000001", headers=sup_headers).status_code == 403
    assert client.get("/api/v1/print/admin-report?from=2026-09-01&to=2026-09-30", headers=sup_headers).status_code == 403
    assert client.get("/api/v1/print/monthly-report?month=2026-09", headers=sup_headers).status_code == 403

    # Statistics print endpoint returns 200 with masked finance figures
    stats = client.get("/api/v1/print/statistics?from=2026-09-01&to=2026-09-30", headers=sup_headers).json()
    assert stats["collection_pct"] == 0
    assert stats["outstanding"] == 0

    # Daily report succeeds but masks finance
    daily = client.get("/api/v1/reports/daily", headers=sup_headers).json()
    assert daily["collected_today"] == 0
    assert daily["outstanding_total"] == 0
    assert daily["expenses_month"] == 0

    # Monthly report succeeds but masks finance
    monthly = client.get("/api/v1/reports/monthly?month=2026-09", headers=sup_headers).json()
    assert monthly["finance"] == {"collected": 0, "expenses": 0, "payroll": 0, "net": 0}
    assert "attendance" in monthly


def test_supervisor_attendance_and_daily_evaluation(client, manager):
    # Manager creates student
    st_res = client.post(
        "/api/v1/students",
        json={"name": "خالد بن الوليد", "guardian_phone": "0501112233", "group_name": "الإنجليزي", "gender": "بنين"},
        headers=manager,
    )
    assert st_res.status_code == 200, st_res.text
    student_id = st_res.json()["id"]

    # Manager creates supervisor with attendance & daily_evaluation
    client.post(
        "/api/v1/users",
        json={
            "username": "sup_eval",
            "password": "Password123!",
            "role": "supervisor",
            "permissions": {
                "students": True,
                "attendance": True,
                "daily_evaluation": True,
                "finance": False,
            },
        },
        headers=manager,
    )
    sup_headers = login(client, "sup_eval", "Password123!")

    today = datetime.date.today().isoformat()

    # Record attendance
    att_res = client.post(
        "/api/v1/attendance/students",
        json=[{"student_id": student_id, "date": today, "status": "حاضر", "note": "ممتاز"}],
        headers=sup_headers,
    )
    assert att_res.status_code == 200, att_res.text

    # Record daily evaluation
    eval_res = client.post(
        "/api/v1/evaluations/daily",
        json=[{
            "student_id": student_id,
            "date": today,
            "subject": "اللغة الإنجليزية",
            "value": 9.5,
        }],
        headers=sup_headers,
    )
    assert eval_res.status_code == 200, eval_res.text
    assert eval_res.json()["saved"] == 1

    # Fetch daily evaluations
    list_res = client.get(f"/api/v1/evaluations/daily?date={today}", headers=sup_headers)
    assert list_res.status_code == 200, list_res.text
    items = list_res.json()["items"]
    assert len(items) >= 1
    ev = items[0]
    assert ev["student_id"] == student_id
    assert float(ev["value"]) == 9.5
    assert ev["subject"] == "اللغة الإنجليزية"


def test_manager_patches_supervisor_permissions(client, manager):
    # Create supervisor without finance
    res = client.post(
        "/api/v1/users",
        json={
            "username": "sup_promote",
            "password": "Password123!",
            "role": "supervisor",
            "permissions": {"students": True, "finance": False},
        },
        headers=manager,
    )
    user_id = res.json()["id"]

    sup_headers = login(client, "sup_promote", "Password123!")
    assert client.get("/api/v1/payments", headers=sup_headers).status_code == 403

    # Manager promotes supervisor to have finance
    patch_res = client.patch(
        f"/api/v1/users/{user_id}",
        json={"permissions": {"students": True, "finance": True}},
        headers=manager,
    )
    assert patch_res.status_code == 200, patch_res.text
    assert patch_res.json()["permissions"]["finance"] is True

    # Now supervisor can query payments
    assert client.get("/api/v1/payments", headers=sup_headers).status_code == 200
