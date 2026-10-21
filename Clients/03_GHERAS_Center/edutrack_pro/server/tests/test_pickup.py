"""Child pickup flow regressions (Milestone M1, migration 016).

Covers delegate idempotency/uniqueness, request cutoff/active-cycle CAS, the
notified -> in_prep -> ready -> completed guardians gate, role-scoped queues,
guardian/teacher scope refusals, body validation, and threaded replay
idempotency across real concurrent PostgreSQL connections.
"""

from __future__ import annotations

import threading
import time
import uuid
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import psycopg
import pytest
from psycopg.rows import dict_row

from tests.conftest import (
    MAIN_BRANCH,
    TEST_DATABASE_URL,
    _guardian,
    _room,
    _student,
    _teacher,
)

ROOT = Path(__file__).resolve().parents[2]
MIGRATION_016 = ROOT / "db" / "postgres" / "016_pickup_requests.sql"
API = "/api/v1/pickup"

_GUARDIAN_NID = "1012345678"
_GUARDIAN_NID_OTHER = "1012345679"
_OPEN_CUTOFF = "23:59"
_CLOSED_CUTOFF = "00:00"
_TZ = ZoneInfo("Asia/Riyadh")


@pytest.fixture(autouse=True)
def _restore_cutoff(db):
    """system_settings survives truncation; reset cutoff after each test for isolation."""
    yield
    db.execute(
        "INSERT INTO system_settings (key, value) VALUES ('pickup_cutoff_time', '18:30') "
        "ON CONFLICT (key) DO UPDATE SET value = '18:30'"
    )
    db.commit()


def _set_cutoff(conn, value: str) -> None:
    conn.execute(
        "INSERT INTO system_settings (key, value) VALUES ('pickup_cutoff_time', %s) "
        "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
        (value,),
    )
    conn.commit()


def _world(db, client, manager, name: str, national_id: str = _GUARDIAN_NID):
    """Room + student + guardian; guardian user carries a Western national_id."""
    _set_cutoff(db, _OPEN_CUTOFF)
    room_id = _room(client, manager, f"قاعة {name}")
    student_id = _student(client, manager, f"طالب {name}", room_id)
    user_id, headers = _guardian(db, client, f"g_{name}", [student_id])
    db.execute("UPDATE users SET national_id = %s WHERE id = %s", (national_id, user_id))
    db.commit()
    return student_id, headers, room_id


def _two_guardians(db, client, manager, suffix: str) -> tuple[str, dict, str, dict]:
    """Guardian A linked to child A and guardian B linked to child B; distinct national ids."""
    student_a, headers_a, _ = _world(db, client, manager, f"{suffix}_a", national_id=_GUARDIAN_NID)
    student_b, headers_b, _ = _world(
        db, client, manager, f"{suffix}_b", national_id=_GUARDIAN_NID_OTHER
    )
    return student_a, headers_a, student_b, headers_b


def _delegate_body(student_id: str, national_id: str = "1055677881", cid: str | None = None) -> dict:
    body = {
        "student_id": student_id,
        "name": "خاله سعد",
        "national_id": national_id,
        "phone": "0551234567",
        "relation": "خال",
    }
    if cid is not None:
        body["client_request_id"] = cid
    return body


def _request(client, headers, student_id: str, **extra):
    return client.post(f"{API}/requests", json={"student_id": student_id, **extra}, headers=headers)


def _teacher_run(db, client, name: str, request_id: str, room_id: str) -> dict:
    """Create a room-scoped teacher and drive the request 'notified' -> 'in_prep' -> 'ready'."""
    _, teacher = _teacher(db, client, f"t_{name}", room_id)
    res = client.post(f"{API}/requests/{request_id}/start-prep", headers=teacher)
    assert res.status_code == 200, res.text
    res = client.post(f"{API}/requests/{request_id}/ready", headers=teacher, json={"prep_checklist": {}})
    assert res.status_code == 200, res.text
    return teacher


def _error_code(res) -> str:
    return res.json()["error"]["code"]


