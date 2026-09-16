from datetime import UTC, datetime, timedelta
from uuid import uuid4

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import Argon2Error
from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from edutrack_api.config import get_settings
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError

_password_hasher = PasswordHasher()
_bearer = HTTPBearer(auto_error=False)


def hash_password(plain: str) -> str:
    return _password_hasher.hash(plain)


def verify_password(password_hash: str, plain: str) -> bool:
    try:
        return _password_hasher.verify(password_hash, plain)
    except (Argon2Error, ValueError):  # ValueError covers InvalidHashError (placeholder hash)
        return False


def issue_token(user_row: dict, settings) -> str:
    now = datetime.now(UTC)
    payload = {
        "sub": str(user_row["id"]),
        "role": user_row["role"],
        "jti": str(uuid4()),
        "iat": now,
        "exp": now + timedelta(minutes=settings.jwt_ttl_minutes),
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm="HS256")


def decode_token(token: str, settings) -> dict:
    try:
        return jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
    except jwt.PyJWTError as exc:
        raise ApiError(401, "unauthorized", "غير مصرح") from exc


def current_user(
    request: Request,
    conn=Depends(get_conn),
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> dict:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise ApiError(401, "unauthorized", "غير مصرح")
    claims = decode_token(credentials.credentials, get_settings())
    jti = claims.get("jti")
    subject = claims.get("sub")
    if not jti or not subject:
        raise ApiError(401, "unauthorized", "غير مصرح")
    revoked = conn.execute("SELECT 1 FROM revoked_tokens WHERE jti = %s", (jti,)).fetchone()
    user = conn.execute(
        "SELECT id, branch_id, username, role, staff_id, guardian_id, room_id, is_active "
        "FROM users WHERE id = %s AND deleted_at IS NULL",
        (subject,),
    ).fetchone()
    if revoked or not user or not user["is_active"]:
        raise ApiError(401, "unauthorized", "غير مصرح")
    user = dict(user)
    user["_jti"] = jti
    user["_exp"] = claims["exp"]
    return user


def require_roles(*roles: str):
    def dependency(user: dict = Depends(current_user)) -> dict:
        if user["role"] not in roles:
            raise ApiError(403, "forbidden", "ليس لديك صلاحية")
        return user

    return dependency
