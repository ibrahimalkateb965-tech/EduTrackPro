from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict

from edutrack_api.audit import write_audit
from edutrack_api.auth import require_roles
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.services.settings import MAX_VALUE_LEN, SETTING_KEYS, load_settings

router = APIRouter()


class SettingsUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    academic_year: str | None = None
    center_name: str | None = None
    center_phone: str | None = None
    center_address: str | None = None
    manager_title: str | None = None
    manager_name: str | None = None


@router.get("/settings")
def get_settings(
    user: dict = Depends(require_roles("manager", "supervisor")),
    conn=Depends(get_conn),
) -> dict:
    return {"settings": load_settings(conn), "keys": list(SETTING_KEYS)}


@router.put("/settings")
def update_settings(
    body: SettingsUpdate,
    user: dict = Depends(require_roles("manager")),
    conn=Depends(get_conn),
) -> dict:
    changes = {k: v.strip() for k, v in body.model_dump(exclude_none=True).items()}
    if not changes:
        raise ApiError(422, "validation_error", "لا توجد إعدادات لتحديثها")
    for key, value in changes.items():
        if not value or len(value) > MAX_VALUE_LEN:
            raise ApiError(422, "validation_error", f"قيمة الإعداد {key} غير صالحة")
    for key, value in changes.items():
        conn.execute(
            "INSERT INTO system_settings (key, value) VALUES (%s, %s) "
            "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()",
            (key, value),
        )
    write_audit(conn, user["id"], "update", "system_settings", None, {"changed": changes})
    return {"settings": load_settings(conn), "updated": sorted(changes)}