def test_migration_016_applied_twice_idempotent(db):
    script = MIGRATION_016.read_text(encoding="utf-8")
    db.execute(script)
    db.execute(script)
    db.commit()
    assert db.execute("SELECT count(*) AS n FROM pickup_delegates").fetchone()["n"] == 0
    assert db.execute("SELECT count(*) AS n FROM pickup_requests").fetchone()["n"] == 0
    row = db.execute(
        "SELECT value FROM system_settings WHERE key = 'pickup_cutoff_time'"
    ).fetchone()
    assert row is not None


def test_guardian_without_national_id_cannot_create_direct_pickup(client, db, manager):
    room_id = _room(client, manager, "قاعة بلا هوية")
    student_id = _student(client, manager, "طالب بلا هوية", room_id)
    _, headers = _guardian(db, client, "g_nonid", [student_id])
    _set_cutoff(db, _OPEN_CUTOFF)

    res = _request(client, headers, student_id)

    assert res.status_code == 422, res.text
    assert _error_code(res) == "missing_guardian_national_id"
    assert db.execute("SELECT count(*) AS n FROM pickup_requests").fetchone()["n"] == 0


def test_pickup_request_after_cutoff_time_rejected(client, db, manager):
    student_id, headers, _ = _world(db, client, manager, "cutoff")
    _set_cutoff(db, _CLOSED_CUTOFF)

    res = _request(client, headers, student_id)

    assert res.status_code == 400, res.text
    assert _error_code(res) == "pickup_cutoff_passed"
    assert db.execute("SELECT count(*) AS n FROM pickup_requests").fetchone()["n"] == 0


def test_duplicate_delegate_client_request_id_returns_200(client, db, manager):
    student_id, headers, _ = _world(db, client, manager, "replay_del")
    cid = str(uuid.uuid4())

    first = client.post(f"{API}/delegates", json=_delegate_body(student_id, cid=cid), headers=headers)
    assert first.status_code == 200, first.text
    second = client.post(f"{API}/delegates", json=_delegate_body(student_id, cid=cid), headers=headers)

    assert second.status_code == 200, second.text
    assert second.json()["id"] == first.json()["id"]
    assert db.execute(
        "SELECT count(*) AS n FROM pickup_delegates WHERE client_request_id = %s", (cid,)
    ).fetchone()["n"] == 1


def test_duplicate_delegate_national_id_returns_409(client, db, manager):
    student_id, headers, _ = _world(db, client, manager, "dup_del")
    _delegate_first = client.post(f"{API}/delegates", json=_delegate_body(student_id), headers=headers)
    assert _delegate_first.status_code == 200, _delegate_first.text

    res = client.post(f"{API}/delegates", json=_delegate_body(student_id), headers=headers)

    assert res.status_code == 409, res.text
    assert _error_code(res) == "delegate_exists"
    assert db.execute("SELECT count(*) AS n FROM pickup_delegates").fetchone()["n"] == 1


def test_unapproved_delegate_rejected_on_pickup(client, db, manager):
    student_id, headers, _ = _world(db, client, manager, "unapp_del")
    delegate = client.post(f"{API}/delegates", json=_delegate_body(student_id), headers=headers).json()
    db.execute("UPDATE pickup_delegates SET is_approved = FALSE WHERE id = %s", (delegate["id"],))
    db.commit()

    res = _request(client, headers, student_id, delegate_id=delegate["id"])

    assert res.status_code == 404, res.text
    assert _error_code(res) == "delegate_not_found"
    assert db.execute("SELECT count(*) AS n FROM pickup_requests").fetchone()["n"] == 0


def test_delete_delegate_in_active_pickup_returns_409(client, db, manager):
    student_id, headers, _ = _world(db, client, manager, "act_del")
    delegate = client.post(f"{API}/delegates", json=_delegate_body(student_id), headers=headers).json()
    created = _request(client, headers, student_id, delegate_id=delegate["id"])
    assert created.status_code == 200, created.text

    res = client.delete(f"{API}/delegates/{delegate['id']}", headers=headers)

    assert res.status_code == 409, res.text
    assert _error_code(res) == "delegate_in_active_pickup"
    assert db.execute(
        "SELECT 1 FROM pickup_delegates WHERE id = %s AND deleted_at IS NULL", (delegate["id"],)
    ).fetchone() is not None


