from datetime import UTC, datetime, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel

from edutrack_api.audit import write_audit
from edutrack_api.auth import current_user, hash_password, issue_token, verify_password
from edutrack_api.config import get_settings
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.serializers import row_to_json
from edutrack_api.services.whatsapp import (
    generate_otp_code,
    hash_otp_code,
    mask_phone,
    normalize_saudi_phone,
    send_whatsapp_otp,
    verify_otp_code,
)

router = APIRouter()


class LoginBody(BaseModel):
    username: str
    password: str
    role: str | None = None


class RequestOtpBody(BaseModel):
    national_id: str
    role: str


class VerifyOtpBody(BaseModel):
    session_id: UUID
    otp_code: str
    role: str | None = None


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
    if user.get("guardian_id"):
        guardian = conn.execute("SELECT name FROM guardians WHERE id = %s AND deleted_at IS NULL", (user["guardian_id"],)).fetchone()
        if guardian:
            return guardian["name"]
    if user.get("student_id"):
        student = conn.execute("SELECT name FROM students WHERE id = %s AND deleted_at IS NULL", (user["student_id"],)).fetchone()
        if student:
            return student["name"]
    return user["username"]


def _user_phone(conn, user: dict) -> str | None:
    if user.get("phone"):
        return user["phone"]
    if user.get("staff_id"):
        staff = conn.execute("SELECT phone FROM staff WHERE id = %s AND deleted_at IS NULL", (user["staff_id"],)).fetchone()
        if staff and staff["phone"]:
            return staff["phone"]
    if user.get("guardian_id"):
        guardian = conn.execute("SELECT phone FROM guardians WHERE id = %s AND deleted_at IS NULL", (user["guardian_id"],)).fetchone()
        if guardian and guardian["phone"]:
            return guardian["phone"]
    if user.get("student_id"):
        student = conn.execute(
            "SELECT guardian_phone, father_phone, mother_phone FROM students WHERE id = %s AND deleted_at IS NULL",
            (user["student_id"],),
        ).fetchone()
        if student:
            return student["guardian_phone"] or student["father_phone"] or student["mother_phone"]
    return None


def _phone_candidates(ident: str) -> list[str]:
    candidates = [ident]
    try:
        norm = normalize_saudi_phone(ident)
        candidates.append(norm)
        if norm.startswith("966") and len(norm) == 12:
            candidates.append("0" + norm[3:])
            candidates.append(norm[3:])
    except ValueError:
        pass
    return list(dict.fromkeys(candidates))


@router.post("/auth/login")
def login(body: LoginBody, conn=Depends(get_conn)):
    ident = body.username.strip()
    role_filter = body.role.strip().lower() if body.role else None
    phones = _phone_candidates(ident)

    if role_filter:
        query = (
            "SELECT u.id, u.username, u.password_hash, u.role, u.staff_id, u.guardian_id, u.room_id, u.student_id, u.is_active "
            "FROM users u "
            "LEFT JOIN staff s ON s.id = u.staff_id AND s.deleted_at IS NULL "
            "LEFT JOIN guardians g ON g.id = u.guardian_id AND g.deleted_at IS NULL "
            "WHERE (u.username = %s OR u.national_id = %s OR u.phone = ANY(%s) OR s.phone = ANY(%s) OR g.phone = ANY(%s)) "
            "  AND u.role = %s AND u.deleted_at IS NULL "
            "ORDER BY CASE WHEN u.username = %s OR u.national_id = %s THEN 0 "
            "              WHEN u.phone = ANY(%s) THEN 1 ELSE 2 END, u.id"
        )
        params = (ident, ident, phones, phones, phones, role_filter, ident, ident, phones)
    else:
        query = (
            "SELECT u.id, u.username, u.password_hash, u.role, u.staff_id, u.guardian_id, u.room_id, u.student_id, u.is_active "
            "FROM users u "
            "LEFT JOIN staff s ON s.id = u.staff_id AND s.deleted_at IS NULL "
            "LEFT JOIN guardians g ON g.id = u.guardian_id AND g.deleted_at IS NULL "
            "WHERE (u.username = %s OR u.national_id = %s OR u.phone = ANY(%s) OR s.phone = ANY(%s) OR g.phone = ANY(%s)) "
            "  AND u.deleted_at IS NULL "
            "ORDER BY CASE WHEN u.username = %s OR u.national_id = %s THEN 0 "
            "              WHEN u.phone = ANY(%s) THEN 1 ELSE 2 END, u.id"
        )
        params = (ident, ident, phones, phones, phones, ident, ident, phones)

    candidates = conn.execute(query, params).fetchall()

    authenticated_user = None
    for row in candidates:
        if row["is_active"] and verify_password(row["password_hash"], body.password):
            authenticated_user = dict(row)
            break

    if not authenticated_user:
        raise ApiError(401, "unauthorized", "بيانات الدخول غير صحيحة")

    user = authenticated_user
    return {
        "token": issue_token(user, get_settings()),
        "user": {key: str(user[key]) if key == "id" else user[key] for key in ("id", "username", "role")} | {"name": _user_name(conn, user)},
    }


