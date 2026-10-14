"""Registration fee -> automatic installment plan (fleet order 02).

Paid == fee: fully paid single installment. Paid < fee: remainder split into the
agreed number of installments from the agreed next due date. Paid > fee: rejected.
"""

from __future__ import annotations

import datetime

API = "/api/v1"
TODAY = datetime.date.today()


def _register(client, manager, name, **fin):
    body = {"name": name, "father_phone": "0511111111", **fin}
    return client.post(f"{API}/students", json=body, headers=manager)


def _plan_state(db, student_id):
    plans = db.execute(
        "SELECT * FROM fee_plans WHERE student_id = %s AND deleted_at IS NULL", (student_id,)
    ).fetchall()
    inst = db.execute(
        "SELECT i.* FROM installments i JOIN fee_plans f ON f.id = i.fee_plan_id "
        "WHERE f.student_id = %s ORDER BY i.seq_no",
        (student_id,),
    ).fetchall()
    pays = db.execute("SELECT * FROM payments WHERE student_id = %s", (student_id,)).fetchall()
    bal = db.execute("SELECT balance FROM v_student_balance WHERE student_id = %s", (student_id,)).fetchone()
    return plans, inst, pays, float(bal["balance"])


def test_primary_scenario_1500_paid_500_two_installments(db, client, manager):
    next_due = TODAY + datetime.timedelta(days=30)
    res = _register(
        client, manager, "طالب الاتفاق",
        registration_fee=1500, initial_payment_amount=500,
        installments_count=2, next_due_date=next_due.isoformat(), interval_days=30,
    )
    assert res.status_code == 200, res.text
    body = res.json()
    plans, inst, pays, balance = _plan_state(db, body["id"])
    assert len(plans) == 1 and plans[0]["count"] == 3 and float(plans[0]["total_amount"]) == 1500
    assert [float(i["amount"]) for i in inst] == [500, 500, 500]
    assert [i["status"] for i in inst] == ["paid", "pending", "pending"]
    assert [i["due_date"] for i in inst] == [TODAY, next_due, next_due + datetime.timedelta(days=30)]
    assert len(pays) == 1 and float(pays[0]["amount"]) == 500
    assert body["initial_receipt"]["payment_id"] == str(pays[0]["id"])
    assert balance == 1000
    assert isinstance(body["installments"][0]["amount"], (int, float))


def test_fully_paid_single_installment(db, client, manager):
    res = _register(client, manager, "طالب مسدد", registration_fee=500, initial_payment_amount=500)
    assert res.status_code == 200, res.text
    plans, inst, pays, balance = _plan_state(db, res.json()["id"])
    assert len(plans) == 1 and plans[0]["count"] == 1
    assert [(float(i["amount"]), i["status"]) for i in inst] == [(500, "paid")]
    assert len(pays) == 1 and balance == 0


def test_empty_payment_does_not_record_full_fee(db, client, manager):
    res = _register(
        client, manager, "طالب بلا دفعة",
        registration_fee=500, initial_payment_amount=None,
        installments_count=2, next_due_date=(TODAY + datetime.timedelta(days=7)).isoformat(), interval_days=7,
    )
    assert res.status_code == 200, res.text
    plans, inst, pays, balance = _plan_state(db, res.json()["id"])
    assert pays == []
    assert [(float(i["amount"]), i["status"]) for i in inst] == [(250, "pending"), (250, "pending")]
    assert balance == 500


def test_overpayment_rejected_and_rolled_back(db, client, manager):
    res = _register(client, manager, "طالب زيادة", registration_fee=500, initial_payment_amount=600)
    assert res.status_code == 400, res.text
    assert db.execute("SELECT count(*) AS n FROM students WHERE name = 'طالب زيادة'").fetchone()["n"] == 0
    assert db.execute("SELECT count(*) AS n FROM payments").fetchone()["n"] == 0
    assert db.execute("SELECT count(*) AS n FROM fee_plans").fetchone()["n"] == 0


def test_payment_without_fee_and_bad_amount_rejected(db, client, manager):
    assert _register(client, manager, "دفعة بلا رسوم", initial_payment_amount=300).status_code == 400
    assert _register(client, manager, "مبلغ خاطئ", registration_fee="abc").status_code == 400
    assert db.execute("SELECT count(*) AS n FROM payments").fetchone()["n"] == 0


def test_partial_payment_requires_future_next_due_date(db, client, manager):
    missing = _register(client, manager, "طالب ناقص", registration_fee=500, initial_payment_amount=200, installments_count=3)
    assert missing.status_code == 400, missing.text
    past = _register(
        client, manager, "طالب ماض", registration_fee=500, initial_payment_amount=200,
        installments_count=3, next_due_date=TODAY.isoformat(),
    )
    assert past.status_code == 400, past.text
    assert db.execute("SELECT count(*) AS n FROM students").fetchone()["n"] == 0


def test_rounding_last_installment_absorbs_remainder(db, client, manager):
    res = _register(
        client, manager, "طالب تقريب",
        registration_fee=1000, initial_payment_amount=0,
        installments_count=3, next_due_date=(TODAY + datetime.timedelta(days=14)).isoformat(), interval_days=14,
    )
    assert res.status_code == 200, res.text
    _, inst, _, balance = _plan_state(db, res.json()["id"])
    assert [float(i["amount"]) for i in inst] == [333.33, 333.33, 333.34]
    assert balance == 1000


def test_no_fee_creates_nothing(db, client, manager):
    res = _register(client, manager, "طالب بلا رسوم")
    assert res.status_code == 200, res.text
    plans, inst, pays, _ = _plan_state(db, res.json()["id"])
    assert plans == [] and inst == [] and pays == []


def test_plain_fee_plan_endpoint_unchanged(db, client, manager):
    sid = _register(client, manager, "طالب خطة").json()["id"]
    res = client.post(
        f"{API}/fee-plans",
        json={"student_id": sid, "total_amount": 900, "count": 3, "start_date": TODAY.isoformat(), "interval_days": 30},
        headers=manager,
    )
    assert res.status_code == 200, res.text
    _, inst, _, _ = _plan_state(db, sid)
    assert [float(i["amount"]) for i in inst] == [300, 300, 300]
    assert [i["due_date"] for i in inst] == [TODAY + datetime.timedelta(days=30 * k) for k in range(3)]