def test_duplicate_pickup_client_request_id_returns_200(client, db, manager):
    student_id, headers, _ = _world(db, client, manager, "replay_req")
    cid = str(uuid.uuid4())

    first = _request(client, headers, student_id, client_request_id=cid)
    assert first.status_code == 200, first.text
    second = _request(client, headers, student_id, client_request_id=cid)

    assert second.status_code == 200, second.text
    assert second.json()["id"] == first.json()["id"]
    assert db.execute(
        "SELECT count(*) AS n FROM pickup_requests WHERE client_request_id = %s", (cid,)
    ).fetchone()["n"] == 1


def test_second_active_cycle_for_same_student_returns_409(client, db, manager):
    student_id, headers, _ = _world(db, client, manager, "dup_req")
    first = _request(client, headers, student_id)
    assert first.status_code == 200, first.text

    res = _request(client, headers, student_id)

    assert res.status_code == 409, res.text
    assert _error_code(res) == "pickup_already_active"
    assert db.execute("SELECT count(*) AS n FROM pickup_requests").fetchone()["n"] == 1


def test_cas_invalid_transition_returns_409(client, db, manager):
    student_id, headers, room_id = _world(db, client, manager, "bad_trans")
    rid = _request(client, headers, student_id).json()["id"]
    _, teacher = _teacher(db, client, "t_bad_trans", room_id)

    res = client.post(f"{API}/requests/{rid}/ready", headers=teacher, json={"prep_checklist": {}})
    assert res.status_code == 409, res.text
    assert _error_code(res) == "invalid_status"

    cancelled = client.post(f"{API}/requests/{rid}/cancel", headers=headers, json={"cancel_reason": "سألتقطه بنفسي"})
    assert cancelled.status_code == 200, cancelled.text
    res = client.post(f"{API}/requests/{rid}/cancel", headers=headers, json={"cancel_reason": "محاولة مكررة"})

    assert res.status_code == 409, res.text
    assert _error_code(res) == "invalid_status"
    row = db.execute("SELECT status FROM pickup_requests WHERE id = %s", (rid,)).fetchone()
    assert row["status"] == "cancelled"


def test_approving_non_early_leave_returns_409(client, db, manager):
    student_id, headers, _ = _world(db, client, manager, "plain_req")
    rid = _request(client, headers, student_id).json()["id"]

    res = client.post(f"{API}/requests/{rid}/approve-early-leave", headers=manager)

    assert res.status_code == 409, res.text
    assert _error_code(res) == "invalid_status"
    row = db.execute("SELECT is_early_leave, early_leave_approved FROM pickup_requests WHERE id = %s", (rid,)).fetchone()
    assert row["is_early_leave"] is False
    assert row["early_leave_approved"] is False


def test_double_early_leave_approval_returns_409_preserves_first_approver(client, db, manager):
    student_id, headers, _ = _world(db, client, manager, "dbl_app")
    rid = _request(client, headers, student_id, is_early_leave=True).json()["id"]

    first = client.post(f"{API}/requests/{rid}/approve-early-leave", headers=manager)
    assert first.status_code == 200, first.text
    res = client.post(f"{API}/requests/{rid}/approve-early-leave", headers=manager)

    assert res.status_code == 409, res.text
    assert _error_code(res) == "invalid_status"
    mgr_id = db.execute("SELECT id FROM users WHERE username = 'manager1'").fetchone()["id"]
    row = db.execute(
        "SELECT early_leave_approved_by, early_leave_approved FROM pickup_requests WHERE id = %s", (rid,)
    ).fetchone()
    assert row["early_leave_approved_by"] == mgr_id
    assert row["early_leave_approved"] is True
    assert db.execute(
        "SELECT count(*) AS n FROM audit_log "
        "WHERE action = 'pickup.early_leave_approve' AND entity = 'pickup_requests' AND entity_id = %s",
        (rid,),
    ).fetchone()["n"] == 1