@router.post("/auth/request-otp")
def request_otp(body: RequestOtpBody, conn=Depends(get_conn)):
    ident = body.national_id.strip()
    role_filter = body.role.strip().lower()
    phones = _phone_candidates(ident)

    user = conn.execute(
        "SELECT u.id, u.username, u.role, u.staff_id, u.guardian_id, u.room_id, u.student_id, u.phone, u.is_active "
        "FROM users u "
        "LEFT JOIN staff s ON s.id = u.staff_id AND s.deleted_at IS NULL "
        "LEFT JOIN guardians g ON g.id = u.guardian_id AND g.deleted_at IS NULL "
        "WHERE (u.national_id = %s OR u.username = %s OR u.phone = ANY(%s) OR s.phone = ANY(%s) OR g.phone = ANY(%s)) "
        "  AND u.role = %s AND u.deleted_at IS NULL "
        "ORDER BY u.id LIMIT 1",
        (ident, ident, phones, phones, phones, role_filter),
    ).fetchone()

    if not user or not user["is_active"]:
        raise ApiError(404, "not_found", "لا يوجد حساب مسجل برقم الهوية أو الجوال المحدد لهذا الدور")

    raw_phone = _user_phone(conn, user)
    if not raw_phone:
        raise ApiError(400, "phone_missing", "لا يوجد رقم هاتف مسجل لهذا الحساب، يرجى مراجعة إدارة المركز")

    try:
        norm_phone = normalize_saudi_phone(raw_phone)
    except ValueError as e:
        raise ApiError(400, "invalid_phone", str(e)) from None

    # Cooldown check: 60 seconds
    recent_otp = conn.execute(
        "SELECT id, created_at FROM auth_otps "
        "WHERE user_id = %s AND is_used = false AND created_at > now() - interval '60 seconds' "
        "ORDER BY created_at DESC LIMIT 1",
        (user["id"],),
    ).fetchone()
    if recent_otp:
        raise ApiError(429, "rate_limited", "يرجى الانتظار 60 ثانية قبل طلب رمز جديد")

    # Invalidate previous unused OTPs for this user
    conn.execute(
        "UPDATE auth_otps SET is_used = true WHERE user_id = %s AND is_used = false",
        (user["id"],),
    )

    code = generate_otp_code(digits=4)
    code_hash = hash_otp_code(code)

    otp_row = conn.execute(
        "INSERT INTO auth_otps (user_id, phone, otp_code_hash, expires_at) "
        "VALUES (%s, %s, %s, now() + interval '5 minutes') RETURNING id, expires_at",
        (user["id"], norm_phone, code_hash),
    ).fetchone()

    # Send message via WhatsApp service
    send_whatsapp_otp(norm_phone, code, get_settings())

    masked = mask_phone(norm_phone)
    return {
        "session_id": str(otp_row["id"]),
        "phone_masked": masked,
        "expires_in": 300,
        "resend_cooldown": 60,
    }


@router.post("/auth/verify-otp")
def verify_otp(body: VerifyOtpBody, conn=Depends(get_conn)):
    otp = conn.execute(
        "SELECT id, user_id, phone, otp_code_hash, attempts, is_used, expires_at "
        "FROM auth_otps WHERE id = %s",
        (body.session_id,),
    ).fetchone()

    if not otp:
        raise ApiError(404, "not_found", "جلسة التحقق غير موجودة أو انتهت")

    if otp["is_used"]:
        raise ApiError(400, "otp_used", "تم استخدام رمز التحقق هذا مسبقاً، يرجى طلب رمز جديد")

    if otp["expires_at"] < datetime.now(timezone.utc):
        raise ApiError(400, "otp_expired", "انتهت صلاحية رمز التحقق، يرجى طلب رمز جديد")

    if otp["attempts"] >= 3:
        raise ApiError(429, "max_attempts_exceeded", "تم تجاوز الحد الأقصى للمحاولات الخاطئة، يرجى طلب رمز جديد")

    if not verify_otp_code(body.otp_code.strip(), otp["otp_code_hash"]):
        conn.execute(
            "UPDATE auth_otps SET attempts = attempts + 1 WHERE id = %s",
            (body.session_id,),
        )
        remaining = 2 - otp["attempts"]
        msg = f"رمز التحقق غير صحيح. متبقي {remaining} محاولات." if remaining > 0 else "رمز التحقق غير صحيح. تم تجاوز الحد الأقصى للمحاولات."
        raise ApiError(401, "invalid_otp", msg)

    # Success: mark OTP as used
    conn.execute("UPDATE auth_otps SET is_used = true WHERE id = %s", (body.session_id,))

    user = conn.execute(
        "SELECT id, username, role, staff_id, guardian_id, room_id, student_id, is_active "
        "FROM users WHERE id = %s AND deleted_at IS NULL",
        (otp["user_id"],),
    ).fetchone()

    if not user or not user["is_active"]:
        raise ApiError(401, "unauthorized", "الحساب غير نشط أو تم حذفه")

    user = dict(user)
    return {
        "token": issue_token(user, get_settings()),
        "user": {key: str(user[key]) if key == "id" else user[key] for key in ("id", "username", "role")} | {"name": _user_name(conn, user)},
    }


@router.post("/auth/logout", status_code=204)
def logout(user: dict = Depends(current_user), conn=Depends(get_conn)) -> Response:
    conn.execute("DELETE FROM revoked_tokens WHERE expires_at < now()")  # D4-a housekeeping
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

