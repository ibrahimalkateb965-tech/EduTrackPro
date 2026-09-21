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
MOBILE_ROLES = frozenset({"teacher", "guardian"})


def hash_password(plain: str) -> str:
    return _password_hasher.hash(plain)


def verify_password(password_hash: str, plain: str) -> bool:
    try:
        return _password_hasher.verify(password_hash, plain)
    except (Argon2Error, ValueError):  # ValueError covers InvalidHashError (placeholder hash)
        return False


def token_ttl_minutes(role: str, settings) -> int:
    return settings.mobile_jwt_ttl_minutes if role in MOBILE_ROLES else settings.jwt_ttl_minutes


def issue_token(user_row: dict, settings) -> str:
    now = datetime.now(UTC)
    payload = {
        "sub": str(user_row["id"]),
        "role": user_row["role"],
        "jti": str(uuid4()),
        "iat": now,
        "exp": now + timedelta(minutes=token_ttl_minutes(user_row["role"], settings)),
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

    if user["role"] == "manager":
        user["permissions"] = {
            "attendance": True,
            "daily_evaluation": True,
            "monthly_evaluation": True,
            "students": True,
            "finance": True,
        }
    else:
        perm_row = conn.execute(
            "SELECT attendance, daily_evaluation, monthly_evaluation, students, finance "
            "FROM user_permissions WHERE user_id = %s AND deleted_at IS NULL",
            (subject,),
        ).fetchone()
        if perm_row:
            user["permissions"] = {
                "attendance": bool(perm_row["attendance"]),
                "daily_evaluation": bool(perm_row["daily_evaluation"]),
                "monthly_evaluation": bool(perm_row["monthly_evaluation"]),
                "students": bool(perm_row["students"]),
                "finance": bool(perm_row["finance"]),
            }
        else:
            user["permissions"] = {
                "attendance": False,
                "daily_evaluation": False,
                "monthly_evaluation": False,
                "students": False,
                "finance": False,
            }
    return user


def require_roles(*roles: str):
    def dependency(user: dict = Depends(current_user)) -> dict:
        if user["role"] not in roles:
            raise ApiError(403, "forbidden", "ليس لديك صلاحية")
        return user

    return dependency


def require_permission(perm: str):
    def dependency(user: dict = Depends(current_user)) -> dict:
        if user["role"] == "manager":
            return user
        perms = user.get("permissions", {})
        if not perms.get(perm):
            raise ApiError(403, "forbidden", f"ليس لديك صلاحية الوصول إلى {perm}")
        return user

    return dependency

