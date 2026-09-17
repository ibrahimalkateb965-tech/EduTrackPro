from datetime import UTC, datetime
from uuid import UUID

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel

from edutrack_api.audit import write_audit
from edutrack_api.auth import current_user, hash_password, issue_token, verify_password
from edutrack_api.config import get_settings
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.serializers import row_to_json

router = APIRouter()


class LoginBody(BaseModel):
    username: str
    password: str


class ChangePasswordBody(BaseModel):
    current_password: str
    new_password: str
    confirm_password: str | None = None


class ResetUserPasswordBody(BaseModel):
    new_password: str


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
    result = {key: user[key] for key in ("id", "username", "role")}
    result["name"] = _user_name(conn, user)
    result["permissions"] = user.get("permissions", {
        "attendance": user["role"] == "manager",
        "daily_evaluation": user["role"] == "manager",
        "monthly_evaluation": user["role"] == "manager",
        "students": user["role"] == "manager",
        "finance": user["role"] == "manager",
    })
    return row_to_json(result)


@router.post("/auth/change-password")
def change_password(body: ChangePasswordBody, user: dict = Depends(current_user), conn=Depends(get_conn)):
    if len(body.new_password) < 8:
        raise ApiError(400, "validation_error", "كلمة المرور الجديدة يجب ألا تقل عن 8 أحرف")
    if body.confirm_password is not None and body.new_password != body.confirm_password:
        raise ApiError(400, "validation_error", "كلمة المرور وتأكيدها غير متطابقين")
    if body.current_password == body.new_password:
        raise ApiError(400, "validation_error", "كلمة المرور الجديدة يجب أن تكون مختلفة عن الحالية")

    row = conn.execute(
        "SELECT id, username, password_hash FROM users WHERE id = %s AND deleted_at IS NULL",
        (user["id"],),
    ).fetchone()
    if not row or not verify_password(row["password_hash"], body.current_password):
        raise ApiError(400, "invalid_credentials", "كلمة المرور الحالية غير صحيحة")

    new_hash = hash_password(body.new_password)
    conn.execute(
        "UPDATE users SET password_hash = %s, updated_at = now() WHERE id = %s",
        (new_hash, user["id"]),
    )
    write_audit(conn, user["id"], "change_password", "user", user["id"], {"username": user["username"]})
    return {"status": "ok", "message": "تم تغيير كلمة المرور بنجاح"}


@router.post("/auth/reset-password/{user_id}")
def reset_user_password(user_id: UUID, body: ResetUserPasswordBody, user: dict = Depends(current_user), conn=Depends(get_conn)):
    if user["role"] != "manager":
        raise ApiError(403, "forbidden", "هذه الميزة مخصصة لمدير النظام فقط")
    if len(body.new_password) < 8:
        raise ApiError(400, "validation_error", "كلمة المرور الجديدة يجب ألا تقل عن 8 أحرف")

    target_user = conn.execute(
        "SELECT id, username FROM users WHERE id = %s AND deleted_at IS NULL",
        (user_id,),
    ).fetchone()
    if not target_user:
        raise ApiError(404, "not_found", "المستخدم غير موجود")

    new_hash = hash_password(body.new_password)
    conn.execute(
        "UPDATE users SET password_hash = %s, updated_at = now() WHERE id = %s",
        (new_hash, user_id),
    )
    write_audit(conn, user["id"], "reset_password", "user", user_id, {"username": target_user["username"]})
    return {"status": "ok", "message": f"تم إعادة تعيين كلمة المرور للمستخدم {target_user['username']} بنجاح"}

