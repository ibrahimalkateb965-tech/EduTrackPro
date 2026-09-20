"""End-to-end API tests over a real PostgreSQL (reviewer-only)."""

from __future__ import annotations

from datetime import date

from tests.conftest import login, make_user

API = "/api/v1"


def test_health(client):
    res = client.get(f"{API}/health")
    assert res.status_code == 200
    assert res.json()["status"] == "ok"


def test_login_me_logout_cycle(db, client):
    make_user(db, "m", "manager")
    res = client.post(f"{API}/auth/login", json={"username": "m", "password": "wrong"})
    assert res.status_code == 401
    assert res.json()["error"]["code"] == "unauthorized"
    headers = login(client, "m")
    me = client.get(f"{API}/me", headers=headers)
    assert me.status_code == 200
    body = me.json()
    assert body["role"] == "manager" and body["username"] == "m"
    assert set(body["permissions"]) == {"attendance", "daily_evaluation", "monthly_evaluation", "students", "finance"}
    assert "password_hash" not in body
    assert client.post(f"{API}/auth/logout", headers=headers).status_code == 204
    assert client.get(f"{API}/me", headers=headers).status_code == 401
    assert client.get(f"{API}/students").status_code == 401


def test_placeholder_admin_hash_never_logs_in(db, client):
    db.execute("INSERT INTO users (username, password_hash, role) VALUES ('admin', '$argon2id$REPLACE_ON_FIRST_RUN', 'manager')")
    db.commit()
    res = client.post(f"{API}/auth/login", json={"username": "admin", "password": "anything"})
    assert res.status_code == 401


def test_students_crud_filters_and_audit(db, client, manager):
    room = client.post(f"{API}/rooms", json={"name": "حلقة 1", "group_name": "الصباح"}, headers=manager)
    assert room.status_code == 200, room.text
    room_id = room.json()["id"]
    created = client.post(
        f"{API}/students",
        json={"name": "أحمد", "father_phone": "0500000000", "room_id": room_id, "group_name": "الصباح", "has_difficulties": False},
        headers=manager,
    )
    assert created.status_code == 200, created.text
    sid = created.json()["id"]
    client.post(f"{API}/students", json={"name": "سارة", "group_name": "المساء", "status": "archived"}, headers=manager)

    listed = client.get(f"{API}/students", headers=manager).json()
    assert listed["total"] == 2 and len(listed["items"]) == 2
    assert client.get(f"{API}/students?status=active", headers=manager).json()["total"] == 1
    assert client.get(f"{API}/students?room_id={room_id}", headers=manager).json()["items"][0]["id"] == sid
    assert client.get(f"{API}/students?q=سار", headers=manager).json()["total"] == 1
    assert client.get(f"{API}/students?nope=1", headers=manager).status_code == 422
    assert client.get(f"{API}/students?limit=0", headers=manager).status_code == 422

    patched = client.patch(f"{API}/students/{sid}", json={"name": "أحمد علي", "id": "ignored"}, headers=manager)
    assert patched.status_code == 200 and patched.json()["name"] == "أحمد علي"
    assert client.get(f"{API}/students/{sid}", headers=manager).json()["name"] == "أحمد علي"

    bad = client.post(f"{API}/students", json={"name": "x", "status": "flying"}, headers=manager)
    assert bad.status_code == 422 and bad.json()["error"]["code"] == "validation_error"

    assert client.delete(f"{API}/students/{sid}", headers=manager).status_code == 204
    assert client.get(f"{API}/students/{sid}", headers=manager).status_code == 404
    assert client.delete(f"{API}/students/{sid}", headers=manager).status_code == 404
    assert db.execute("SELECT deleted_at FROM students WHERE id=%s", (sid,)).fetchone()["deleted_at"] is not None

    audit = client.get(f"{API}/audit-log?entity=students", headers=manager).json()
    actions = sorted(r["action"] for r in audit["items"])
    assert actions == ["create", "create", "delete", "update"]
    assert audit["items"][0]["actor_name"] == "manager1"