def test_teacher_cannot_prep_unapproved_early_leave(client, db, manager):
    student_id, headers, room_id = _world(db, client, manager, "unapp_early")
    rid = _request(client, headers, student_id, is_early_leave=True).json()["id"]
    _, teacher = _teacher(db, client, "t_unapp_early", room_id)

    res = client.post(f"{API}/requests/{rid}/start-prep", headers=teacher)

    assert res.status_code == 409, res.text
    assert _error_code(res) == "early_leave_not_approved"
    row = db.execute("SELECT status FROM pickup_requests WHERE id = %s", (rid,)).fetchone()
    assert row["status"] == "notified"


def test_teacher_queue_hides_unapproved_early_leave(client, db, manager, supervisor):
    student_id, headers, room_id = _world(db, client, manager, "queue hide")
    rid = _request(client, headers, student_id, is_early_leave=True).json()["id"]
    _, teacher = _teacher(db, client, "t_queue", room_id)

    teacher_view = client.get(f"{API}/requests/active", headers=teacher)
    assert teacher_view.status_code == 200, teacher_view.text
    assert teacher_view.json()["requests"] == []

    staff_view = client.get(f"{API}/requests/active", headers=supervisor)
    assert staff_view.status_code == 200, staff_view.text
    assert [r["id"] for r in staff_view.json()["requests"]] == [rid]


def test_guardian_cannot_request_pickup_for_foreign_child_403(client, db, manager):
    _, headers_a, student_b, _ = _two_guardians(db, client, manager, "foreign_req")

    res = _request(client, headers_a, student_b)

    assert res.status_code == 403, res.text
    assert _error_code(res) == "forbidden"
    assert db.execute("SELECT count(*) AS n FROM pickup_requests").fetchone()["n"] == 0


def test_guardian_cannot_create_delegate_for_foreign_child_403(client, db, manager):
    _, headers_a, student_b, _ = _two_guardians(db, client, manager, "foreign_del")

    res = client.post(f"{API}/delegates", json=_delegate_body(student_b), headers=headers_a)

    assert res.status_code == 403, res.text
    assert _error_code(res) == "forbidden"
    assert db.execute("SELECT count(*) AS n FROM pickup_delegates").fetchone()["n"] == 0


def test_guardian_cannot_cancel_foreign_request(client, db, manager):
    student_a, headers_a, student_b, headers_b = _two_guardians(db, client, manager, "foreign_can")
    rid = _request(client, headers_a, student_a).json()["id"]

    res = client.post(
        f"{API}/requests/{rid}/cancel", headers=headers_b, json={"cancel_reason": "ليس طلبي"}
    )

    assert res.status_code == 403, res.text
    assert _error_code(res) == "forbidden"
    row = db.execute("SELECT status FROM pickup_requests WHERE id = %s", (rid,)).fetchone()
    assert row["status"] == "notified"


def test_teacher_cannot_prep_or_ready_other_room_403(client, db, manager):
    room_one = _room(client, manager, "قاعة معلم الاختبار")
    student_id, headers, room_two = _world(db, client, manager, "other_room")
    rid = _request(client, headers, student_id).json()["id"]
    _, teacher = _teacher(db, client, "t_other_room", room_one)
    _, roomless_teacher = _teacher(db, client, "t_other_room_none", None)

    prep = client.post(f"{API}/requests/{rid}/start-prep", headers=teacher)
    ready = client.post(f"{API}/requests/{rid}/ready", headers=teacher)
    roomless_prep = client.post(f"{API}/requests/{rid}/start-prep", headers=roomless_teacher)

    assert prep.status_code == 403, prep.text
    assert _error_code(prep) == "forbidden"
    assert ready.status_code == 403, ready.text
    assert _error_code(ready) == "forbidden"
    assert roomless_prep.status_code == 403, roomless_prep.text
    row = db.execute("SELECT status FROM pickup_requests WHERE id = %s", (rid,)).fetchone()
    assert row["status"] == "notified"


def test_guardian_queue_shows_only_own_children(client, db, manager):
    student_a, headers_a, student_b, headers_b = _two_guardians(db, client, manager, "queue_iso")
    created_a = _request(client, headers_a, student_a)
    assert created_a.status_code == 200, created_a.text
    created_b = _request(client, headers_b, student_b)
    assert created_b.status_code == 200, created_b.text

    view = client.get(f"{API}/requests/active", headers=headers_a)

    assert view.status_code == 200, view.text
    assert [r["id"] for r in view.json()["requests"]] == [created_a.json()["id"]]


