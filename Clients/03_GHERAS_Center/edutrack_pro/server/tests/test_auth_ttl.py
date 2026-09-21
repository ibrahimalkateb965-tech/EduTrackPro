"""D4: per-role JWT TTL — mobile roles get MOBILE_JWT_TTL_MINUTES, dashboard roles keep JWT_TTL_MINUTES.

D4-a: logout purges expired rows from revoked_tokens (30-day mobile tokens would otherwise pile up).
"""

from __future__ import annotations

import os
import uuid

import jwt

from tests.conftest import login, make_user


def _ttl_seconds(headers: dict) -> int:
    token = headers["Authorization"].removeprefix("Bearer ")
    claims = jwt.decode(token, os.environ["JWT_SECRET"], algorithms=["HS256"])
    return claims["exp"] - claims["iat"]


def test_mobile_roles_get_mobile_ttl(db, client):
    for username, role in (("t1", "teacher"), ("g1", "guardian")):
        make_user(db, username, role)
        assert _ttl_seconds(login(client, username)) == 43200 * 60  # default, conftest does not override


def test_dashboard_roles_keep_jwt_ttl(db, client, manager, supervisor):
    for headers in (manager, supervisor):
        assert _ttl_seconds(headers) == 60 * 60  # conftest pins JWT_TTL_MINUTES=60


def test_logout_purges_expired_revoked_tokens(db, client, manager):
    stale = uuid.uuid4()
    db.execute(
        "INSERT INTO revoked_tokens (jti, expires_at) VALUES (%s, now() - interval '1 day')",
        (stale,),
    )
    db.commit()
    res = client.post("/api/v1/auth/logout", headers=manager)
    assert res.status_code == 204
    rows = db.execute("SELECT jti FROM revoked_tokens").fetchall()
    jtis = {r["jti"] for r in rows}
    assert stale not in jtis  # purged
    assert len(jtis) == 1  # the manager's own jti, just revoked