def test_users_hook_hashes_password_and_hides_hash(db, client, manager):
    res = client.post(
        f"{API}/users",
        json={"username": "t1", "password": "Teacher123", "role": "teacher", "permissions": {"attendance": True}},
        headers=manager,
    )
    assert res.status_code == 200, res.text
    body = res.json()
    assert "password_hash" not in body and "password" not in body
    row = db.execute("SELECT password_hash FROM users WHERE username='t1'").fetchone()
    assert row["password_hash"].startswith("$argon2id$")
    perms = db.execute("SELECT attendance, finance FROM user_permissions WHERE user_id=%s", (body["id"],)).fetchone()
    assert perms["attendance"] is True and perms["finance"] is False
    audit = db.execute("SELECT details_json FROM audit_log WHERE entity='users'").fetchone()["details_json"]
    assert "password" not in audit and "password_hash" not in audit
    listed = client.get(f"{API}/users", headers=manager).json()
    assert all("password_hash" not in u for u in listed["items"])
    dup = client.post(f"{API}/users", json={"username": "t1", "password": "x", "role": "teacher"}, headers=manager)
    assert dup.status_code == 409 and dup.json()["error"]["code"] == "conflict"
    # the new teacher can log in but has no web access
    teacher = login(client, "t1", "Teacher123")
    assert client.get(f"{API}/students", headers=teacher).status_code == 403


def test_role_matrix_for_supervisor(client, supervisor):
    assert client.get(f"{API}/users", headers=supervisor).status_code == 200
    res = client.post(f"{API}/users", json={"username": "z", "password": "p", "role": "teacher"}, headers=supervisor)
    assert res.status_code == 403 and res.json()["error"]["code"] == "forbidden"
    # Rooms/staff writes are manager-only since the strict RBAC pass (a82c071); supervisors may only read them.
    rooms_res = client.post(f"{API}/rooms", json={"name": "ح", "group_name": "المساء"}, headers=supervisor)
    assert rooms_res.status_code == 403 and rooms_res.json()["error"]["code"] == "forbidden"


def test_finance_flow_fee_plan_payment_receipt_ledger_reports(db, client, manager):
    student = client.post(f"{API}/students", json={"name": "خالد", "guardian_phone": "0511111111"}, headers=manager).json()
    plan = client.post(
        f"{API}/fee-plans",
        json={"student_id": student["id"], "total_amount": 1000, "count": 3, "start_date": "2026-09-01", "interval_days": 30},
        headers=manager,
    )
    assert plan.status_code == 200, plan.text
    inst = plan.json()["installments"]
    assert [i["seq_no"] for i in inst] == [1, 2, 3]
    assert [i["due_date"] for i in inst] == ["2026-09-01", "2026-10-01", "2026-10-31"]
    assert [i["amount"] for i in inst] == [333.33, 333.33, 333.34]

    today = date.today().isoformat()
    pay = client.post(
        f"{API}/payments",
        json={"student_id": student["id"], "amount": 500, "method": "كاش", "paid_on": today},
        headers=manager,
    )
    assert pay.status_code == 200, pay.text
    body = pay.json()
    assert body["receipt"]["receipt_no"] == 1
    touched = body["installments_touched"]
    assert [t["status"] for t in touched] == ["paid", "partial"]
    assert touched[1]["paid_amount"] == 166.67
    assert body["installment_id"] == touched[0]["id"]

    accounts = client.get(f"{API}/ledger-accounts", headers=manager).json()["items"]
    assert len(accounts) == 1 and accounts[0]["name"] == "الصندوق"
    entries = client.get(f"{API}/ledger-entries", headers=manager).json()["items"]
    assert entries[0]["entry_type"] == "in" and entries[0]["amount"] == 500 and entries[0]["ref_table"] == "payments"

    exp = client.post(
        f"{API}/expenses",
        json={"description": "فاتورة كهرباء", "category": "كهرباء", "amount": 120.5, "paid_on": today, "method": "كاش"},
        headers=manager,
    )
    assert exp.status_code == 200, exp.text
    assert exp.json()["category_id"]
    new_cat = client.post(
        f"{API}/expenses",
        json={"description": "x", "category": "فئة جديدة", "amount": 1, "paid_on": today},
        headers=manager,
    )
    assert new_cat.status_code == 200
    assert db.execute("SELECT count(*) AS n FROM expense_categories WHERE name='فئة جديدة'").fetchone()["n"] == 1
    assert client.get(f"{API}/ledger-entries?entry_type=out", headers=manager).json()["total"] == 2

    fin = client.get(f"{API}/reports/finance?from={today}&to={today}", headers=manager).json()
    assert fin["collected"] == 500 and fin["expenses"] == 121.5 and fin["net"] == 378.5
    assert fin["outstanding_total"] == 500
    assert fin["outstanding_by_student"][0]["student_id"] == student["id"]
    assert fin["outstanding_by_student"][0]["outstanding"] == 500

    daily = client.get(f"{API}/reports/daily", headers=manager).json()
    assert daily["students_count"] == 1 and daily["collected_today"] == 500 and daily["outstanding_total"] == 500
    assert daily["expenses_month"] == 121.5 and daily["absences"] == []

    printed = client.get(f"{API}/print/receipt/{body['id']}", headers=manager).json()
    assert printed["template"] == "receipt" and printed["receipt_no"] == 1
    assert printed["amount"] == 500 and printed["remaining_balance"] == 500
    assert printed["amount_words"] == "خمسمائة ريال فقط لا غير"
    assert client.get(f"{API}/print/receipt/{student['id']}", headers=manager).status_code == 404

    month = today[:7]
    closed = client.post(f"{API}/month-closures", json={"month": month}, headers=manager)
    assert closed.status_code == 200, closed.text
    assert closed.json()["totals_json"]["collected"] == 500
    assert client.post(f"{API}/month-closures", json={"month": month}, headers=manager).status_code == 409


