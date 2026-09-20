"""System settings (academic year, center identity) with hard-coded fallbacks."""

from __future__ import annotations

import psycopg

DEFAULTS: dict[str, str] = {
    "academic_year": "1447-1448 هـ",
    "center_name": "مركز غراس للرعاية النهارية والتعليم الذكي",
    "center_phone": "0550000000",
    "center_address": "حوطة بني تميم",
    "manager_title": "مدير عام المركز",
    "manager_name": "إدارة المركز",
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
