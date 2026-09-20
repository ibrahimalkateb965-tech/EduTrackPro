"""System settings (006): GET/PUT /settings, RBAC, print fallback wiring, users(staff_id) partial unique index."""

from __future__ import annotations

import psycopg
import pytest

from edutrack_api.services.settings import DEFAULTS, SETTING_KEYS

from .conftest import make_user
from .test_print import _seed_basic_data

API = "/api/v1"


@pytest.fixture()
def restore_defaults(db):
    """system_settings is in KEEP_TABLES (seeded by 006) — put the seed values back after a mutating test."""
    yield
    for key, value in DEFAULTS.items():
        db.execute(
            "INSERT INTO system_settings (key, value) VALUES (%s, %s) "
            "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
            (key, value),
        )
    db.commit()


def test_get_settings_returns_seed_defaults(client, db, manager):
    res = client.get(f"{API}/settings", headers=manager)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["keys"] == list(SETTING_KEYS)
    assert body["settings"] == DEFAULTS


def test_supervisor_can_read_but_not_write(client, db, supervisor):
    assert client.get(f"{API}/settings", headers=supervisor).status_code == 200
    res = client.put(f"{API}/settings", json={"academic_year": "1448-1449 هـ"}, headers=supervisor)
    assert res.status_code == 403, res.text
    row = db.execute("SELECT value FROM system_settings WHERE key='academic_year'").fetchone()
    assert row["value"] == DEFAULTS["academic_year"]


def test_put_validation(client, db, manager):
    assert client.put(f"{API}/settings", json={}, headers=manager).status_code == 422
    assert client.put(f"{API}/settings", json={"academic_year": "   "}, headers=manager).status_code == 422
    assert client.put(f"{API}/settings", json={"academic_year": "x" * 201}, headers=manager).status_code == 422
    # unknown keys are rejected, not silently stored
    res = client.put(f"{API}/settings", json={"academic_year": "1448-1449 هـ", "evil": "1"}, headers=manager)
    assert res.status_code == 422, res.text
    assert db.execute("SELECT count(*) AS n FROM system_settings WHERE key='evil'").fetchone()["n"] == 0


def test_put_updates_flow_into_print_and_audit(client, db, manager, restore_defaults):
    res = client.put(
        f"{API}/settings",
        json={"academic_year": " 1448-1449 هـ ", "manager_name": "أ. محمد", "center_phone": "0559998877"},
        headers=manager,
    )
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["updated"] == ["academic_year", "center_phone", "manager_name"]
    assert body["settings"]["academic_year"] == "1448-1449 هـ"  # trimmed
    assert body["settings"]["center_name"] == DEFAULTS["center_name"]  # untouched keys keep their value

    # partial update: a second PUT touching one key must not reset the others
    res = client.put(f"{API}/settings", json={"center_address": "الرياض"}, headers=manager)
    assert res.status_code == 200
    assert res.json()["settings"]["manager_name"] == "أ. محمد"

    # print payloads read the live values (guardian card no longer hard-codes the year)
    data = _seed_basic_data(db)
    card = client.get(f"{API}/print/guardian-card?student={data['student_id']}", headers=manager).json()
    assert card["academic_year"] == "1448-1449 هـ"
    assert card["manager_name"] == "أ. محمد"
    assert card["center_phone"] == "0559998877"
    assert card["center_address"] == "الرياض"
    sched = client.get(f"{API}/print/schedule?room={data['room_id']}", headers=manager).json()
    assert sched["template"] == "schedule"
    assert sched["academic_year"] == "1448-1449 هـ"

    audit = db.execute(
        "SELECT details_json FROM audit_log WHERE entity='system_settings' AND action='update' ORDER BY at"
    ).fetchall()
    assert len(audit) == 2
    assert audit[0]["details_json"]["changed"]["manager_name"] == "أ. محمد"


def test_print_falls_back_to_constants_when_rows_missing(client, db, manager, restore_defaults):
    db.execute("DELETE FROM system_settings")
    db.commit()
    data = _seed_basic_data(db)
    card = client.get(f"{API}/print/guardian-card?student={data['student_id']}", headers=manager).json()
    assert card["academic_year"] == DEFAULTS["academic_year"]
    assert card["center_name"] == DEFAULTS["center_name"]
    # GET still answers with the constants
    assert client.get(f"{API}/settings", headers=manager).json()["settings"] == DEFAULTS


def test_one_active_user_per_staff_member(db):
    staff_id = db.execute(
        "INSERT INTO staff (name, role_title) VALUES ('م/آية', 'معلمة') RETURNING id"
    ).fetchone()["id"]
    first = make_user(db, "aya1", "teacher")
    db.execute("UPDATE users SET staff_id=%s WHERE id=%s", (staff_id, first))
    db.commit()
    second = make_user(db, "aya2", "teacher")
    with pytest.raises(psycopg.errors.UniqueViolation):
        db.execute("UPDATE users SET staff_id=%s WHERE id=%s", (staff_id, second))
    db.rollback()
    # soft-deleting the first login frees the slot (partial index ignores deleted rows)
    db.execute("UPDATE users SET deleted_at=now(), is_active=false WHERE id=%s", (first,))
    db.execute("UPDATE users SET staff_id=%s WHERE id=%s", (staff_id, second))
    db.commit()
    active = db.execute(
        "SELECT count(*) AS n FROM users WHERE staff_id=%s AND deleted_at IS NULL", (staff_id,)
    ).fetchone()["n"]
    assert active == 1
