from __future__ import annotations

import os
import uuid
import pytest
from datetime import datetime, timezone, timedelta

from tests.conftest import make_user, login
from edutrack_api.auth import hash_password
from edutrack_api.services.whatsapp import hash_otp_code


def _ensure_009_applied(db):
    # Ensure migration 008
    col = db.execute(
        "SELECT 1 FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'student_id'"
    ).fetchone()
    if not col:
        mig_path = os.path.normpath(
            os.path.join(os.path.dirname(__file__), "..", "..", "db", "postgres", "008_national_id_auth_and_student_role.sql")
        )
        if os.path.exists(mig_path):
            with open(mig_path, "r", encoding="utf-8") as f:
                db.execute(f.read())
                db.commit()

    # Ensure migration 009
    tbl = db.execute(
        "SELECT 1 FROM information_schema.tables WHERE table_name = 'auth_otps'"
    ).fetchone()
    if not tbl:
        mig_path = os.path.normpath(
            os.path.join(os.path.dirname(__file__), "..", "..", "db", "postgres", "009_whatsapp_auth_otp.sql")
        )
        if os.path.exists(mig_path):
            with open(mig_path, "r", encoding="utf-8") as f:
                db.execute(f.read())
                db.commit()


def test_whatsapp_otp_request_and_verify(db, client):
    _ensure_009_applied(db)

    # 1. Create a guardian with national_id and phone
    uid = uuid.uuid4()
    nat_id = "1098765432"
    phone = "0551234567"
    db.execute(
        "INSERT INTO users (id, username, password_hash, role, national_id, phone) VALUES (%s, %s, %s, %s, %s, %s)",
        (uid, "g_otp_user", hash_password("Pass123!"), "guardian", nat_id, phone),
    )
    db.commit()

    # 2. Request OTP via WhatsApp
    res = client.post(
        "/api/v1/auth/request-otp",
        json={"national_id": nat_id, "role": "guardian"}
    )
    assert res.status_code == 200
    data = res.json()
    assert "session_id" in data
    assert data["phone_masked"] == "******4567"
    assert data["expires_in"] == 300
    session_id = data["session_id"]

    # 3. Retrieve the generated code from database (since we are in test/mock mode)
    otp_row = db.execute(
        "SELECT id, otp_code_hash, expires_at FROM auth_otps WHERE id = %s",
        (session_id,)
    ).fetchone()
    assert otp_row is not None

    # 4. Verify with incorrect code first
    res_bad = client.post(
        "/api/v1/auth/verify-otp",
        json={"session_id": session_id, "otp_code": "0000"}
    )
    assert res_bad.status_code == 401
    assert "غير صحيح" in res_bad.json()["error"]["message"]

    # 5. Overwrite the code hash with a known test code "4321" to test exact verification
    db.execute(
        "UPDATE auth_otps SET otp_code_hash = %s WHERE id = %s",
        (hash_otp_code("4321"), session_id)
    )
    db.commit()

    # 6. Verify with correct code
    res_ok = client.post(
        "/api/v1/auth/verify-otp",
        json={"session_id": session_id, "otp_code": "4321"}
    )
    assert res_ok.status_code == 200
    res_data = res_ok.json()
    assert "token" in res_data
    assert res_data["user"]["role"] == "guardian"
    assert res_data["user"]["id"] == str(uid)

    # 7. Cannot reuse already used OTP
    res_reuse = client.post(
        "/api/v1/auth/verify-otp",
        json={"session_id": session_id, "otp_code": "4321"}
    )
    assert res_reuse.status_code == 400
    assert "مسبقاً" in res_reuse.json()["error"]["message"]


def test_whatsapp_otp_cooldown_rate_limit(db, client):
    _ensure_009_applied(db)

    uid = uuid.uuid4()
    nat_id = "1055555555"
    db.execute(
        "INSERT INTO users (id, username, password_hash, role, national_id, phone) VALUES (%s, %s, %s, %s, %s, %s)",
        (uid, "g_cool_user", hash_password("Pass123!"), "guardian", nat_id, "0501112233"),
    )
    db.commit()

    # First request
    res1 = client.post("/api/v1/auth/request-otp", json={"national_id": nat_id, "role": "guardian"})
    assert res1.status_code == 200

    # Immediate second request triggers 429
    res2 = client.post("/api/v1/auth/request-otp", json={"national_id": nat_id, "role": "guardian"})
    assert res2.status_code == 429
    assert "60 ثانية" in res2.json()["error"]["message"]


def test_whatsapp_otp_phone_inheritance_and_not_found(db, client):
    _ensure_009_applied(db)

    # 1. Non-existent user returns 404
    res_none = client.post("/api/v1/auth/request-otp", json={"national_id": "9999999999", "role": "guardian"})
    assert res_none.status_code == 404

    # 2. User with no phone in users or linked entities returns 400
    uid_nophone = uuid.uuid4()
    db.execute(
        "INSERT INTO users (id, username, password_hash, role, national_id, phone) VALUES (%s, %s, %s, %s, %s, NULL)",
        (uid_nophone, "no_phone_user", hash_password("Pass123!"), "guardian", "1088888888"),
    )
    db.commit()

    res_nophone = client.post("/api/v1/auth/request-otp", json={"national_id": "1088888888", "role": "guardian"})
    assert res_nophone.status_code == 400
    assert "رقم هاتف" in res_nophone.json()["error"]["message"]