def test_missing_body_returns_422(client, db, manager):
    _, headers, _ = _world(db, client, manager, "no_body")

    cases = [
        (f"{API}/delegates", headers),
        (f"{API}/requests", headers),
        (f"{API}/requests/{uuid.uuid4()}/handover", manager),
        (f"{API}/requests/{uuid.uuid4()}/cancel", headers),
        (f"{API}/requests/{uuid.uuid4()}/fail", manager),
    ]

    for url, auth in cases:
        res = client.post(url, headers=auth)
        assert res.status_code == 422, res.text
        assert _error_code(res) == "validation_error"
    assert db.execute("SELECT count(*) AS n FROM pickup_requests").fetchone()["n"] == 0
    assert db.execute("SELECT count(*) AS n FROM pickup_delegates").fetchone()["n"] == 0


def test_ready_accepts_empty_body(client, db, manager):
    student_id, headers, room_id = _world(db, client, manager, "empty_ready")
    rid = _request(client, headers, student_id).json()["id"]
    _, teacher = _teacher(db, client, "t_empty_ready", room_id)
    started = client.post(f"{API}/requests/{rid}/start-prep", headers=teacher)
    assert started.status_code == 200, started.text

    res = client.post(f"{API}/requests/{rid}/ready", headers=teacher)

    assert res.status_code == 200, res.text
    assert res.json()["status"] == "ready"


def test_gate_verification_normalizes_arabic_indic_digits(client, db, manager):
    student_id, headers, room_id = _world(db, client, manager, "gate_ok")
    rid = _request(client, headers, student_id).json()["id"]
    _teacher_run(db, client, "t_gate_ok", rid, room_id)

    res = client.post(
        f"{API}/requests/{rid}/handover",
        headers=manager,
        json={
            "verified_receiver_id_no": "١٠١٢٣٤٥٦٧٨",
            "verified_receiver_name": "أبو طالب",
            "verified_receiver_phone": "0550000000",
            "verified_receiver_relation": "الأب",
        },
    )

    assert res.status_code == 200, res.text
    body = res.json()
    assert body["status"] == "completed"
    assert "verified_receiver_id_no" not in body
    assert body["verified_receiver_id_no_masked"] == "*" * (len(_GUARDIAN_NID) - 4) + _GUARDIAN_NID[-4:]
    stored = db.execute(
        "SELECT verified_receiver_id_no FROM pickup_requests WHERE id = %s", (rid,)
    ).fetchone()
    assert stored["verified_receiver_id_no"] == _GUARDIAN_NID
    dismissals = db.execute(
        "SELECT * FROM dismissals WHERE pickup_request_id = %s", (rid,)
    ).fetchall()
    assert len(dismissals) == 1
    assert dismissals[0]["receiver_id_no"] == _GUARDIAN_NID
    assert dismissals[0]["method"] == "استلام من ولي الأمر"
    assert dismissals[0]["receiver_name"] == "أبو طالب"


def test_gate_verification_mismatched_id_returns_422(client, db, manager):
    student_id, headers, room_id = _world(db, client, manager, "gate_bad")
    rid = _request(client, headers, student_id).json()["id"]
    _teacher_run(db, client, "t_gate_bad", rid, room_id)

    res = client.post(
        f"{API}/requests/{rid}/handover",
        headers=manager,
        json={
            "verified_receiver_id_no": "1055677881",
            "verified_receiver_name": "شخص غريب",
            "verified_receiver_phone": "0550000000",
            "verified_receiver_relation": "خال",
        },
    )

    assert res.status_code == 422, res.text
    assert _error_code(res) == "receiver_id_mismatch"
    row = db.execute("SELECT status FROM pickup_requests WHERE id = %s", (rid,)).fetchone()
    assert row["status"] == "ready"
    assert db.execute(
        "SELECT count(*) AS n FROM dismissals WHERE pickup_request_id = %s", (rid,)
    ).fetchone()["n"] == 0


