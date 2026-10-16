"""Fleet order 15: Tabby/Tamara, allergy profile, bus subscription split, receiver name, WhatsApp Web.

Contract: fleet_orders/15_PLAN.md (D1-D6). Needs migrations 001-015 applied to TEST_DATABASE_URL.
Bus money rides on ONE payment row (payments.bus_amount): the ledger and receipt carry the full
amount, while installments and v_student_balance see only the registration portion.
"""

from __future__ import annotations

import datetime
import re
from pathlib import Path

import pytest

from tests.conftest import _student, login, make_user

WEB_ROOT = Path(__file__).resolve().parents[2] / "web"
RECEIPT_TEMPLATE = WEB_ROOT / "print" / "templates" / "receipt.html"
EASTERN_DIGITS = re.compile(r"[٠-٩]")
PLAN_TERMS = {
    "installments_count": 1,
    "next_due_date": (datetime.date.today() + datetime.timedelta(days=30)).isoformat(),
    "interval_days": 30,
}


def _count(db, table: str) -> int:
    db.rollback()  # fresh snapshot of what the API committed
    return db.execute(f'SELECT count(*) AS n FROM "{table}"').fetchone()["n"]


def _register(client, headers, **extra):
    payload = {"name": "طالب اختبار الباص", "gender": "بنين", "group_name": "الصباح", "guardian_phone": "0551234567"}
    payload.update(extra)
    return client.post("/api/v1/students", json=payload, headers=headers)


def _print_receipt(client, headers, payment_id) -> dict:
    res = client.get(f"/api/v1/print/receipt?payment={payment_id}", headers=headers)
    assert res.status_code == 200, res.text
    return res.json()


def _assert_nothing_written(db) -> None:
    assert _count(db, "students") == 0
    assert _count(db, "payments") == 0
    assert _count(db, "receipts") == 0


# ---- D1: payment methods ----


@pytest.mark.parametrize("method", ["تابي", "تمارا"])
def test_bnpl_methods_accepted_for_down_payment(client, db, manager, method):
    res = _register(client, manager, registration_fee=500, initial_payment_amount=500, payment_method=method)
    assert res.status_code == 200, res.text
    payment = res.json()["initial_payment"]
    assert payment["method"] == method
    assert _print_receipt(client, manager, payment["id"])["method"] == method


def test_label_string_is_not_a_payment_method(client, db, manager):
    # "تابي (Tabby)" is the dropdown LABEL; the stored value must stay the canonical CHECK value.
    res = _register(client, manager, registration_fee=500, initial_payment_amount=500, payment_method="تابي (Tabby)")
    assert res.status_code == 400, res.text
    assert res.json()["error"]["code"] == "validation_error"
    _assert_nothing_written(db)


# ---- D2: allergy profile ----


def test_new_student_defaults_for_allergy_and_bus(client, db, manager):
    sid = _student(client, manager, "طالب افتراضي", None)
    row = client.get(f"/api/v1/students/{sid}", headers=manager).json()
    assert row["has_allergies"] is False
    assert row["allergy_notes"] is None
    assert row["has_bus_subscription"] is False
    # Rule 9: numeric columns arrive as JSON numbers, never Decimal strings
    assert row["bus_fee"] == 0.0 and row["bus_paid"] == 0.0 and row["bus_remaining"] == 0.0


def test_allergy_profile_roundtrip_and_clear(client, db, manager):
    res = _register(client, manager, has_allergies=True, allergy_notes="  حساسية فول سوداني وبنسلين  ")
    assert res.status_code == 200, res.text
    sid = res.json()["id"]

    row = client.get(f"/api/v1/students/{sid}", headers=manager).json()
    assert row["has_allergies"] is True
    assert row["allergy_notes"] == "حساسية فول سوداني وبنسلين"

    res = client.patch(f"/api/v1/students/{sid}", json={"has_allergies": False}, headers=manager)
    assert res.status_code == 200, res.text
    row = client.get(f"/api/v1/students/{sid}", headers=manager).json()
    assert row["has_allergies"] is False
    assert row["allergy_notes"] is None


def test_allergy_flag_without_notes_rejected(client, db, manager):
    res = _register(client, manager, has_allergies=True, allergy_notes="   ")
    assert res.status_code == 400, res.text
    assert "الحساسية" in res.json()["error"]["message"]
    _assert_nothing_written(db)