def test_attendance_bulk_upsert_and_reports(db, client, manager):
    s1 = client.post(f"{API}/students", json={"name": "نورة", "guardian_phone": "0522222222"}, headers=manager).json()
    s2 = client.post(f"{API}/students", json={"name": "ريم"}, headers=manager).json()
    day = "2026-09-15"
    rows = [
        {"student_id": s1["id"], "date": day, "status": "غائب", "note": "مرض"},
        {"student_id": s2["id"], "date": day, "status": "حاضر"},
    ]
    res = client.post(f"{API}/attendance/students", json=rows, headers=manager)
    assert res.status_code == 200, res.text
    assert res.json()["saved"] == 2
    rows[0]["status"] = "متأخر"
    assert client.post(f"{API}/attendance/students", json=rows, headers=manager).json()["saved"] == 2
    assert db.execute("SELECT count(*) AS n FROM student_attendance").fetchone()["n"] == 2
    bad = client.post(f"{API}/attendance/students", json=[{"student_id": s1["id"], "date": day, "status": "طائر"}], headers=manager)
    assert bad.status_code == 422

    rep = client.get(f"{API}/reports/attendance?from={day}&to={day}", headers=manager).json()
    assert rep["summary"] == {"present": 1, "absent": 0, "late": 1, "excused": 0}
    assert {r["student_id"] for r in rep["items"]} == {s1["id"], s2["id"]}
    assert client.get(f"{API}/reports/attendance?from={day}&to={day}&room_id={s1['id']}", headers=manager).json()["items"] == []

    client.post(f"{API}/attendance/students", json=[{"student_id": s1["id"], "date": day, "status": "غائب"}], headers=manager)
    daily = client.get(f"{API}/reports/daily?date={day}", headers=manager).json()
    assert daily["absent_today"] == 1 and daily["present_today"] == 1
    assert daily["absences"][0]["name"] == "نورة" and daily["absences"][0]["guardian_phone"] == "0522222222"

    monthly = client.get(f"{API}/reports/monthly?month=2026-09", headers=manager).json()
    assert monthly["attendance"]["absent"] == 1 and monthly["top_absent"][0]["absent_days"] == 1
    assert client.get(f"{API}/reports/monthly?month=2026-09", headers=manager).status_code == 200
    assert db.execute("SELECT count(*) AS n FROM monthly_reports WHERE month='2026-09'").fetchone()["n"] == 1
    assert client.get(f"{API}/reports/monthly?month=2026-13", headers=manager).status_code == 422

    student_report = client.get(f"{API}/reports/student/{s1['id']}", headers=manager).json()
    assert student_report["attendance_summary"]["absent"] == 1 and student_report["balance"]["outstanding"] == 0
    assert client.get(f"{API}/reports/student/{s1['id']}x", headers=manager).status_code == 422