def _inflight_pickup_request(db, name: str, student_id: str, cid: str, event: threading.Event) -> dict:
    """Insert an uncommitted active-cycle row on a private connection, commit late."""
    g_uid = db.execute(
        "SELECT id FROM users WHERE username = %s", (f"g_{name}",)
    ).fetchone()["id"]
    outcome: dict = {}

    def _fill() -> None:
        with psycopg.connect(TEST_DATABASE_URL, row_factory=dict_row) as inflight:
            row = inflight.execute(
                "INSERT INTO pickup_requests (branch_id, student_id, requested_by_user_id, "
                "is_early_leave, pickup_date, status, notified_at, client_request_id) "
                "VALUES (%s, %s, %s, FALSE, %s, 'notified', now(), %s) RETURNING id",
                (MAIN_BRANCH, student_id, g_uid, datetime.now(_TZ).date(), cid),
            ).fetchone()
            outcome["request_id"] = row["id"]
            event.set()
            time.sleep(0.5)
            inflight.commit()

    return {"thread": threading.Thread(target=_fill, daemon=True), "outcome": outcome}


def _inflight_pickup_delegate(db, name: str, student_id: str, cid: str, event: threading.Event) -> dict:
    """Insert an uncommitted delegate row on a private connection, commit late."""
    g_uid = db.execute(
        "SELECT id FROM users WHERE username = %s", (f"g_{name}",)
    ).fetchone()["id"]
    outcome: dict = {}

    def _fill() -> None:
        with psycopg.connect(TEST_DATABASE_URL, row_factory=dict_row) as inflight:
            row = inflight.execute(
                "INSERT INTO pickup_delegates (branch_id, student_id, created_by_user_id, "
                "name, national_id, phone, relation, client_request_id) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s) RETURNING id",
                (MAIN_BRANCH, student_id, g_uid, "خاله سعد", "1055677881",
                 "0551234567", "خال", cid),
            ).fetchone()
            outcome["delegate_id"] = row["id"]
            event.set()
            time.sleep(0.5)
            inflight.commit()

    return {"thread": threading.Thread(target=_fill, daemon=True), "outcome": outcome}


def test_concurrent_request_replay_same_client_id_returns_same_row(db, client, manager):
    student_id, headers, _ = _world(db, client, manager, "conc_req")
    cid = str(uuid.uuid4())
    inserted = threading.Event()
    job = _inflight_pickup_request(db, "conc_req", student_id, cid, inserted)

    job["thread"].start()
    assert inserted.wait(timeout=5)
    res = _request(client, headers, student_id, client_request_id=cid)
    job["thread"].join(timeout=5)

    assert res.status_code == 200, res.text
    assert res.json()["id"] == str(job["outcome"]["request_id"])
    assert db.execute(
        "SELECT count(*) AS n FROM pickup_requests WHERE client_request_id = %s", (cid,)
    ).fetchone()["n"] == 1


def test_concurrent_delegate_replay_same_client_id_returns_same_row(db, client, manager):
    student_id, headers, _ = _world(db, client, manager, "conc_del")
    cid = str(uuid.uuid4())
    inserted = threading.Event()
    job = _inflight_pickup_delegate(db, "conc_del", student_id, cid, inserted)

    job["thread"].start()
    assert inserted.wait(timeout=5)
    res = client.post(f"{API}/delegates", json=_delegate_body(student_id, cid=cid), headers=headers)
    job["thread"].join(timeout=5)

    assert res.status_code == 200, res.text
    assert res.json()["id"] == str(job["outcome"]["delegate_id"])
    assert db.execute(
        "SELECT count(*) AS n FROM pickup_delegates WHERE client_request_id = %s", (cid,)
    ).fetchone()["n"] == 1


