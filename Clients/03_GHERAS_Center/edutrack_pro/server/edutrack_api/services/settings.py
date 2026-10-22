"""System settings (academic year, center identity) with hard-coded fallbacks."""

from __future__ import annotations

import psycopg

DEFAULTS: dict[str, str] = {
    "academic_year": "1447-1448 هـ",
    "center_name": "مركز غراس",
    "center_phone": "0550000000",
    "center_address": "حوطة بني تميم",
    "manager_title": "مدير عام المركز",
    "manager_name": "إدارة المركز",
    "pickup_cutoff_time": "18:30",
}
SETTING_KEYS = tuple(DEFAULTS)
MAX_VALUE_LEN = 200


def load_settings(conn) -> dict[str, str]:
    """Return every known setting, DB value first, DEFAULTS as fallback (missing table/rows tolerated)."""
    try:
        rows = conn.execute(
            "SELECT key, value FROM system_settings WHERE key = ANY(%s)",
            (list(SETTING_KEYS),),
        ).fetchall()
    except psycopg.errors.UndefinedTable:
        conn.rollback()
        return dict(DEFAULTS)
    return {**DEFAULTS, **{r["key"]: r["value"] for r in rows if r["value"]}}


def get_pickup_cutoff(conn) -> str:
    """Return the cutoff time after which pickup requests are rejected (HH:MM)."""
    return load_settings(conn).get("pickup_cutoff_time", "18:30")