def test_print_endpoints_return_template_keys(db, client, manager):
    student = client.post(f"{API}/students", json={"name": "ليان", "group_name": "الصباح"}, headers=manager).json()
    card = client.get(f"{API}/print/guardian-card/{student['id']}", headers=manager).json()
    assert card["template"] == "guardian_card" and card["student_name"] == "ليان"
    cert = client.post(f"{API}/certificates", json={"student_id": student["id"], "title": "تميز", "issued_on": "2026-09-01"}, headers=manager).json()
    printed = client.get(f"{API}/print/certificate/{cert['id']}", headers=manager).json()
    assert printed["template"] == "excellence_certificate" and printed["student_name"] == "ليان"
    report = client.get(f"{API}/print/student-report/{student['id']}", headers=manager).json()
    assert report["template"] == "student_report"
    stats = client.get(f"{API}/print/statistics?from=2026-09-01&to=2026-09-30", headers=manager).json()
    assert stats["template"] == "statistics_report"
    admin = client.get(f"{API}/print/admin-report?from=2026-09-01&to=2026-09-30", headers=manager).json()
    assert admin["template"] == "admin_report"
    monthly = client.get(f"{API}/print/monthly-report?month=2026-09", headers=manager).json()
    assert monthly["template"] == "monthly_report"
    receipt = client.get(f"{API}/print/student-receipt/{student['id']}", headers=manager).json()
    assert receipt["template"] == "student_receipt"
    att = client.get(f"{API}/print/attendance-report?from=2026-09-01&to=2026-09-02", headers=manager).json()
    assert att["template"] == "attendance_report"


def test_payroll_advance_deduction(db, client, manager):
    staff = client.post(f"{API}/staff", json={"name": "موظف", "role_title": "معلم", "base_salary": 3000}, headers=manager).json()
    adv = client.post(f"{API}/staff-advances", json={"staff_id": staff["id"], "amount": 500, "date": "2026-09-01"}, headers=manager)
    assert adv.status_code == 200, adv.text
    run = client.post(
        f"{API}/payroll-runs",
        json={"staff_id": staff["id"], "month": "2026-09", "base": 3000, "advance_deducted": 800},
        headers=manager,
    )
    assert run.status_code == 200, run.text
    assert run.json()["advance_deducted"] == 500 and run.json()["net"] == 2500
    assert db.execute("SELECT settled FROM staff_advances WHERE id=%s", (adv.json()["id"],)).fetchone()["settled"] is True
    dup = client.post(f"{API}/payroll-runs", json={"staff_id": staff["id"], "month": "2026-09", "base": 1}, headers=manager)
    assert dup.status_code == 409


def test_error_envelope_for_unknown_route_and_bad_json(client, manager):
    res = client.get(f"{API}/nope", headers=manager)
    assert res.status_code == 404 and res.json()["error"]["code"] == "not_found"
    res = client.post(f"{API}/auth/login", json={"username": "x"})
    assert res.status_code == 422 and res.json()["error"]["code"] == "validation_error"


def test_ledger_transfer_posts_matched_pair(db, client, manager):
    cash = client.post(f"{API}/ledger-accounts", json={"name": "الصندوق", "kind": "cash", "opening_balance": 1000}, headers=manager).json()
    bank = client.post(f"{API}/ledger-accounts", json={"name": "البنك", "kind": "bank", "opening_balance": 0}, headers=manager).json()
    res = client.post(
        f"{API}/ledger-entries",
        json={"from_account_id": cash["id"], "to_account_id": bank["id"], "amount": 250, "occurred_on": "2026-09-10", "note": "إيداع"},
        headers=manager,
    )
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["entry_type"] == "transfer_out" and body["counterpart"]["entry_type"] == "transfer_in"
    assert body["ref_id"] == body["counterpart"]["ref_id"]
    entries = client.get(f"{API}/ledger-entries?ref_table=transfer", headers=manager).json()
    assert entries["total"] == 2
    same = client.post(f"{API}/ledger-entries", json={"from_account_id": cash["id"], "to_account_id": cash["id"], "amount": 1}, headers=manager)
    assert same.status_code == 422
    plain = client.post(f"{API}/ledger-entries", json={"account_id": bank["id"], "entry_type": "in", "amount": 10, "occurred_on": "2026-09-10"}, headers=manager)
    assert plain.status_code == 200 and plain.json()["entry_type"] == "in"
