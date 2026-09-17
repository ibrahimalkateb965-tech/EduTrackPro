from uuid import UUID

import psycopg
from fastapi import APIRouter, Depends, Request

from edutrack_api.audit import write_audit
from edutrack_api.auth import require_roles
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError, map_db_error
from edutrack_api.repositories.generic import GenericRepository
from edutrack_api.serializers import row_to_json
from edutrack_api.services.finance import (
    allocate_payment,
    apply_advance_deduction,
    close_month,
    default_cash_account_id,
    generate_installments,
    issue_receipt,
    post_ledger_entry,
    resolve_expense_category,
    transfer_between_accounts,
)

RESOURCES = {
    "students": "students", "guardians": "guardians", "staff": "staff", "users": "users",
    "rooms": "rooms", "schedules": "schedules", "fee-plans": "fee_plans", "installments": "installments",
    "payments": "payments", "receipts": "receipts", "expenses": "expenses", "expense-categories": "expense_categories",
    "payroll-runs": "payroll_runs", "staff-advances": "staff_advances", "staff-assets": "staff_assets",
    "staff-attendance": "staff_attendance",
    "ledger-accounts": "ledger_accounts", "ledger-entries": "ledger_entries", "assignments": "assignments",
    "submissions": "submissions", "lesson-logs": "lesson_logs", "study-plans": "study_plans",
    "skill-progress": "skill_progress", "evaluations": "evaluations", "tasks": "tasks", "messages": "messages",
    "certificates": "certificates", "dismissals": "dismissals", "branches": "branches", "month-closures": "month_closures",
}

router = APIRouter()


def _clean_user(row: dict) -> dict:
    result = dict(row)
    result.pop("password_hash", None)
    return result


def _clean_user_with_perms(conn, row: dict) -> dict:
    result = _clean_user(row)
    perms = conn.execute(
        "SELECT attendance, daily_evaluation, monthly_evaluation, students, finance "
        "FROM user_permissions WHERE user_id = %s AND deleted_at IS NULL",
        (row["id"],),
    ).fetchone()
    result["permissions"] = {
        "attendance": bool(perms["attendance"]) if perms else False,
        "daily_evaluation": bool(perms["daily_evaluation"]) if perms else False,
        "monthly_evaluation": bool(perms["monthly_evaluation"]) if perms else False,
        "students": bool(perms["students"]) if perms else False,
        "finance": bool(perms["finance"]) if perms else False,
    }
    return result


def _clean_staff(row: dict, user: dict) -> dict:
    result = dict(row)
    if user.get("role") == "supervisor" and not user.get("permissions", {}).get("finance", False):
        result["base_salary"] = None
    return result


def _format_row(conn, row: dict, table: str, user: dict) -> dict:
    json_row = row_to_json(row)
    if table == "users":
        return _clean_user_with_perms(conn, json_row)
    if table == "staff":
        return _clean_staff(json_row, user)
    return json_row


def _repo(request: Request, conn, table: str) -> GenericRepository:
    columns = request.app.state.columns.get(table)
    if columns is None:
        raise ApiError(422, "validation_error", "المورد غير متاح")
    return GenericRepository(conn, table, columns)


