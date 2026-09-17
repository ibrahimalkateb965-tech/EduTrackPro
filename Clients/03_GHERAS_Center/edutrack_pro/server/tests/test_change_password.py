"""Tests for POST /api/v1/auth/change-password and reset-password endpoints."""

from __future__ import annotations

from tests.conftest import make_user


def test_change_password_success(client, db):
    user_id = make_user(db, "manager_pwd_test", "manager", password="OldPassword123!")
    db.commit()

    # Login with old password
    login_res = client.post("/api/v1/auth/login", json={"username": "manager_pwd_test", "password": "OldPassword123!"})
    assert login_res.status_code == 200
    token = login_res.json()["token"]

    # Change password
    change_res = client.post(
        "/api/v1/auth/change-password",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "current_password": "OldPassword123!",
            "new_password": "NewSecurePassword456!",
            "confirm_password": "NewSecurePassword456!",
        },
    )
    assert change_res.status_code == 200
    assert change_res.json()["status"] == "ok"

    # Verify old password no longer works
    fail_login = client.post("/api/v1/auth/login", json={"username": "manager_pwd_test", "password": "OldPassword123!"})
    assert fail_login.status_code == 401

    # Verify new password works
    success_login = client.post("/api/v1/auth/login", json={"username": "manager_pwd_test", "password": "NewSecurePassword456!"})
    assert success_login.status_code == 200

    # Verify audit log entry
    audit = db.execute(
        "SELECT action, entity, entity_id, details_json FROM audit_log WHERE entity = 'user' AND action = 'change_password' AND entity_id = %s",
        (user_id,),
    ).fetchone()
    assert audit is not None
    assert "password" not in audit["details_json"]


def test_change_password_invalid_current(client, db):
    make_user(db, "wrong_pwd_user", "supervisor", password="CorrectPassword123!")
    db.commit()

    login_res = client.post("/api/v1/auth/login", json={"username": "wrong_pwd_user", "password": "CorrectPassword123!"})
    token = login_res.json()["token"]

    change_res = client.post(
        "/api/v1/auth/change-password",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "current_password": "WrongPassword999!",
            "new_password": "NewSecurePassword456!",
        },
    )
    assert change_res.status_code == 400
    assert change_res.json()["error"]["code"] == "invalid_credentials"


def test_change_password_short_or_mismatch(client, db):
    make_user(db, "short_pwd_user", "manager", password="CorrectPassword123!")
    db.commit()

    login_res = client.post("/api/v1/auth/login", json={"username": "short_pwd_user", "password": "CorrectPassword123!"})
    token = login_res.json()["token"]

    # Short password
    res_short = client.post(
        "/api/v1/auth/change-password",
        headers={"Authorization": f"Bearer {token}"},
        json={"current_password": "CorrectPassword123!", "new_password": "short"},
    )
    assert res_short.status_code == 400

    # Mismatching confirm password
    res_mismatch = client.post(
        "/api/v1/auth/change-password",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "current_password": "CorrectPassword123!",
            "new_password": "NewSecurePassword456!",
            "confirm_password": "DifferentPassword789!",
        },
    )
    assert res_mismatch.status_code == 400