def test_concurrent_second_cycle_returns_pickup_already_active(db, client, manager):
    student_id, headers, _ = _world(db, client, manager, "conc_cycle")
    first_cid = str(uuid.uuid4())
    inserted = threading.Event()
    job = _inflight_pickup_request(db, "conc_cycle", student_id, first_cid, inserted)

    job["thread"].start()
    assert inserted.wait(timeout=5)
    res = _request(client, headers, student_id, client_request_id=str(uuid.uuid4()))
    job["thread"].join(timeout=5)

    assert res.status_code == 409, res.text
    assert _error_code(res) == "pickup_already_active"
    assert db.execute(
        "SELECT count(*) AS n FROM pickup_requests WHERE student_id = %s", (student_id,)
    ).fetchone()["n"] == 1


def test_no_raw_national_id_in_any_response(db, client, manager, supervisor):
    student_id, headers, room_id = _world(db, client, manager, "noid")
    _, teacher = _teacher(db, client, "t_noid", room_id)
    delegate_nid = "1099988877"
    delegate = client.post(
        f"{API}/delegates", json=_delegate_body(student_id, national_id=delegate_nid),
        headers=headers,
    ).json()

    created = _request(client, headers, student_id, delegate_id=delegate["id"])
    assert created.status_code == 200, created.text
    data = created.json()
    assert "delegate_national_id" not in data
    assert "guardian_national_id" not in data
    assert "verified_receiver_id_no" not in data
    masked = data["delegate_national_id_masked"]
    assert masked.startswith("*"), masked
    assert masked.endswith("8877"), masked
    assert delegate_nid not in str(data)

    for auth in (headers, teacher, supervisor):
        view = client.get(f"{API}/requests/active", headers=auth)
        assert view.status_code == 200, view.text
        items = view.json()["requests"]
        assert [r["id"] for r in items] == [data["id"]]
        for row in items:
            assert "delegate_national_id" not in row
            assert "guardian_national_id" not in row
            assert "verified_receiver_id_no" not in row
            row_masked = row["delegate_national_id_masked"]
            assert row_masked.startswith("*"), row_masked
            assert row_masked.endswith("8877"), row_masked
            expected_guardian = "*" * (len(_GUARDIAN_NID) - 4) + _GUARDIAN_NID[-4:]
            assert row["guardian_national_id_masked"] == expected_guardian
        assert delegate_nid not in str(view.json())
        assert _GUARDIAN_NID not in str(view.json())

    # Transitions check: start-prep -> ready -> handover
    prep = client.post(f"{API}/requests/{data['id']}/start-prep", headers=teacher)
    assert prep.status_code == 200, prep.text
    pdata = prep.json()
    assert "delegate_national_id" not in pdata
    assert "guardian_national_id" not in pdata
    assert "verified_receiver_id_no" not in pdata
    assert pdata["delegate_national_id_masked"] == masked
    assert delegate_nid not in str(pdata)

    ready = client.post(f"{API}/requests/{data['id']}/ready", headers=teacher, json={"prep_checklist": {}})
    assert ready.status_code == 200, ready.text
    rdata = ready.json()
    assert "delegate_national_id" not in rdata
    assert "guardian_national_id" not in rdata
    assert "verified_receiver_id_no" not in rdata
    assert rdata["delegate_national_id_masked"] == masked
    assert delegate_nid not in str(rdata)

    hand = client.post(
        f"{API}/requests/{data['id']}/handover",
        headers=supervisor,
        json={
            "verified_receiver_id_no": delegate_nid,
            "verified_receiver_name": "خاله سعد",
            "verified_receiver_phone": "0551234567",
            "verified_receiver_relation": "خال",
        },
    )
    assert hand.status_code == 200, hand.text
    hdata = hand.json()
    assert "delegate_national_id" not in hdata
    assert "guardian_national_id" not in hdata
    assert "verified_receiver_id_no" not in hdata
    assert hdata["verified_receiver_id_no_masked"] == masked
    assert delegate_nid not in str(hdata)
    assert _GUARDIAN_NID not in str(hdata)

    # Scoped GET by id after handover for all three roles: guardian, teacher, supervisor
    for auth in (headers, teacher, supervisor):
        single = client.get(f"{API}/requests/{data['id']}", headers=auth)
        assert single.status_code == 200, single.text
        sdata = single.json()
        assert "delegate_national_id" not in sdata
        assert "guardian_national_id" not in sdata
        assert "verified_receiver_id_no" not in sdata
        assert sdata["verified_receiver_id_no_masked"] == masked
        assert delegate_nid not in str(sdata)
        assert _GUARDIAN_NID not in str(sdata)