# ---- D3 + D6: bus subscription on a single split payment, receiver name ----


def test_registration_with_bus_single_payment_split(client, db):
    staff_id = db.execute(
        "INSERT INTO staff (name, role_title) VALUES (%s, %s) RETURNING id", ("أمل المحاسبة", "محاسبة")
    ).fetchone()["id"]
    uid = make_user(db, "cashier1", "manager")
    db.execute("UPDATE users SET staff_id = %s WHERE id = %s", (staff_id, uid))
    db.commit()
    headers = login(client, "cashier1")

    res = _register(
        client, headers,
        registration_fee=600, initial_payment_amount=350, payment_method="كاش", **PLAN_TERMS,
        has_bus_subscription=True, bus_fee=400, bus_paid=150,
    )
    assert res.status_code == 200, res.text
    created = res.json()
    assert created["has_bus_subscription"] is True
    assert float(created["bus_fee"]) == 400.0
    assert float(created["bus_paid"]) == 150.0
    assert float(created["bus_remaining"]) == 250.0

    payment = created["initial_payment"]
    assert float(payment["amount"]) == 500.0, "one payment carries registration + bus"
    assert float(payment["bus_amount"]) == 150.0
    assert _count(db, "payments") == 1
    assert _count(db, "receipts") == 1

    ledger = db.execute(
        "SELECT amount FROM ledger_entries WHERE ref_table = 'payments' AND ref_id = %s", (payment["id"],)
    ).fetchall()
    assert [float(r["amount"]) for r in ledger] == [500.0], "cash drawer receives the full amount once"

    allocated = db.execute(
        "SELECT COALESCE(SUM(i.paid_amount), 0) AS s FROM installments i JOIN fee_plans f ON f.id = i.fee_plan_id "
        "WHERE f.student_id = %s", (created["id"],)
    ).fetchone()["s"]
    assert float(allocated) == 350.0, "bus portion must never be allocated to registration installments"

    balance = db.execute("SELECT total_paid, balance FROM v_student_balance WHERE student_id = %s", (created["id"],)).fetchone()
    assert float(balance["total_paid"]) == 350.0
    assert float(balance["balance"]) == 250.0

    data = _print_receipt(client, headers, payment["id"])
    assert data["amount"] == 500.0
    assert "ريال" in data["amount_words"]
    assert data["registration_paid_amount"] == 350.0
    assert data["has_bus"] is True
    assert data["bus_fee"] == 400.0
    assert data["bus_paid_amount"] == 150.0
    assert data["bus_remaining"] == 250.0
    # Installment math must use the registration portion (350), not the receipt total (500)
    assert data["total_fee"] == 600.0
    assert data["remaining_installments_count"] == 1
    assert data["next_installment_amount"] == 250.0
    assert data["receiver_name"] == "أمل المحاسبة"
    assert data["guardian_whatsapp"] == "966551234567"


def test_bus_paid_over_fee_rejected(client, db, manager):
    res = _register(client, manager, has_bus_subscription=True, bus_fee=200, bus_paid=250)
    assert res.status_code == 400, res.text
    assert "باص" in res.json()["error"]["message"]  # "للباص" contracts the article
    _assert_nothing_written(db)


def test_bus_amounts_without_subscription_rejected(client, db, manager):
    res = _register(client, manager, has_bus_subscription=False, bus_fee=300, bus_paid=0)
    assert res.status_code == 400, res.text
    _assert_nothing_written(db)


def test_bus_paid_immutable_after_registration(client, db, manager):
    res = _register(client, manager, has_bus_subscription=True, bus_fee=300, bus_paid=150)
    assert res.status_code == 200, res.text
    sid = res.json()["id"]
    res = client.patch(f"/api/v1/students/{sid}", json={"bus_paid": 0}, headers=manager)
    assert res.status_code == 400, res.text
    row = client.get(f"/api/v1/students/{sid}", headers=manager).json()
    assert row["bus_paid"] == 150.0


def test_bus_only_registration_without_fee_plan(client, db, manager):
    res = _register(
        client, manager, registration_fee=0, initial_payment_amount=0, payment_method="مدى",
        has_bus_subscription=True, bus_fee=300, bus_paid=300,
    )
    assert res.status_code == 200, res.text
    created = res.json()
    payment = created["initial_payment"]
    assert payment is not None and created["initial_receipt"] is not None
    assert float(payment["amount"]) == 300.0 and float(payment["bus_amount"]) == 300.0
    assert _count(db, "fee_plans") == 0
    balance = db.execute("SELECT balance FROM v_student_balance WHERE student_id = %s", (created["id"],)).fetchone()
    assert float(balance["balance"]) == 0.0

    data = _print_receipt(client, manager, payment["id"])
    assert data["has_bus"] is True
    assert data["registration_paid_amount"] == 0.0
    assert data["bus_remaining"] == 0.0
    assert data["has_installments"] is False