def _allowed(user: dict, table: str, write: bool) -> None:
    if user["role"] == "manager":
        return

    # Branch management is strictly manager-only
    if table == "branches":
        raise ApiError(403, "forbidden", "إدارة الفروع مخصصة للمدير العام فقط")

    # User management: manager has full access; supervisor can read only
    if table == "users":
        if write and user["role"] != "manager":
            raise ApiError(403, "forbidden", "إدارة المستخدمين مخصصة للمدير العام فقط")
        if not write and user["role"] not in {"manager", "supervisor"}:
            raise ApiError(403, "forbidden", "إدارة المستخدمين مخصصة للمدير العام فقط")
        return

    if user["role"] == "supervisor":
        perms = user.get("permissions", {})

        # Staff and Rooms access
        staff_and_rooms = {"staff", "rooms", "schedules"}
        if table in staff_and_rooms:
            if write:
                raise ApiError(403, "forbidden", "إدارة الموظفين والقاعات مخصصة للمدير العام فقط")
            if not perms.get("students", False) and not perms.get("attendance", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية الوصول إلى الموظفين والقاعات")

        # Finance resources
        finance_tables = {
            "payments", "receipts", "expenses", "expense_categories",
            "payroll_runs", "staff_advances", "ledger_accounts", "ledger_entries",
            "month_closures", "fee_plans", "installments"
        }
        if table in finance_tables and not perms.get("finance", False):
            raise ApiError(403, "forbidden", "ليس لديك صلاحية الوصول إلى البيانات والعمليات المالية")

        # Student resources
        student_tables = {"students", "guardians", "student_guardians", "dismissals"}
        if table in student_tables and not perms.get("students", False):
            raise ApiError(403, "forbidden", "ليس لديك صلاحية الوصول إلى سجلات الطلاب")

        # Attendance resources
        attendance_tables = {"student_attendance", "staff_attendance"}
        if table in attendance_tables and not perms.get("attendance", False):
            raise ApiError(403, "forbidden", "ليس لديك صلاحية الوصول إلى الحضور والغياب")

        # Evaluation & tracking resources
        evaluation_tables = {"evaluations", "skill_progress", "study_plans"}
        if table in evaluation_tables and not (perms.get("daily_evaluation", False) or perms.get("monthly_evaluation", False)):
            raise ApiError(403, "forbidden", "ليس لديك صلاحية الوصول إلى التقييمات ومتابعة الحفظ")

        # Write restrictions on sensitive ledger closures
        if write and table in {"ledger_accounts", "ledger_entries", "payroll_runs", "month_closures"}:
            raise ApiError(403, "forbidden", "ليس لديك صلاحية التعديل على الحسابات والإقفالات المالية")

        return

    raise ApiError(403, "forbidden", "ليس لديك صلاحية")


def _permissions(conn, user_id, branch_id, values: dict) -> None:
    flags = {key: bool(values.get(key, False)) for key in ("attendance", "daily_evaluation", "monthly_evaluation", "students", "finance")}
    conn.execute(
        "INSERT INTO user_permissions (branch_id, user_id, attendance, daily_evaluation, monthly_evaluation, students, finance) "
        "VALUES (%(branch_id)s, %(user_id)s, %(attendance)s, %(daily_evaluation)s, %(monthly_evaluation)s, %(students)s, %(finance)s) "
        "ON CONFLICT (user_id) DO UPDATE SET attendance = EXCLUDED.attendance, daily_evaluation = EXCLUDED.daily_evaluation, "
        "monthly_evaluation = EXCLUDED.monthly_evaluation, students = EXCLUDED.students, finance = EXCLUDED.finance, deleted_at = NULL",
        flags | {"branch_id": branch_id, "user_id": user_id},
    )


def _audit_details(data: dict) -> dict:
    details = dict(data)
    details.pop("password", None)
    details.pop("password_hash", None)
    return details


def _create_hook(conn, repo, table: str, data: dict, actor: dict) -> tuple[dict, dict]:
    details = _audit_details(data)
    if table == "users":
        from edutrack_api.auth import hash_password
        password = data.pop("password", None)
        permissions = data.pop("permissions", None)
        if password:
            data["password_hash"] = hash_password(password)
        row = repo.create(data)
        if permissions is not None:
            _permissions(conn, row["id"], row["branch_id"], permissions)
        return row, details
    if table == "expenses":
        category = data.pop("category", None)
        if category is not None and not data.get("category_id"):
            data["category_id"] = resolve_expense_category(conn, category, actor.get("branch_id"))
        data["account_id"] = data.get("account_id") or default_cash_account_id(conn, actor.get("branch_id"))
    if table == "month_closures":
        row = close_month(conn, data["month"], actor["id"], actor.get("branch_id"))
        return row, details
    if table == "ledger_entries" and data.get("from_account_id") and data.get("to_account_id"):
        # dashboard transfer form: one request -> transfer_out on the source + transfer_in on the target
        row = transfer_between_accounts(conn, data, actor.get("branch_id"))
        return row, details
    row = repo.create(data)
    if table == "fee_plans":
        row["installments"] = generate_installments(conn, row)
    elif table == "payments":
        touched = allocate_payment(conn, row, row.get("installment_id"))
        row["installments_touched"] = touched
        row["receipt"] = issue_receipt(conn, row["id"])
        post_ledger_entry(conn, row.get("account_id") or default_cash_account_id(conn, row["branch_id"]), "in", row["amount"], "payments", row["id"], row["paid_on"], row["branch_id"])
    elif table == "expenses":
        post_ledger_entry(conn, row["account_id"], "out", row["amount"], "expenses", row["id"], row["paid_on"], row["branch_id"])
    elif table == "payroll_runs":
        deduction = apply_advance_deduction(conn, row["staff_id"], row["month"], data)
        if deduction != row["advance_deducted"]:
            row = conn.execute("UPDATE payroll_runs SET advance_deducted = %s WHERE id = %s RETURNING *", (deduction, row["id"])).fetchone()
            row = dict(row)
    return row, details


def _update_hook(conn, repo, table: str, record_id: UUID, data: dict) -> tuple[dict | None, dict]:
    details = _audit_details(data)
    if table == "users":
        from edutrack_api.auth import hash_password
        password = data.pop("password", None)
        permissions = data.pop("permissions", None)
        if password:
            data["password_hash"] = hash_password(password)
        row = repo.update(record_id, data)
        if row and permissions is not None:
            _permissions(conn, row["id"], row["branch_id"], permissions)
        return row, details
    return repo.update(record_id, data), details


def _register(path: str, table: str) -> None:
    def list_rows(request: Request, limit: int = 100, offset: int = 0, q: str | None = None,
                  user: dict = Depends(require_roles("manager", "supervisor")), conn=Depends(get_conn)):
        _allowed(user, table, False)
        if not 1 <= limit <= 500 or offset < 0:
            raise ApiError(422, "validation_error", "قيم التصفح غير صحيحة")
        filters = {key: value for key, value in request.query_params.items() if key not in {"limit", "offset", "q"}}
        if table == "evaluations" and user.get("role") == "supervisor":
            perms = user.get("permissions", {})
            has_daily = perms.get("daily_evaluation", False)
            has_monthly = perms.get("monthly_evaluation", False)
            requested_type = filters.get("eval_type")
            if requested_type == "daily" and not has_daily:
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم اليومي")
            if requested_type in {"monthly", "weekly"} and not has_monthly:
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم الشهري والنهائي")
            if not requested_type:
                if has_daily and not has_monthly:
                    filters["eval_type"] = "daily"
                elif has_monthly and not has_daily:
                    filters["eval_type"] = "monthly"
        try:
            rows, total = _repo(request, conn, table).list(filters=filters, limit=limit, offset=offset, q=q)
        except ValueError as exc:
            raise ApiError(422, "validation_error", "حقل التصفية غير صحيح") from exc
        return {"items": [_format_row(conn, row, table, user) for row in rows], "total": total, "limit": limit, "offset": offset}

    def get_row(record_id: UUID, request: Request, user: dict = Depends(require_roles("manager", "supervisor")), conn=Depends(get_conn)):
        _allowed(user, table, False)
        row = _repo(request, conn, table).get(record_id)
        if not row:
            raise ApiError(404, "not_found", "السجل غير موجود")
        if table == "evaluations" and user.get("role") == "supervisor":
            perms = user.get("permissions", {})
            eval_type = row.get("eval_type")
            if eval_type == "daily" and not perms.get("daily_evaluation", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم اليومي")
            if eval_type in {"monthly", "weekly"} and not perms.get("monthly_evaluation", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم الشهري والنهائي")
        return _format_row(conn, row, table, user)

    def create_row(body: dict, request: Request, user: dict = Depends(require_roles("manager", "supervisor")), conn=Depends(get_conn)):
        _allowed(user, table, True)
        if table == "evaluations" and user.get("role") == "supervisor":
            perms = user.get("permissions", {})
            eval_type = body.get("eval_type", "daily")
            if eval_type == "daily" and not perms.get("daily_evaluation", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم اليومي")
            if eval_type in {"monthly", "weekly"} and not perms.get("monthly_evaluation", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم الشهري والنهائي")
        if table == "staff" and user.get("role") == "supervisor" and not user.get("permissions", {}).get("finance", False):
            if "base_salary" in body:
                raise ApiError(403, "forbidden", "ليس لديك صلاحية تحديد أو تعديل الراتب الأساسي")
        try:
            row, details = _create_hook(conn, _repo(request, conn, table), table, dict(body), user)
            write_audit(conn, user["id"], "create", table, row["id"], details)
            return _format_row(conn, row, table, user)
        except psycopg.Error as exc:
            raise map_db_error(exc) from exc

    def update_row(record_id: UUID, body: dict, request: Request, user: dict = Depends(require_roles("manager", "supervisor")), conn=Depends(get_conn)):
        _allowed(user, table, True)
        if table == "evaluations" and user.get("role") == "supervisor":
            perms = user.get("permissions", {})
            existing = _repo(request, conn, table).get(record_id)
            if not existing:
                raise ApiError(404, "not_found", "السجل غير موجود")
            eval_type = body.get("eval_type", existing.get("eval_type"))
            if eval_type == "daily" and not perms.get("daily_evaluation", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم اليومي")
            if eval_type in {"monthly", "weekly"} and not perms.get("monthly_evaluation", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم الشهري والنهائي")
        if table == "staff" and user.get("role") == "supervisor" and not user.get("permissions", {}).get("finance", False):
            if "base_salary" in body:
                raise ApiError(403, "forbidden", "ليس لديك صلاحية تحديد أو تعديل الراتب الأساسي")
        try:
            row, details = _update_hook(conn, _repo(request, conn, table), table, record_id, dict(body))
            if not row:
                raise ApiError(404, "not_found", "السجل غير موجود")
            write_audit(conn, user["id"], "update", table, record_id, details)
            return _format_row(conn, row, table, user)
        except psycopg.Error as exc:
            raise map_db_error(exc) from exc

    def delete_row(record_id: UUID, request: Request, user: dict = Depends(require_roles("manager", "supervisor")), conn=Depends(get_conn)):
        _allowed(user, table, True)
        if table == "evaluations" and user.get("role") == "supervisor":
            perms = user.get("permissions", {})
            existing = _repo(request, conn, table).get(record_id)
            if existing:
                eval_type = existing.get("eval_type")
                if eval_type == "daily" and not perms.get("daily_evaluation", False):
                    raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم اليومي")
                if eval_type in {"monthly", "weekly"} and not perms.get("monthly_evaluation", False):
                    raise ApiError(403, "forbidden", "ليس لديك صلاحية التقييم الشهري والنهائي")
        try:
            if not _repo(request, conn, table).soft_delete(record_id):
                raise ApiError(404, "not_found", "السجل غير موجود")
            write_audit(conn, user["id"], "delete", table, record_id, {})
        except psycopg.Error as exc:
            raise map_db_error(exc) from exc

    router.add_api_route(f"/{path}", list_rows, methods=["GET"])
    router.add_api_route(f"/{path}/{{record_id}}", get_row, methods=["GET"])
    router.add_api_route(f"/{path}", create_row, methods=["POST"])
    router.add_api_route(f"/{path}/{{record_id}}", update_row, methods=["PATCH"])
    router.add_api_route(f"/{path}/{{record_id}}", delete_row, methods=["DELETE"], status_code=204)


for _path, _table in RESOURCES.items():
    _register(_path, _table)
