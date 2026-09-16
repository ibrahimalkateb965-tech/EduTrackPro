from datetime import UTC, datetime

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel

from edutrack_api.auth import current_user, issue_token, verify_password
from edutrack_api.config import get_settings
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.serializers import row_to_json

router = APIRouter()


class LoginBody(BaseModel):
    username: str
    password: str


def _user_name(conn, user: dict) -> str:
    if user.get("staff_id"):
        staff = conn.execute("SELECT name FROM staff WHERE id = %s AND deleted_at IS NULL", (user["staff_id"],)).fetchone()
        if staff:
            return staff["name"]
    return user["username"]


@router.post("/auth/login")
def login(body: LoginBody, conn=Depends(get_conn)):
    user = conn.execute(
        "SELECT id, username, password_hash, role, staff_id, is_active "
        "FROM users WHERE username = %s AND deleted_at IS NULL",
        (body.username,),
    ).fetchone()
    if not user or not user["is_active"] or not verify_password(user["password_hash"], body.password):
        raise ApiError(401, "unauthorized", "بيانات الدخول غير صحيحة")
    user = dict(user)
    return {
        "token": issue_token(user, get_settings()),
        "user": {key: str(user[key]) if key == "id" else user[key] for key in ("id", "username", "role")} | {"name": _user_name(conn, user)},
    }


@router.post("/auth/logout", status_code=204)
def logout(user: dict = Depends(current_user), conn=Depends(get_conn)) -> Response:
    conn.execute(
        "INSERT INTO revoked_tokens (jti, expires_at) VALUES (%s, %s) ON CONFLICT (jti) DO NOTHING",
        (user["_jti"], datetime.fromtimestamp(user["_exp"], UTC)),
    )
    return Response(status_code=204)


@router.get("/me")
def me(user: dict = Depends(current_user), conn=Depends(get_conn)):
    permissions = conn.execute(
        "SELECT attendance, daily_evaluation, monthly_evaluation, students, finance "
        "FROM user_permissions WHERE user_id = %s AND deleted_at IS NULL",
        (user["id"],),
    ).fetchone()
    flags = {key: False for key in ("attendance", "daily_evaluation", "monthly_evaluation", "students", "finance")}
    if permissions:
        flags.update(dict(permissions))
    result = {key: user[key] for key in ("id", "username", "role")}
    result["name"] = _user_name(conn, user)
    result["permissions"] = flags
    return row_to_json(result)
