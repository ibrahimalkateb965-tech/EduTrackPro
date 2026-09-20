"""Shared fixtures: real PostgreSQL (TEST_DATABASE_URL), app under TestClient, seeded users.

The reviewer runs these against an embedded PostgreSQL 16 with 001 through 006 applied.
"""

from __future__ import annotations

import os
import uuid

import psycopg
import pytest
from psycopg.rows import dict_row

TEST_DATABASE_URL = os.environ.get("TEST_DATABASE_URL")
if not TEST_DATABASE_URL:
    pytest.skip("TEST_DATABASE_URL not set", allow_module_level=True)

os.environ.setdefault("DATABASE_URL", TEST_DATABASE_URL)
os.environ.setdefault("JWT_SECRET", "test-secret-not-for-production-0123456789")
os.environ.setdefault("JWT_TTL_MINUTES", "60")

from fastapi.testclient import TestClient  # noqa: E402

from edutrack_api.auth import hash_password  # noqa: E402
from edutrack_api.main import app  # noqa: E402

MAIN_BRANCH = uuid.UUID("00000000-0000-0000-0000-000000000001")
KEEP_TABLES = {"branches", "expense_categories", "message_templates", "system_settings"}


def _truncate_all(conn: psycopg.Connection) -> None:
    tables = [
        r["table_name"]
        for r in conn.execute(
            "SELECT table_name FROM information_schema.tables "
            "WHERE table_schema='public' AND table_type='BASE TABLE'"
        ).fetchall()
    ]
    targets = [t for t in tables if t not in KEEP_TABLES]
    conn.execute("TRUNCATE " + ", ".join(f'"{t}"' for t in targets) + " RESTART IDENTITY CASCADE")
    conn.execute("SELECT setval('receipts_receipt_no_seq', 1, false)")
    conn.commit()


@pytest.fixture(scope="session")
def client():
    with TestClient(app) as tc:
        yield tc


@pytest.fixture()
def db():
    with psycopg.connect(TEST_DATABASE_URL, row_factory=dict_row, autocommit=False) as conn:
        _truncate_all(conn)
        yield conn
        conn.rollback()


def make_user(conn, username: str, role: str, password: str = "Secret123!") -> uuid.UUID:
    row = conn.execute(
        "INSERT INTO users (username, password_hash, role) VALUES (%s, %s, %s) RETURNING id",
        (username, hash_password(password), role),
    ).fetchone()
    conn.commit()
    return row["id"]


def login(client, username: str, password: str = "Secret123!") -> dict:
    res = client.post("/api/v1/auth/login", json={"username": username, "password": password})
    assert res.status_code == 200, res.text
    token = res.json()["token"]
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture()
def manager(db, client):
    make_user(db, "manager1", "manager")
    return login(client, "manager1")


@pytest.fixture()
def supervisor(db, client):
    make_user(db, "super1", "supervisor")
    return login(client, "super1")