def _manual_payment(client, headers, sid: str, method: str = "تمارا") -> dict:
    res = client.post(
        "/api/v1/payments",
        json={"student_id": sid, "amount": 100, "paid_on": datetime.date.today().isoformat(), "method": method},
        headers=headers,
    )
    assert res.status_code == 200, res.text
    return res.json()


def test_payment_endpoint_records_issuer_username_fallback(client, db, manager):
    sid = _student(client, manager, "طالب دفعة يدوية", None)
    payment = _manual_payment(client, manager, sid)
    data = _print_receipt(client, manager, payment["id"])
    assert data["receiver_name"] == "manager1", "no staff link: fall back to the username"
    assert data["method"] == "تمارا"
    assert data["has_bus"] is False
    assert data["registration_paid_amount"] == 100.0


def test_legacy_receipt_has_blank_receiver(client, db, manager):
    sid = _student(client, manager, "طالب سند قديم", None)
    payment = _manual_payment(client, manager, sid, method="كاش")
    db.execute("UPDATE receipts SET issued_by_user_id = NULL WHERE payment_id = %s", (payment["id"],))
    db.commit()
    data = _print_receipt(client, manager, payment["id"])
    # Never attribute legacy cash to whoever happens to print it
    assert data["receiver_name"] == ""


# ---- D5: WhatsApp Web target ----


@pytest.mark.parametrize(
    ("phone", "expected"),
    [
        ("0551234567", "966551234567"),
        ("+966 55 123 4567", "966551234567"),
        ("00966551234567", "966551234567"),
        ("551234567", "966551234567"),
        ("0001112223", ""),  # e2e fixture phone: not a Saudi mobile, so no share button
    ],
)
def test_guardian_whatsapp_normalisation(client, db, manager, phone, expected):
    res = _register(client, manager, guardian_phone=phone, registration_fee=100, initial_payment_amount=100)
    assert res.status_code == 200, res.text
    data = _print_receipt(client, manager, res.json()["initial_payment"]["id"])
    assert data["guardian_whatsapp"] == expected


# ---- Template + static contracts ----


def test_receipt_template_contract(client, db, manager):
    template = RECEIPT_TEMPLATE.read_text(encoding="utf-8")
    for token in ("{{receiver_name}}", "{{#has_bus}}", "{{/has_bus}}", "sig-receiver", "receipt-bus-row"):
        assert token in template, f"receipt.html is missing {token}"

    res = _register(
        client, manager, registration_fee=600, initial_payment_amount=350, **PLAN_TERMS,
        has_bus_subscription=True, bus_fee=400, bus_paid=150,
    )
    assert res.status_code == 200, res.text
    data = _print_receipt(client, manager, res.json()["initial_payment"]["id"])
    keys = set(re.findall(r"\{\{[#^/]?([a-zA-Z0-9_]+)\}\}", template))
    missing = sorted(k for k in keys if k not in data)
    assert missing == [], f"placeholders without payload keys: {missing}"
    # Rule 50: Western digits only in every printed string
    offenders = {k: v for k, v in data.items() if isinstance(v, str) and EASTERN_DIGITS.search(v)}
    assert offenders == {}


def test_print_engine_never_targets_native_whatsapp():
    # Scope: the print engine and its templates (order 15 D5). Native URI scheme (whatsapp://)
    # is banned. Desktop targets web.whatsapp.com and mobile targets wa.me via responsive device detection.
    sources = [WEB_ROOT / "print" / "print_engine.js", *sorted((WEB_ROOT / "print" / "templates").glob("*.html"))]
    for path in sources:
        text = path.read_text(encoding="utf-8")
        for banned in ("whatsapp://",):
            assert banned not in text, f"{path.name} uses {banned}"
    engine = (WEB_ROOT / "print" / "print_engine.js").read_text(encoding="utf-8")
    assert "https://web.whatsapp.com/send" in engine
    assert "https://wa.me/" in engine
