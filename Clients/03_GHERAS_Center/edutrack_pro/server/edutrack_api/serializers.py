"""Row to JSON conversion helpers (Phase 2, Worker B)."""

from __future__ import annotations

import base64
import datetime
import uuid
from decimal import Decimal


def json_default(obj):
    if isinstance(obj, Decimal):
        return round(float(obj), 2)
    if isinstance(obj, uuid.UUID):
        return str(obj)
    if isinstance(obj, (datetime.datetime, datetime.date, datetime.time)):
        return obj.isoformat()
    if isinstance(obj, bytes):
        return base64.b64encode(obj).decode("ascii")
    raise TypeError(f"Object of type {type(obj).__name__} is not JSON serializable")


def _convert(value):
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Decimal):
        return round(float(value), 2)
    if isinstance(value, uuid.UUID):
        return str(value)
    if isinstance(value, (datetime.datetime, datetime.date, datetime.time)):
        return value.isoformat()
    if isinstance(value, bytes):
        return base64.b64encode(value).decode("ascii")
    if isinstance(value, dict):
        return {key: _convert(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_convert(item) for item in value]
    return json_default(value)


def row_to_json(row: dict) -> dict:
    return {key: _convert(value) for key, value in row.items()}