def test_get_request_by_id_scope_matrix(db, client, manager):
    student_id, headers, room_one = _world(db, client, manager, "scope")
    room_two = _room(client, manager, "قاعة سكوب أخرى")
    foreign_student = _student(client, manager, "طالب سكوب آخر", room_two)
    _, guardian_two = _guardian(db, client, "g_scope_other", [foreign_student])
    _, teacher_room_one = _teacher(db, client, "t_scope_in", room_one)
    _, teacher_room_two = _teacher(db, client, "t_scope_out", room_two)

    rid = _request(client, headers, student_id).json()["id"]

    ok_guardian = client.get(f"{API}/requests/{rid}", headers=headers)
    assert ok_guardian.status_code == 200, ok_guardian.text
    assert ok_guardian.json()["id"] == rid

    foreign = client.get(f"{API}/requests/{rid}", headers=guardian_two)
    assert foreign.status_code == 403, foreign.text

    in_room = client.get(f"{API}/requests/{rid}", headers=teacher_room_one)
    assert in_room.status_code == 200, in_room.text

    out_room = client.get(f"{API}/requests/{rid}", headers=teacher_room_two)
    assert out_room.status_code == 403, out_room.text

    missing = client.get(f"{API}/requests/{uuid.uuid4()}", headers=headers)
    assert missing.status_code == 404, missing.text
    assert _error_code(missing) == "pickup_not_found"


def test_terminal_status_readable_by_guardian(db, client, manager):
    student_id, headers, room_id = _world(db, client, manager, "terminal")
    rid = _request(client, headers, student_id).json()["id"]
    _teacher_run(db, client, "t_terminal", rid, room_id)

    handed = client.post(
        f"{API}/requests/{rid}/handover",
        headers=manager,
        json={
            "verified_receiver_id_no": _GUARDIAN_NID,
            "verified_receiver_name": "أبو طالب",
            "verified_receiver_phone": "0550000000",
            "verified_receiver_relation": "الأب",
        },
    )
    assert handed.status_code == 200, handed.text
    assert handed.json()["status"] == "completed"
    expected_guardian = "*" * (len(_GUARDIAN_NID) - 4) + _GUARDIAN_NID[-4:]
    hdata = handed.json()
    assert "verified_receiver_id_no" not in hdata
    assert hdata["verified_receiver_id_no_masked"] == expected_guardian
    assert _GUARDIAN_NID not in str(hdata)

    active = client.get(f"{API}/requests/active", headers=headers)
    assert active.status_code == 200, active.text
    assert [r["id"] for r in active.json()["requests"]] == []

    single = client.get(f"{API}/requests/{rid}", headers=headers)
    assert single.status_code == 200, single.text
    row = single.json()
    assert row["status"] == "completed"
    assert "delegate_national_id" not in row
    assert "guardian_national_id" not in row
    assert "verified_receiver_id_no" not in row
    assert row["guardian_national_id_masked"] == expected_guardian
    assert row["verified_receiver_id_no_masked"] == expected_guardian
    assert row["delegate_national_id_masked"] is None
    assert _GUARDIAN_NID not in str(row)

    # Room teacher can also read the completed request without PII leak (L2)
    _, room_teacher = _teacher(db, client, "t_term_read", room_id)
    t_single = client.get(f"{API}/requests/{rid}", headers=room_teacher)
    assert t_single.status_code == 200, t_single.text
    t_row = t_single.json()
    assert t_row["status"] == "completed"
    assert "delegate_national_id" not in t_row
    assert "guardian_national_id" not in t_row
    assert "verified_receiver_id_no" not in t_row
    assert t_row["verified_receiver_id_no_masked"] == expected_guardian
    assert _GUARDIAN_NID not in str(t_row)


def test_double_prefix_returns_404(client):
    """S-B7: Ensure double mount /api/v1/api/v1/pickup is removed and returns 404."""
    resp = client.post("/api/v1/api/v1/pickup/requests", json={})
    assert resp.status_code == 404

