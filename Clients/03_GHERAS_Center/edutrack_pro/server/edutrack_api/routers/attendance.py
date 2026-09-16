from datetime import date

from fastapi import APIRouter, Depends

from edutrack_api.audit import write_audit
from edutrack_api.auth import require_roles
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.serializers import row_to_json

router = APIRouter()
_ROLES = Depends(require_roles("manager", "supervisor", "teacher"))
_VALID_STATUSES = {"حاضر", "غائب", "متأخر", "مستأذن"}


def _validate(items: list[dict], key: str) -> date | None:
    dates: set[date] = set()
    for item in items:
        if not item.get(key) or item.get("status") not in _VALID_STATUSES:
            raise ApiError(422, "validation_error", "بيانات الحضور غير صحيحة")
        try:
            dates.add(date.fromisoformat(str(item["date"])))
        except ValueError as exc:
            raise ApiError(422, "validation_error", "التاريخ غير صحيح") from exc
    return next(iter(dates)) if len(dates) == 1 else None


def _save(conn, items: list[dict], key: str, table: str, actor: dict) -> dict:
    report_date = _validate(items, key)
    if not items:
        return {"saved": 0, "items": []}
    sql = f"""
        INSERT INTO {table} ({key}, date, status, note{', recorded_by_user_id' if table == 'student_attendance' else ''})
        VALUES (%({key})s, %(date)s, %(status)s, %(note)s{', %(actor)s' if table == 'student_attendance' else ''})
        ON CONFLICT ({key}, date) DO UPDATE SET status = EXCLUDED.status, note = EXCLUDED.note,
          updated_at = now(), deleted_at = NULL{', recorded_by_user_id = EXCLUDED.recorded_by_user_id' if table == 'student_attendance' else ''}
        RETURNING *
    """
    saved = []
    for item in items:
        params = {key: item[key], "date": item["date"], "status": item["status"], "note": item.get("note"), "actor": actor["id"]}
        saved.append(row_to_json(conn.execute(sql, params).fetchone()))
    write_audit(conn, actor["id"], "update", table, None, {"count": len(items), "date": report_date.isoformat() if report_date else None})
    return {"saved": len(saved), "items": saved}


@router.post("/attendance/students")
def save_students(items: list[dict], conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    return _save(conn, items, "student_id", "student_attendance", user)


@router.post("/attendance/staff")
def save_staff(items: list[dict], conn=Depends(get_conn), user: dict = _ROLES) -> dict:
    return _save(conn, items, "staff_id", "staff_attendance", user)
