"""Day-10 data migration from the MVP backup named gheras_simple_v1.

Reads either the raw localStorage object or the download wrapper and writes
rows with stable uuid5 ids. Every insert uses ON CONFLICT DO NOTHING inside
a per-row savepoint so one bad row never stops the import.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation

import psycopg
from psycopg import sql
from psycopg.types.json import Json

NAMESPACE_GHERAS = uuid.uuid5(uuid.NAMESPACE_DNS, "edutrack.gheras.sa")

_VALID_GROUPS = ("الصباح", "المساء", "الإنجليزي", "القدرات")
_VALID_ATTENDANCE = ("حاضر", "غائب", "متأخر", "مستأذن")
_VALID_EVAL_TYPES = ("daily", "weekly", "monthly")
_VALID_ENTRY_TYPES = ("in", "out", "transfer_in", "transfer_out")
_VALID_LEVELS = ("متميز", "متقن", "جيد", "يحتاج متابعة", "يحتاج دعم")
_VALID_GUARDIAN_RELATIONS = ("الأب", "الأم", "ولي الأمر", "شخص آخر")
_VALID_METHODS = (
    "كاش",
    "تحويل بنكي",
    "تابي",
    "تقسيط المركز",
    "مدى",
    "Apple Pay",
    "بطاقة",
    "تمارا",
    "جهاز نقاط بيع",
)
_METHOD_MAP = {
    "جهاز نقاط بيع": "مدى",
    "تمارا": "تابي",
    "بطاقة ائتمانية": "بطاقة",
}


def legacy_uuid(collection: str, legacy_id) -> uuid.UUID:
    """Stable id for a legacy row."""
    return uuid.uuid5(NAMESPACE_GHERAS, f"{collection}:{legacy_id}")


def yes_no(v) -> bool | None:
    """Map Arabic yes/no strings to bool."""
    if v is None:
        return None
    if isinstance(v, bool):
        return v
    if isinstance(v, (int, float)):
        if v == 1:
            return True
        if v == 0:
            return False
        return None
    s = str(v).strip()
    if s == "نعم":
        return True
    if s == "لا":
        return False
    low = s.lower()
    if low in ("true", "yes", "1"):
        return True
    if low in ("false", "no", "0"):
        return False
    return None


def as_bool(v) -> bool | None:
    """Best effort bool coercion."""
    if v is None:
        return None
    if isinstance(v, bool):
        return v
    if isinstance(v, (int, float)):
        return bool(v)
    s = str(v).strip()
    if s == "":
        return None
    yn = yes_no(s)
    if yn is not None:
        return yn
    low = s.lower()
    if low in ("true", "yes", "1"):
        return True
    if low in ("false", "no", "0"):
        return False
    return None


def as_decimal(v) -> Decimal:
    """Coerce to Decimal, invalid input becomes Decimal 0."""
    if v is None:
        return Decimal("0")
    if isinstance(v, Decimal):
        return v
    if isinstance(v, bool):
        return Decimal("1") if v else Decimal("0")
    if isinstance(v, int):
        return Decimal(v)
    if isinstance(v, float):
        try:
            return Decimal(str(v))
        except InvalidOperation:
            return Decimal("0")
    s = str(v).strip()
    if s == "":
        return Decimal("0")
    try:
        return Decimal(s)
    except InvalidOperation:
        return Decimal("0")


def as_date(v) -> date | None:
    """Accept YYYY-MM-DD, ISO datetimes, datetime objects, empty as None."""
    if v is None:
        return None
    if isinstance(v, datetime):
        return v.date()
    if isinstance(v, date):
        return v
    if isinstance(v, (int, float)):
        return None
    s = str(v).strip()
    if s == "":
        return None
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    try:
        return date.fromisoformat(s[:10])
    except ValueError:
        pass
    try:
        return datetime.fromisoformat(s).date()
    except ValueError:
        return None


def _as_ts(v):
    """Coerce to datetime for timestamptz columns, None when empty."""
    if v is None:
        return None
    if isinstance(v, datetime):
        return v
    if isinstance(v, date):
        return datetime(v.year, v.month, v.day)
    s = str(v).strip()
    if s == "":
        return None
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    try:
        return datetime.fromisoformat(s)
    except ValueError:
        pass
    d = as_date(s)
    if d is None:
        return None
    return datetime(d.year, d.month, d.day)


def map_role(v) -> str:
    """Map Arabic role labels to manager/supervisor/teacher/guardian."""
    s = str(v or "").strip()
    if "مدير" in s:
        return "manager"
    if "مشرف" in s:
        return "supervisor"
    if "معلم" in s:
        return "teacher"
    if "ولي" in s:
        return "guardian"
    if s in ("manager", "supervisor", "teacher", "guardian"):
        return s
    return "teacher"


def map_method(v) -> str | None:
    """Map payment method labels to stored values, None when empty."""
    if v is None:
        return None
    s = str(v).strip()
    if s == "":
        return None
    if s in _METHOD_MAP:
        return _METHOD_MAP[s]
    if s in _VALID_METHODS:
        return s
    return "كاش"


def map_attendance_status(v) -> str:
    """Map attendance labels to stored Arabic status values."""
    s = str(v or "").strip()
    if s in _VALID_ATTENDANCE:
        return s
    mapping = {
        "حضور": "حاضر",
        "present": "حاضر",
        "غياب": "غائب",
        "absent": "غائب",
        "تأخر": "متأخر",
        "late": "متأخر",
        "استئذان": "مستأذن",
        "excused": "مستأذن",
    }
    return mapping.get(s, mapping.get(s.lower(), "حاضر"))


def map_account_kind(v) -> str:
    """Map account kind labels to cash/bank."""
    s = str(v or "").strip()
    if s == "بنك" or s.lower() == "bank":
        return "bank"
    return "cash"


def map_entry_type(v) -> str:
    """Map money movement labels to ledger entry types."""
    s = str(v or "").strip()
    if s in _VALID_ENTRY_TYPES:
        return s
    mapping = {
        "إيداع": "in",
        "in": "in",
        "سحب": "out",
        "out": "out",
        "تحويل": "transfer_out",
    }
    return mapping.get(s, mapping.get(s.lower(), "out"))


def map_student_status(v) -> str:
    """Map student status labels to active/dismissed/archived."""
    if v is None or str(v).strip() == "":
        return "active"
    s = str(v).strip()
    if s in ("active", "dismissed", "archived"):
        return s
    if "مفصول" in s:
        return "dismissed"
    if "مؤرشف" in s or "ارشيف" in s:
        return "archived"
    return "active"


def map_eval_type(v) -> str:
    """Map evaluation type labels to daily/weekly/monthly."""
    s = str(v or "").strip()
    if s in _VALID_EVAL_TYPES:
        return s
    if "أسبوع" in s or s.lower() == "weekly":
        return "weekly"
    if "شهر" in s or s.lower() == "monthly":
        return "monthly"
    return "daily"


@dataclass
class CollectionResult:
    collection: str
    read: int = 0
    inserted: int = 0
    skipped: int = 0
    warnings: list[str] = field(default_factory=list)


@dataclass
class ImportReport:
    rows: list[CollectionResult] = field(default_factory=list)

    def result_for(self, collection: str) -> CollectionResult:
        for row in self.rows:
            if row.collection == collection:
                return row
        item = CollectionResult(collection=collection)
        self.rows.append(item)
        return item

    def format_table(self) -> str:
        header = ("collection", "read", "inserted", "skipped", "warnings")
        lines = []
        lines.append(
            f"{header[0]:<20} | {header[1]:>6} | {header[2]:>8} | "
            f"{header[3]:>7} | {header[4]:>8}"
        )
        lines.append("-" * 20 + "-+-" + "-" * 6 + "-+-" + "-" * 8 + "-+-" + "-" * 7 + "-+-" + "-" * 8)
        for row in self.rows:
            lines.append(
                f"{row.collection:<20} | {row.read:>6} | {row.inserted:>8} | "
                f"{row.skipped:>7} | {len(row.warnings):>8}"
            )
        for row in self.rows:
            for warning in row.warnings:
                lines.append(f"{row.collection}: {warning}")
        return "\n".join(lines)


@dataclass
class ImportContext:
    conn: object
    branch_id: uuid.UUID
    payload: dict
    report: ImportReport
    dry_run: bool = False
    ids: dict[str, dict[str, uuid.UUID]] = field(default_factory=dict)
    plans_by_student: dict[str, uuid.UUID] = field(default_factory=dict)
    room_by_name: dict[str, uuid.UUID] = field(default_factory=dict)
    category_by_name: dict[str, uuid.UUID] = field(default_factory=dict)


class _DryRunRollback(Exception):
    pass


def _remember(ctx: ImportContext, collection: str, legacy_id, new_id: uuid.UUID) -> None:
    bucket = ctx.ids.setdefault(collection, {})
    bucket[str(legacy_id)] = new_id


def _lookup(ctx: ImportContext, collection: str, legacy_id) -> uuid.UUID | None:
    if legacy_id is None:
        return None
    key = str(legacy_id).strip()
    if key == "":
        return None
    return ctx.ids.get(collection, {}).get(key)


def _today() -> date:
    return datetime.now(timezone.utc).date()


def insert_row(
    ctx: ImportContext,
    table: str,
    row: dict,
    *,
    conflict: str = "(id)",
    collection: str,
) -> uuid.UUID | None:
    """Insert one row with ON CONFLICT DO NOTHING inside a savepoint."""
    result = ctx.report.result_for(collection)
    data = dict(row)
    if "branch_id" not in data:
        data["branch_id"] = ctx.branch_id
    adapted = {}
    for key, value in data.items():
        if isinstance(value, (dict, list)):
            adapted[key] = Json(value)
        else:
            adapted[key] = value
    cols = list(adapted.keys())
    values = [adapted[c] for c in cols]
    query = sql.SQL("INSERT INTO {} ({}) VALUES ({}) ON CONFLICT {} DO NOTHING RETURNING id").format(
        sql.Identifier(table),
        sql.SQL(", ").join(sql.Identifier(c) for c in cols),
        sql.SQL(", ").join(sql.Placeholder() for _ in cols),
        sql.SQL(conflict),
    )
    try:
        with ctx.conn.transaction():
            cur = ctx.conn.execute(query, values)
            found = cur.fetchone()
    except psycopg.Error as exc:
        detail = getattr(exc, "diag", None)
        code = ""
        if detail is not None and getattr(detail, "constraint_name", None):
            code = str(detail.constraint_name)
        elif getattr(exc, "pgcode", None):
            code = str(exc.pgcode)
        else:
            code = type(exc).__name__
        result.skipped += 1
        result.warnings.append(f"{table} insert failed ({code})")
        return None
    if found is None:
        result.skipped += 1
        return None
    new_id = found["id"] if isinstance(found, dict) else found[0]
    result.inserted += 1
    return new_id


def _clean_text(v) -> str | None:
    if v is None:
        return None
    s = str(v).strip()
    return s if s != "" else None


def import_rooms(ctx: ImportContext) -> None:
    items = ctx.payload.get("rooms") or []
    result = ctx.report.result_for("rooms")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("rooms: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("rooms: missing id skipped")
            continue
        name = _clean_text(item.get("name")) or f"room-{legacy_id}"
        raw_group = _clean_text(item.get("group") or item.get("group_name"))
        group_name = raw_group if raw_group in _VALID_GROUPS else "الصباح"
        new_id = legacy_uuid("rooms", str(legacy_id))
        stored = insert_row(
            ctx,
            "rooms",
            {"id": new_id, "name": name, "group_name": group_name},
            collection="rooms",
        )
        if stored is not None:
            _remember(ctx, "rooms", legacy_id, new_id)
            ctx.room_by_name[name] = new_id


def import_staff(ctx: ImportContext) -> None:
    items = ctx.payload.get("staff") or []
    result = ctx.report.result_for("staff")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("staff: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("staff: missing id skipped")
            continue
        name = _clean_text(item.get("name"))
        if not name:
            result.skipped += 1
            result.warnings.append(f"staff {legacy_id}: missing name skipped")
            continue
        role_title = _clean_text(item.get("role")) or "موظف"
        base_salary = as_decimal(item.get("salary", 0))
        phone = _clean_text(item.get("phone"))
        hire_date = as_date(item.get("hireDate") or item.get("hire_date") or item.get("date"))
        new_id = legacy_uuid("staff", str(legacy_id))
        row: dict = {"id": new_id, "name": name, "role_title": role_title, "base_salary": base_salary}
        if phone:
            row["phone"] = phone
        if hire_date is not None:
            row["hire_date"] = hire_date
        stored = insert_row(ctx, "staff", row, collection="staff")
        if stored is not None:
            _remember(ctx, "staff", legacy_id, new_id)


def import_users(ctx: ImportContext) -> None:
    from edutrack_api.auth import hash_password

    items = ctx.payload.get("users") or []
    result = ctx.report.result_for("users")
    result.read = len(items)
    defaulted: list[str] = []
    staff_to_user: dict[str, uuid.UUID] = {}
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("users: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("users: missing id skipped")
            continue
        username = _clean_text(item.get("username"))
        if not username:
            result.skipped += 1
            result.warnings.append(f"users {legacy_id}: missing username skipped")
            continue
        raw_password = item.get("password")
        if raw_password is None or str(raw_password).strip() == "":
            raw_password = username
            defaulted.append(username)
        password_hash = hash_password(str(raw_password))
        role = map_role(item.get("role"))
        staff_id = _lookup(ctx, "staff", item.get("staffId") or item.get("staff_id"))
        room_id = _lookup(ctx, "rooms", item.get("roomId") or item.get("room_id"))
        is_active = item.get("active", True) is not False
        if isinstance(item.get("active"), str):
            parsed = as_bool(item.get("active"))
            is_active = parsed if parsed is not None else True
        new_id = legacy_uuid("users", str(legacy_id))
        row: dict = {
            "id": new_id,
            "username": username,
            "password_hash": password_hash,
            "role": role,
            "is_active": is_active,
        }
        if staff_id is not None:
            row["staff_id"] = staff_id
        if room_id is not None:
            row["room_id"] = room_id
        stored = insert_row(ctx, "users", row, collection="users")
        if stored is not None:
            _remember(ctx, "users", legacy_id, new_id)
            staff_key = item.get("staffId") or item.get("staff_id")
            if staff_key is not None and str(staff_key).strip() != "":
                staff_to_user[str(staff_key)] = new_id
    if defaulted:
        result.warnings.append(f"default password used for: {', '.join(sorted(set(defaulted)))}")
    perms = ctx.payload.get("permissions") or []
    perm_result = ctx.report.result_for("permissions")
    perm_result.read = len(perms)
    for index, perm in enumerate(perms):
        if not isinstance(perm, dict):
            perm_result.skipped += 1
            perm_result.warnings.append("permissions: invalid row skipped")
            continue
        staff_key = perm.get("staffId") or perm.get("staff_id")
        user_id = staff_to_user.get(str(staff_key)) if staff_key is not None else None
        if user_id is None:
            perm_result.skipped += 1
            perm_result.warnings.append(f"permissions {index}: no user for staff skipped")
            continue
        legacy_key = str(staff_key) if staff_key is not None else f"index-{index}"
        new_id = legacy_uuid("permissions", legacy_key)
        row = {
            "id": new_id,
            "user_id": user_id,
            "attendance": bool(as_bool(perm.get("attendance")) or False),
            "daily_evaluation": bool(as_bool(perm.get("dailyEvaluation")) or False),
            "monthly_evaluation": bool(as_bool(perm.get("monthlyEvaluation")) or False),
            "students": bool(as_bool(perm.get("students")) or False),
            "finance": bool(as_bool(perm.get("finance")) or False),
        }
        stored = insert_row(ctx, "user_permissions", row, collection="permissions")
        if stored is not None:
            _remember(ctx, "permissions", legacy_key, new_id)


def _resolve_room(ctx: ImportContext, item: dict) -> uuid.UUID | None:
    legacy_room = item.get("roomId") or item.get("room_id")
    found = _lookup(ctx, "rooms", legacy_room)
    if found is not None:
        return found
    name = _clean_text(item.get("roomName") or item.get("room_name"))
    if name and name in ctx.room_by_name:
        return ctx.room_by_name[name]
    return None


def import_students(ctx: ImportContext) -> None:
    items = ctx.payload.get("students") or []
    result = ctx.report.result_for("students")
    result.read = len(items)
    installment_items = ctx.payload.get("installments") or []
    earliest_by_student: dict[str, date] = {}
    for raw in installment_items:
        if not isinstance(raw, dict):
            continue
        sid = raw.get("studentId") or raw.get("student_id")
        if sid is None:
            continue
        due = as_date(raw.get("dueDate") or raw.get("due_date"))
        if due is None:
            continue
        key = str(sid)
        if key not in earliest_by_student or due < earliest_by_student[key]:
            earliest_by_student[key] = due
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("students: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("students: missing id skipped")
            continue
        name = _clean_text(item.get("name"))
        if not name:
            result.skipped += 1
            result.warnings.append(f"students {legacy_id}: missing name skipped")
            continue
        raw_diff = item.get("hasDifficulties")
        if isinstance(raw_diff, bool):
            has_difficulties = raw_diff
        elif isinstance(raw_diff, str):
            parsed = yes_no(raw_diff)
            has_difficulties = parsed if parsed is not None else False
        elif raw_diff is None:
            has_difficulties = False
        else:
            has_difficulties = bool(raw_diff)
        raw_prev = item.get("previousStudy")
        if isinstance(raw_prev, bool):
            previous_study = raw_prev
        elif isinstance(raw_prev, str):
            parsed_prev = yes_no(raw_prev)
            previous_study = parsed_prev if parsed_prev is not None else False
        elif raw_prev is None:
            previous_study = False
        else:
            previous_study = bool(raw_prev)
        raw_group = _clean_text(item.get("group") or item.get("group_name"))
        group_name = raw_group if raw_group in _VALID_GROUPS else None
        raw_relation = _clean_text(item.get("guardianRelation"))
        guardian_relation = raw_relation if raw_relation in _VALID_GUARDIAN_RELATIONS else None
        room_id = _resolve_room(ctx, item)
        new_id = legacy_uuid("students", str(legacy_id))
        row: dict = {
            "id": new_id,
            "name": name,
            "has_difficulties": has_difficulties,
            "previous_study": previous_study,
            "status": map_student_status(item.get("status")),
        }
        optional = {
            "national_id": _clean_text(item.get("nationalId")),
            "birth_date": as_date(item.get("birthDate")),
            "nationality": _clean_text(item.get("nationality")),
            "difficulty_notes": _clean_text(item.get("difficulty")),
            "child_notes": _clean_text(item.get("childNotes")),
            "father_name": _clean_text(item.get("fatherName")),
            "father_phone": _clean_text(item.get("fatherPhone")),
            "mother_name": _clean_text(item.get("motherName")),
            "mother_phone": _clean_text(item.get("motherPhone")),
            "guardian_phone": _clean_text(item.get("guardianPhone")),
            "pickup_type": _clean_text(item.get("pickupType")),
            "pickup_name": _clean_text(item.get("pickupName")),
            "pickup_relation": _clean_text(item.get("pickupRelation")),
            "pickup_phone": _clean_text(item.get("pickupPhone")),
            "previous_school": _clean_text(item.get("previousSchool")),
            "previous_level": _clean_text(item.get("previousLevel")),
            "education_notes": _clean_text(item.get("educationNotes")),
        }
        for key, value in optional.items():
            if value is not None:
                row[key] = value
        if guardian_relation is not None:
            row["guardian_relation"] = guardian_relation
        if group_name is not None:
            row["group_name"] = group_name
        if room_id is not None:
            row["room_id"] = room_id
        stored = insert_row(ctx, "students", row, collection="students")
        if stored is None:
            continue
        _remember(ctx, "students", legacy_id, new_id)
        for label, gname, gphone in (
            ("الأب", item.get("fatherName"), item.get("fatherPhone")),
            ("الأم", item.get("motherName"), item.get("motherPhone")),
        ):
            clean_name = _clean_text(gname)
            clean_phone = _clean_text(gphone)
            if not clean_name or not clean_phone:
                continue
            gid = legacy_uuid("guardians", clean_phone)
            grow = {"id": gid, "name": clean_name, "phone": clean_phone, "relation": label}
            created = insert_row(ctx, "guardians", grow, collection="students.guardians")
            if created is None:
                existing = ctx.ids.get("guardians", {}).get(clean_phone)
                if existing is None:
                    try:
                        with ctx.conn.transaction():
                            cur = ctx.conn.execute(
                                "SELECT id FROM guardians WHERE phone = %s LIMIT 1",
                                (clean_phone,),
                            )
                            found = cur.fetchone()
                            if found is not None:
                                existing = found["id"] if isinstance(found, dict) else found[0]
                                ctx.ids.setdefault("guardians", {})[clean_phone] = existing
                    except psycopg.Error:
                        pass
                gid = existing or gid
            else:
                ctx.ids.setdefault("guardians", {})[clean_phone] = gid
            is_primary = raw_relation == label
            link_id = legacy_uuid("student_guardians", f"{legacy_id}:{clean_phone}")
            insert_row(
                ctx,
                "student_guardians",
                {
                    "id": link_id,
                    "student_id": new_id,
                    "guardian_id": gid,
                    "is_primary": is_primary,
                },
                collection="students.guardians",
            )
        total_due = as_decimal(item.get("totalDue", 0))
        if total_due > 0:
            try:
                count = int(item.get("installments") or 1)
            except (TypeError, ValueError):
                count = 1
            if count < 1:
                count = 1
            start_date = earliest_by_student.get(str(legacy_id)) or _today()
            plan_id = legacy_uuid("fee_plans", str(legacy_id))
            insert_row(
                ctx,
                "fee_plans",
                {
                    "id": plan_id,
                    "student_id": new_id,
                    "total_amount": total_due,
                    "count": count,
                    "start_date": start_date,
                    "interval_days": 30,
                },
                collection="students.fee_plans",
            )
            ctx.plans_by_student[str(legacy_id)] = plan_id
            _remember(ctx, "fee_plans", legacy_id, plan_id)


def import_installments(ctx: ImportContext) -> None:
    items = ctx.payload.get("installments") or []
    result = ctx.report.result_for("installments")
    result.read = len(items)
    running: dict[str, int] = {}
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("installments: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("installments: missing id skipped")
            continue
        sid = item.get("studentId") or item.get("student_id")
        plan_id = ctx.plans_by_student.get(str(sid)) if sid is not None else None
        if plan_id is None:
            result.skipped += 1
            result.warnings.append(f"installments {legacy_id}: no fee plan skipped")
            continue
        key = str(sid)
        running[key] = running.get(key, 0) + 1
        try:
            seq_no = int(item.get("number", running[key]))
        except (TypeError, ValueError):
            seq_no = running[key]
        if seq_no < 1:
            seq_no = running[key]
        amount = as_decimal(item.get("amount", 0))
        paid_amount = as_decimal(item.get("paid", 0))
        if paid_amount >= amount and amount > 0:
            status = "paid"
        elif paid_amount > 0:
            status = "partial"
        else:
            status = "pending"
        due_date = as_date(item.get("dueDate") or item.get("due_date")) or _today()
        new_id = legacy_uuid("installments", str(legacy_id))
        stored = insert_row(
            ctx,
            "installments",
            {
                "id": new_id,
                "fee_plan_id": plan_id,
                "seq_no": seq_no,
                "due_date": due_date,
                "amount": amount,
                "paid_amount": paid_amount,
                "status": status,
            },
            collection="installments",
        )
        if stored is not None:
            _remember(ctx, "installments", legacy_id, new_id)


def import_payments(ctx: ImportContext) -> None:
    items = ctx.payload.get("payments") or []
    result = ctx.report.result_for("payments")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("payments: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("payments: missing id skipped")
            continue
        amount = as_decimal(item.get("amount", 0))
        if amount <= 0:
            result.skipped += 1
            result.warnings.append(f"payments {legacy_id}: non-positive amount skipped")
            continue
        sid = item.get("studentId") or item.get("student_id")
        student_id = _lookup(ctx, "students", sid)
        if student_id is None:
            result.skipped += 1
            result.warnings.append(f"payments {legacy_id}: unknown student skipped")
            continue
        raw_method = item.get("method")
        method = map_method(raw_method)
        if method is None:
            method = "كاش"
        elif _clean_text(raw_method) not in _VALID_METHODS and _clean_text(raw_method) not in _METHOD_MAP:
            result.warnings.append(f"payments {legacy_id}: unknown method mapped to cash")
        paid_on = as_date(item.get("date") or item.get("paid_on")) or _today()
        new_id = legacy_uuid("payments", str(legacy_id))
        row: dict = {
            "id": new_id,
            "student_id": student_id,
            "amount": amount,
            "method": method,
            "paid_on": paid_on,
        }
        note = _clean_text(item.get("note"))
        if note:
            row["note"] = note
        stored = insert_row(ctx, "payments", row, collection="payments")
        if stored is not None:
            _remember(ctx, "payments", legacy_id, new_id)


def import_receipts(ctx: ImportContext) -> None:
    items = ctx.payload.get("receipts") or []
    result = ctx.report.result_for("receipts")
    result.read = len(items)
    max_no = 0
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("receipts: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("receipts: missing id skipped")
            continue
        pid = item.get("paymentId") or item.get("payment_id")
        payment_id = _lookup(ctx, "payments", pid)
        if payment_id is None:
            result.skipped += 1
            result.warnings.append(f"receipts {legacy_id}: unknown payment skipped")
            continue
        new_id = legacy_uuid("receipts", str(legacy_id))
        row: dict = {"id": new_id, "payment_id": payment_id}
        raw_no = item.get("no") or item.get("receipt_no")
        try:
            receipt_no = int(raw_no) if raw_no is not None else None
        except (TypeError, ValueError):
            receipt_no = None
        if receipt_no is not None and receipt_no > 0:
            row["receipt_no"] = receipt_no
            if receipt_no > max_no:
                max_no = receipt_no
        issued = _as_ts(item.get("date") or item.get("issued_on"))
        if issued is not None:
            row["issued_on"] = issued
        stored = insert_row(ctx, "receipts", row, collection="receipts")
        if stored is not None:
            _remember(ctx, "receipts", legacy_id, new_id)
    try:
        with ctx.conn.transaction():
            cur = ctx.conn.execute("SELECT MAX(receipt_no) AS m FROM receipts")
            found = cur.fetchone()
            current_max = 0
            if found is not None:
                current_max = found["m"] if isinstance(found, dict) else found[0]
                current_max = int(current_max or 0)
            if current_max > 0:
                ctx.conn.execute("SELECT setval('receipts_receipt_no_seq', %s)", (current_max,))
    except psycopg.Error as exc:
        result.warnings.append(f"receipts setval failed ({type(exc).__name__})")


def _ensure_category(ctx: ImportContext, name: str) -> uuid.UUID | None:
    cleaned = (name or "").strip() or "أخرى"
    if cleaned in ctx.category_by_name:
        return ctx.category_by_name[cleaned]
    new_id = legacy_uuid("expense_categories", cleaned)
    stored = insert_row(
        ctx,
        "expense_categories",
        {"id": new_id, "name": cleaned},
        collection="expenses",
    )
    if stored is not None:
        ctx.category_by_name[cleaned] = new_id
        _remember(ctx, "expense_categories", cleaned, new_id)
        return new_id
    try:
        with ctx.conn.transaction():
            cur = ctx.conn.execute(
                "SELECT id FROM expense_categories WHERE branch_id = %s AND name = %s LIMIT 1",
                (str(ctx.branch_id), cleaned),
            )
            found = cur.fetchone()
            if found is not None:
                existing = found["id"] if isinstance(found, dict) else found[0]
                ctx.category_by_name[cleaned] = existing
                return existing
    except psycopg.Error:
        pass
    return ctx.category_by_name.get(cleaned)


def import_expenses(ctx: ImportContext) -> None:
    items = ctx.payload.get("expenses") or []
    result = ctx.report.result_for("expenses")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("expenses: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("expenses: missing id skipped")
            continue
        description = _clean_text(item.get("description")) or _clean_text(item.get("category")) or "مصروف"
        category_id = _ensure_category(ctx, _clean_text(item.get("category")) or "أخرى")
        if category_id is None:
            result.skipped += 1
            result.warnings.append(f"expenses {legacy_id}: no category skipped")
            continue
        amount = as_decimal(item.get("amount", 0))
        paid_on = as_date(item.get("date") or item.get("paid_on")) or _today()
        raw_method = item.get("method")
        method = map_method(raw_method) if _clean_text(raw_method) else None
        if _clean_text(raw_method) and method == "كاش" and _clean_text(raw_method) not in _VALID_METHODS and _clean_text(raw_method) not in _METHOD_MAP:
            result.warnings.append(f"expenses {legacy_id}: unknown method mapped to cash")
        parts = [
            _clean_text(item.get("note")),
            _clean_text(item.get("vendor")),
            _clean_text(item.get("invoiceNo") or item.get("invoice_no")),
        ]
        note = " | ".join(p for p in parts if p) or None
        new_id = legacy_uuid("expenses", str(legacy_id))
        row: dict = {
            "id": new_id,
            "description": description,
            "category_id": category_id,
            "amount": amount,
            "paid_on": paid_on,
        }
        if method is not None:
            row["method"] = method
        if note is not None:
            row["note"] = note
        stored = insert_row(ctx, "expenses", row, collection="expenses")
        if stored is not None:
            _remember(ctx, "expenses", legacy_id, new_id)


def import_accounts(ctx: ImportContext) -> None:
    items = ctx.payload.get("accounts") or []
    result = ctx.report.result_for("accounts")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("accounts: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("accounts: missing id skipped")
            continue
        name = _clean_text(item.get("name")) or f"account-{legacy_id}"
        kind = map_account_kind(item.get("type") or item.get("kind"))
        opening = as_decimal(item.get("opening", item.get("balance", 0)))
        new_id = legacy_uuid("accounts", str(legacy_id))
        stored = insert_row(
            ctx,
            "ledger_accounts",
            {"id": new_id, "name": name, "kind": kind, "opening_balance": opening},
            collection="accounts",
        )
        if stored is not None:
            _remember(ctx, "accounts", legacy_id, new_id)


def import_accountMoves(ctx: ImportContext) -> None:
    items = ctx.payload.get("accountMoves") or []
    result = ctx.report.result_for("accountMoves")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("accountMoves: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("accountMoves: missing id skipped")
            continue
        account_id = _lookup(ctx, "accounts", item.get("accountId") or item.get("account_id"))
        if account_id is None:
            result.skipped += 1
            result.warnings.append(f"accountMoves {legacy_id}: unknown account skipped")
            continue
        amount = as_decimal(item.get("amount", 0))
        if amount <= 0:
            result.skipped += 1
            result.warnings.append(f"accountMoves {legacy_id}: non-positive amount skipped")
            continue
        entry_type = map_entry_type(item.get("type"))
        occurred_on = as_date(item.get("date") or item.get("occurred_on")) or _today()
        new_id = legacy_uuid("accountMoves", str(legacy_id))
        row: dict = {
            "id": new_id,
            "account_id": account_id,
            "entry_type": entry_type,
            "amount": amount,
            "occurred_on": occurred_on,
        }
        source_type = _clean_text(item.get("sourceType"))
        source_id = item.get("sourceId")
        if source_type:
            row["ref_table"] = source_type
        if source_type and source_id is not None and str(source_id).strip() != "":
            row["ref_id"] = legacy_uuid(source_type, str(source_id))
        stored = insert_row(ctx, "ledger_entries", row, collection="accountMoves")
        if stored is not None:
            _remember(ctx, "accountMoves", legacy_id, new_id)


def import_attendance(ctx: ImportContext) -> None:
    items = ctx.payload.get("attendance") or []
    result = ctx.report.result_for("attendance")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("attendance: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("attendance: missing id skipped")
            continue
        student_id = _lookup(ctx, "students", item.get("studentId") or item.get("student_id"))
        if student_id is None:
            result.skipped += 1
            result.warnings.append(f"attendance {legacy_id}: unknown student skipped")
            continue
        day = as_date(item.get("date"))
        if day is None:
            result.skipped += 1
            result.warnings.append(f"attendance {legacy_id}: missing date skipped")
            continue
        new_id = legacy_uuid("attendance", str(legacy_id))
        row: dict = {
            "id": new_id,
            "student_id": student_id,
            "date": day,
            "status": map_attendance_status(item.get("status")),
        }
        note = _clean_text(item.get("note"))
        if note:
            row["note"] = note
        recorder = _lookup(ctx, "users", item.get("teacherId") or item.get("teacher_id"))
        if recorder is not None:
            row["recorded_by_user_id"] = recorder
        stored = insert_row(
            ctx,
            "student_attendance",
            row,
            conflict="(student_id, date)",
            collection="attendance",
        )
        if stored is not None:
            _remember(ctx, "attendance", legacy_id, new_id)


def import_staffAttendance(ctx: ImportContext) -> None:
    items = ctx.payload.get("staffAttendance") or []
    result = ctx.report.result_for("staffAttendance")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("staffAttendance: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("staffAttendance: missing id skipped")
            continue
        staff_id = _lookup(ctx, "staff", item.get("staffId") or item.get("staff_id"))
        if staff_id is None:
            result.skipped += 1
            result.warnings.append(f"staffAttendance {legacy_id}: unknown staff skipped")
            continue
        day = as_date(item.get("date"))
        if day is None:
            result.skipped += 1
            result.warnings.append(f"staffAttendance {legacy_id}: missing date skipped")
            continue
        new_id = legacy_uuid("staffAttendance", str(legacy_id))
        row: dict = {
            "id": new_id,
            "staff_id": staff_id,
            "date": day,
            "status": map_attendance_status(item.get("status")),
        }
        note = _clean_text(item.get("note"))
        if note:
            row["note"] = note
        stored = insert_row(
            ctx,
            "staff_attendance",
            row,
            conflict="(staff_id, date)",
            collection="staffAttendance",
        )
        if stored is not None:
            _remember(ctx, "staffAttendance", legacy_id, new_id)


def import_staffAdvances(ctx: ImportContext) -> None:
    items = ctx.payload.get("staffAdvances") or []
    result = ctx.report.result_for("staffAdvances")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("staffAdvances: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("staffAdvances: missing id skipped")
            continue
        staff_id = _lookup(ctx, "staff", item.get("staffId") or item.get("staff_id"))
        if staff_id is None:
            result.skipped += 1
            result.warnings.append(f"staffAdvances {legacy_id}: unknown staff skipped")
            continue
        amount = as_decimal(item.get("amount", 0))
        if amount <= 0:
            result.skipped += 1
            result.warnings.append(f"staffAdvances {legacy_id}: non-positive amount skipped")
            continue
        day = as_date(item.get("date")) or _today()
        remaining = as_decimal(item.get("remaining", 1))
        settled = str(item.get("status") or "").strip() == "مسدد" or remaining <= 0
        note = _clean_text(item.get("reason") or item.get("note"))
        new_id = legacy_uuid("staffAdvances", str(legacy_id))
        row: dict = {
            "id": new_id,
            "staff_id": staff_id,
            "amount": amount,
            "date": day,
            "settled": settled,
        }
        if note:
            row["note"] = note
        stored = insert_row(ctx, "staff_advances", row, collection="staffAdvances")
        if stored is not None:
            _remember(ctx, "staffAdvances", legacy_id, new_id)


def import_staffAssets(ctx: ImportContext) -> None:
    items = ctx.payload.get("staffAssets") or []
    result = ctx.report.result_for("staffAssets")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("staffAssets: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("staffAssets: missing id skipped")
            continue
        staff_id = _lookup(ctx, "staff", item.get("staffId") or item.get("staff_id"))
        if staff_id is None:
            result.skipped += 1
            result.warnings.append(f"staffAssets {legacy_id}: unknown staff skipped")
            continue
        asset_name = (
            _clean_text(item.get("asset"))
            or _clean_text(item.get("asset_name"))
            or _clean_text(item.get("serial"))
            or f"asset-{legacy_id}"
        )
        day = as_date(item.get("date")) or _today()
        parts = [_clean_text(item.get("note")), _clean_text(item.get("status")), _clean_text(item.get("serial"))]
        note = " | ".join(p for p in parts if p) or None
        new_id = legacy_uuid("staffAssets", str(legacy_id))
        row: dict = {"id": new_id, "staff_id": staff_id, "asset_name": asset_name, "date": day}
        if note:
            row["note"] = note
        stored = insert_row(ctx, "staff_assets", row, collection="staffAssets")
        if stored is not None:
            _remember(ctx, "staffAssets", legacy_id, new_id)


def import_staffMovements(ctx: ImportContext) -> None:
    items = ctx.payload.get("staffMovements") or []
    result = ctx.report.result_for("staffMovements")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("staffMovements: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("staffMovements: missing id skipped")
            continue
        staff_id = _lookup(ctx, "staff", item.get("staffId") or item.get("staff_id"))
        if staff_id is None:
            result.skipped += 1
            result.warnings.append(f"staffMovements {legacy_id}: unknown staff skipped")
            continue
        day = as_date(item.get("date")) or _today()
        parts = [
            _clean_text(item.get("type")),
            _clean_text(item.get("description")),
            _clean_text(item.get("note")),
        ]
        amount_raw = item.get("amount")
        if amount_raw is not None and str(amount_raw).strip() != "":
            parts.append(str(amount_raw).strip())
        note = " | ".join(p for p in parts if p) or None
        new_id = legacy_uuid("staffMovements", str(legacy_id))
        row: dict = {"id": new_id, "staff_id": staff_id, "date": day}
        if note:
            row["note"] = note
        stored = insert_row(ctx, "staff_movements", row, collection="staffMovements")
        if stored is not None:
            _remember(ctx, "staffMovements", legacy_id, new_id)


def import_evaluations(ctx: ImportContext) -> None:
    items = ctx.payload.get("evaluations") or []
    result = ctx.report.result_for("evaluations")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("evaluations: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("evaluations: missing id skipped")
            continue
        student_id = _lookup(ctx, "students", item.get("studentId") or item.get("student_id"))
        if student_id is None:
            result.skipped += 1
            result.warnings.append(f"evaluations {legacy_id}: unknown student skipped")
            continue
        new_id = legacy_uuid("evaluations", str(legacy_id))
        row: dict = {
            "id": new_id,
            "student_id": student_id,
            "subject": _clean_text(item.get("subject")) or "لغتي",
            "eval_type": map_eval_type(item.get("type")),
            "date": as_date(item.get("date")) or _today(),
            "value": as_decimal(item.get("value", 0)),
        }
        teacher = _lookup(ctx, "users", item.get("teacherId") or item.get("teacher_id"))
        if teacher is not None:
            row["teacher_user_id"] = teacher
        stored = insert_row(ctx, "evaluations", row, collection="evaluations")
        if stored is not None:
            _remember(ctx, "evaluations", legacy_id, new_id)


def import_skillProgress(ctx: ImportContext) -> None:
    items = ctx.payload.get("skillProgress") or []
    result = ctx.report.result_for("skillProgress")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("skillProgress: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("skillProgress: missing id skipped")
            continue
        student_id = _lookup(ctx, "students", item.get("studentId") or item.get("student_id"))
        if student_id is None:
            result.skipped += 1
            result.warnings.append(f"skillProgress {legacy_id}: unknown student skipped")
            continue
        level = _clean_text(item.get("level"))
        if level not in _VALID_LEVELS:
            level = "جيد"
        new_id = legacy_uuid("skillProgress", str(legacy_id))
        row: dict = {
            "id": new_id,
            "student_id": student_id,
            "subject": _clean_text(item.get("subject")) or "عام",
            "skill": _clean_text(item.get("skill")) or "عام",
            "level": level,
            "date": as_date(item.get("date")) or _today(),
        }
        note = _clean_text(item.get("note"))
        if note:
            row["note"] = note
        stored = insert_row(ctx, "skill_progress", row, collection="skillProgress")
        if stored is not None:
            _remember(ctx, "skillProgress", legacy_id, new_id)


def import_studentPlans(ctx: ImportContext) -> None:
    items = ctx.payload.get("studentPlans") or []
    result = ctx.report.result_for("studentPlans")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("studentPlans: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("studentPlans: missing id skipped")
            continue
        student_id = _lookup(ctx, "students", item.get("studentId") or item.get("student_id"))
        if student_id is None:
            result.skipped += 1
            result.warnings.append(f"studentPlans {legacy_id}: unknown student skipped")
            continue
        new_id = legacy_uuid("studentPlans", str(legacy_id))
        row: dict = {
            "id": new_id,
            "student_id": student_id,
            "subject": _clean_text(item.get("subject")) or "عام",
            "skill": _clean_text(item.get("skill")) or "عام",
            "start_date": as_date(item.get("startDate") or item.get("start_date")) or _today(),
        }
        for key, legacy_key in (
            ("goal", "goal"),
            ("end_date", "endDate"),
            ("activities", "activities"),
            ("notes", "notes"),
        ):
            raw = item.get(legacy_key)
            if legacy_key in ("endDate",):
                value = as_date(raw)
            else:
                value = _clean_text(raw)
            if value is not None:
                row[key] = value
        stored = insert_row(ctx, "study_plans", row, collection="studentPlans")
        if stored is not None:
            _remember(ctx, "studentPlans", legacy_id, new_id)


def import_assignments(ctx: ImportContext) -> None:
    items = ctx.payload.get("assignments") or []
    result = ctx.report.result_for("assignments")
    result.read = len(items)
    valid_subjects = ("القرآن", "لغتي", "الإنجليزي", "الرياضيات")
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("assignments: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("assignments: missing id skipped")
            continue
        title = _clean_text(item.get("title")) or f"assignment-{legacy_id}"
        subject = _clean_text(item.get("subject"))
        if subject not in valid_subjects:
            subject = None
        new_id = legacy_uuid("assignments", str(legacy_id))
        row: dict = {
            "id": new_id,
            "title": title,
            "due_date": as_date(item.get("dueDate") or item.get("due_date")) or _today(),
        }
        if subject is not None:
            row["subject"] = subject
        kind = _clean_text(item.get("kind"))
        if kind:
            row["kind"] = kind
        instructions = _clean_text(item.get("instructions"))
        if instructions:
            row["instructions"] = instructions
        page_ref = _clean_text(item.get("pageRef") or item.get("page_ref"))
        if page_ref:
            row["page_ref"] = page_ref
        teacher = _lookup(ctx, "users", item.get("teacherId") or item.get("teacher_id"))
        if teacher is not None:
            row["teacher_user_id"] = teacher
        stored = insert_row(ctx, "assignments", row, collection="assignments")
        if stored is None:
            continue
        _remember(ctx, "assignments", legacy_id, new_id)
        student_ids = item.get("studentIds") or item.get("student_ids") or []
        if isinstance(student_ids, (str, int)):
            student_ids = [student_ids]
        for sid in student_ids:
            student_id = _lookup(ctx, "students", sid)
            if student_id is None:
                result.warnings.append(f"assignments {legacy_id}: unknown student {sid} skipped")
                continue
            link_id = legacy_uuid("assignment_students", f"{legacy_id}:{sid}")
            insert_row(
                ctx,
                "assignment_students",
                {"id": link_id, "assignment_id": new_id, "student_id": student_id},
                collection="assignments",
            )


def import_lessonLogs(ctx: ImportContext) -> None:
    items = ctx.payload.get("lessonLogs") or []
    result = ctx.report.result_for("lessonLogs")
    result.read = len(items)
    valid_status = ("تمت", "مؤجلة", "ملغاة")
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("lessonLogs: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("lessonLogs: missing id skipped")
            continue
        schedule_id = item.get("scheduleId") or item.get("schedule_id")
        if schedule_id is None or str(schedule_id).strip() == "":
            result.skipped += 1
            result.warnings.append(f"lessonLogs {legacy_id}: no schedule skipped")
            continue
        new_id = legacy_uuid("lessonLogs", str(legacy_id))
        status = _clean_text(item.get("status"))
        if status not in valid_status:
            status = "تمت"
        row: dict = {
            "id": new_id,
            "schedule_id": str(schedule_id),
            "date": as_date(item.get("date")) or _today(),
            "status": status,
        }
        for key, legacy_key in (
            ("covered", "covered"),
            ("homework", "homework"),
            ("notes", "notes"),
        ):
            value = _clean_text(item.get(legacy_key))
            if value:
                row[key] = value
        teacher = _lookup(ctx, "users", item.get("teacherId") or item.get("teacher_id"))
        if teacher is not None:
            row["teacher_user_id"] = teacher
        stored = insert_row(ctx, "lesson_logs", row, collection="lessonLogs")
        if stored is not None:
            _remember(ctx, "lessonLogs", legacy_id, new_id)


def import_tasks(ctx: ImportContext) -> None:
    items = ctx.payload.get("tasks") or []
    result = ctx.report.result_for("tasks")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("tasks: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("tasks: missing id skipped")
            continue
        title = _clean_text(item.get("title")) or f"task-{legacy_id}"
        priority = _clean_text(item.get("priority"))
        if priority not in ("high", "medium", "low"):
            priority = "medium"
        status = _clean_text(item.get("status"))
        if status not in ("open", "in_progress", "done", "cancelled"):
            status = "open"
        new_id = legacy_uuid("tasks", str(legacy_id))
        row: dict = {"id": new_id, "title": title, "priority": priority, "status": status}
        student_id = _lookup(ctx, "students", item.get("studentId") or item.get("student_id"))
        if student_id is not None:
            row["student_id"] = student_id
        assignee = _lookup(ctx, "users", item.get("assigneeId") or item.get("assignee_user_id"))
        if assignee is not None:
            row["assignee_user_id"] = assignee
        due = as_date(item.get("dueDate") or item.get("due_date"))
        if due is not None:
            row["due_date"] = due
        description = _clean_text(item.get("description"))
        if description:
            row["description"] = description
        stored = insert_row(ctx, "tasks", row, collection="tasks")
        if stored is not None:
            _remember(ctx, "tasks", legacy_id, new_id)


def import_certificates(ctx: ImportContext) -> None:
    items = ctx.payload.get("certificates") or []
    result = ctx.report.result_for("certificates")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("certificates: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("certificates: missing id skipped")
            continue
        student_id = _lookup(ctx, "students", item.get("studentId") or item.get("student_id"))
        if student_id is None:
            result.skipped += 1
            result.warnings.append(f"certificates {legacy_id}: unknown student skipped")
            continue
        title = _clean_text(item.get("title")) or f"certificate-{legacy_id}"
        new_id = legacy_uuid("certificates", str(legacy_id))
        row: dict = {
            "id": new_id,
            "student_id": student_id,
            "title": title,
            "issued_on": as_date(item.get("date") or item.get("issued_on")) or _today(),
        }
        reason = _clean_text(item.get("reason"))
        if reason:
            row["reason"] = reason
        stored = insert_row(ctx, "certificates", row, collection="certificates")
        if stored is not None:
            _remember(ctx, "certificates", legacy_id, new_id)


def import_dismissals(ctx: ImportContext) -> None:
    items = ctx.payload.get("dismissals") or []
    result = ctx.report.result_for("dismissals")
    result.read = len(items)
    valid_methods = ("استلام من ولي الأمر", "استلام من شخص مصرح", "الباص", "استلام إداري بإذن")
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("dismissals: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("dismissals: missing id skipped")
            continue
        student_id = _lookup(ctx, "students", item.get("studentId") or item.get("student_id"))
        if student_id is None:
            result.skipped += 1
            result.warnings.append(f"dismissals {legacy_id}: unknown student skipped")
            continue
        method = _clean_text(item.get("method"))
        if method not in valid_methods:
            method = "استلام من ولي الأمر"
        new_id = legacy_uuid("dismissals", str(legacy_id))
        row: dict = {
            "id": new_id,
            "student_id": student_id,
            "date": as_date(item.get("date")) or _today(),
            "method": method,
        }
        for key, legacy_key in (
            ("receiver_name", "receiverName"),
            ("receiver_relation", "receiverRelation"),
            ("receiver_phone", "receiverPhone"),
            ("receiver_id_no", "receiverIdNo"),
            ("note", "note"),
        ):
            value = _clean_text(item.get(legacy_key))
            if value:
                row[key] = value
        recorder = _lookup(ctx, "users", item.get("teacherId") or item.get("recordedBy"))
        if recorder is not None:
            row["recorded_by_user_id"] = recorder
        stored = insert_row(ctx, "dismissals", row, collection="dismissals")
        if stored is not None:
            _remember(ctx, "dismissals", legacy_id, new_id)


def import_followups(ctx: ImportContext) -> None:
    items = ctx.payload.get("followups") or []
    result = ctx.report.result_for("followups")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("followups: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("followups: missing id skipped")
            continue
        student_id = _lookup(ctx, "students", item.get("studentId") or item.get("student_id"))
        if student_id is None:
            result.skipped += 1
            result.warnings.append(f"followups {legacy_id}: unknown student skipped")
            continue
        new_id = legacy_uuid("followups", str(legacy_id))
        row: dict = {
            "id": new_id,
            "student_id": student_id,
            "date": as_date(item.get("date")) or _today(),
        }
        note = _clean_text(item.get("note"))
        if note:
            row["note"] = note
        by_user = _lookup(ctx, "users", item.get("byUserId") or item.get("teacherId"))
        if by_user is not None:
            row["by_user_id"] = by_user
        stored = insert_row(ctx, "student_followups", row, collection="followups")
        if stored is not None:
            _remember(ctx, "followups", legacy_id, new_id)


def import_collectionFollowups(ctx: ImportContext) -> None:
    items = ctx.payload.get("collectionFollowups") or []
    result = ctx.report.result_for("collectionFollowups")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("collectionFollowups: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("collectionFollowups: missing id skipped")
            continue
        installment_id = _lookup(ctx, "installments", item.get("installmentId") or item.get("installment_id"))
        if installment_id is None:
            result.skipped += 1
            result.warnings.append(f"collectionFollowups {legacy_id}: unknown installment skipped")
            continue
        new_id = legacy_uuid("collectionFollowups", str(legacy_id))
        row: dict = {
            "id": new_id,
            "installment_id": installment_id,
            "contacted_on": as_date(item.get("date") or item.get("contacted_on")) or _today(),
        }
        channel = _clean_text(item.get("channel"))
        if channel:
            row["channel"] = channel
        outcome = _clean_text(item.get("outcome"))
        if outcome:
            row["outcome"] = outcome
        stored = insert_row(ctx, "collection_followups", row, collection="collectionFollowups")
        if stored is not None:
            _remember(ctx, "collectionFollowups", legacy_id, new_id)


def _import_message_list(ctx: ImportContext, collection: str) -> None:
    items = ctx.payload.get(collection) or []
    result = ctx.report.result_for(collection)
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append(f"{collection}: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append(f"{collection}: missing id skipped")
            continue
        student_id = _lookup(ctx, "students", item.get("studentId") or item.get("student_id"))
        if student_id is None:
            result.skipped += 1
            result.warnings.append(f"{collection} {legacy_id}: unknown student skipped")
            continue
        body = _clean_text(item.get("body") or item.get("message"))
        if not body:
            result.skipped += 1
            result.warnings.append(f"{collection} {legacy_id}: missing body skipped")
            continue
        channel = _clean_text(item.get("channel"))
        if channel not in ("whatsapp", "sms", "app"):
            channel = "whatsapp"
        new_id = legacy_uuid(collection, str(legacy_id))
        row: dict = {"id": new_id, "student_id": student_id, "channel": channel, "body": body}
        template_key = _clean_text(item.get("templateKey") or item.get("template_key"))
        if template_key:
            row["template_key"] = template_key
        sent = _as_ts(item.get("date") or item.get("sent_at"))
        if sent is not None:
            row["sent_at"] = sent
        status = _clean_text(item.get("status"))
        if status in ("queued", "sent", "failed"):
            row["status"] = status
        stored = insert_row(ctx, "messages", row, collection=collection)
        if stored is not None:
            _remember(ctx, collection, legacy_id, new_id)


def import_parentMessages(ctx: ImportContext) -> None:
    _import_message_list(ctx, "parentMessages")


def import_communication(ctx: ImportContext) -> None:
    _import_message_list(ctx, "communication")


def import_monthClosures(ctx: ImportContext) -> None:
    import re

    items = ctx.payload.get("monthClosures") or []
    result = ctx.report.result_for("monthClosures")
    result.read = len(items)
    pattern = re.compile(r"^[0-9]{4}-(0[1-9]|1[0-2])$")
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("monthClosures: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("monthClosures: missing id skipped")
            continue
        month = _clean_text(item.get("month"))
        if not month or not pattern.match(month):
            result.skipped += 1
            result.warnings.append(f"monthClosures {legacy_id}: invalid month skipped")
            continue
        new_id = legacy_uuid("monthClosures", str(legacy_id))
        row: dict = {"id": new_id, "month": month}
        totals = item.get("totals") or item.get("totals_json") or {}
        row["totals_json"] = totals if isinstance(totals, dict) else {"value": str(totals)}
        closed_by = _lookup(ctx, "users", item.get("closedBy"))
        if closed_by is not None:
            row["closed_by"] = closed_by
        closed_at = _as_ts(item.get("closedAt") or item.get("date"))
        if closed_at is not None:
            row["closed_at"] = closed_at
        stored = insert_row(ctx, "month_closures", row, collection="monthClosures")
        if stored is not None:
            _remember(ctx, "monthClosures", legacy_id, new_id)


def import_payroll(ctx: ImportContext) -> None:
    import re

    items = ctx.payload.get("payroll") or []
    result = ctx.report.result_for("payroll")
    result.read = len(items)
    pattern = re.compile(r"^[0-9]{4}-(0[1-9]|1[0-2])$")
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("payroll: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("payroll: missing id skipped")
            continue
        staff_id = _lookup(ctx, "staff", item.get("staffId") or item.get("staff_id"))
        if staff_id is None:
            result.skipped += 1
            result.warnings.append(f"payroll {legacy_id}: unknown staff skipped")
            continue
        month = _clean_text(item.get("month"))
        if not month or not pattern.match(month):
            result.skipped += 1
            result.warnings.append(f"payroll {legacy_id}: invalid month skipped")
            continue
        new_id = legacy_uuid("payroll", str(legacy_id))
        row: dict = {
            "id": new_id,
            "staff_id": staff_id,
            "month": month,
            "base": as_decimal(item.get("base", 0)),
            "allowances": as_decimal(item.get("allowances", 0)),
            "bonus": as_decimal(item.get("bonus", 0)),
            "deductions": as_decimal(item.get("deductions", 0)),
            "advance_deducted": as_decimal(item.get("advanceDeducted", 0)),
        }
        note = _clean_text(item.get("note"))
        if note:
            row["note"] = note
        stored = insert_row(ctx, "payroll_runs", row, collection="payroll")
        if stored is not None:
            _remember(ctx, "payroll", legacy_id, new_id)


def import_auditLog(ctx: ImportContext) -> None:
    items = ctx.payload.get("auditLog") or []
    result = ctx.report.result_for("auditLog")
    result.read = len(items)
    for item in items:
        if not isinstance(item, dict):
            result.skipped += 1
            result.warnings.append("auditLog: invalid row skipped")
            continue
        legacy_id = item.get("id")
        if legacy_id is None or str(legacy_id).strip() == "":
            result.skipped += 1
            result.warnings.append("auditLog: missing id skipped")
            continue
        new_id = legacy_uuid("auditLog", str(legacy_id))
        row: dict = {
            "id": new_id,
            "action": _clean_text(item.get("action")) or "import",
            "entity": _clean_text(item.get("entity")) or "legacy",
        }
        entity_id = item.get("entityId")
        if entity_id is not None and str(entity_id).strip() != "":
            try:
                row["entity_id"] = uuid.UUID(str(entity_id))
            except ValueError:
                pass
        details = {
            "legacy_user": item.get("user") or item.get("actor"),
            "legacy_details": item.get("details") or item.get("note"),
        }
        row["details_json"] = details
        at = _as_ts(item.get("at") or item.get("date"))
        if at is not None:
            row["at"] = at
        stored = insert_row(ctx, "audit_log", row, collection="auditLog")
        if stored is not None:
            _remember(ctx, "auditLog", legacy_id, new_id)


KNOWN_COLLECTIONS = (
    "rooms",
    "staff",
    "users",
    "permissions",
    "students",
    "installments",
    "payments",
    "receipts",
    "expenses",
    "accounts",
    "accountMoves",
    "attendance",
    "staffAttendance",
    "staffAdvances",
    "staffAssets",
    "staffMovements",
    "evaluations",
    "skillProgress",
    "studentPlans",
    "assignments",
    "lessonLogs",
    "tasks",
    "certificates",
    "dismissals",
    "followups",
    "collectionFollowups",
    "parentMessages",
    "communication",
    "monthClosures",
    "payroll",
    "auditLog",
)


def unwrap_payload(obj) -> dict:
    """Accept the raw object or the versioned download wrapper."""
    if not isinstance(obj, dict):
        raise ValueError("backup payload must be a JSON object")
    data = obj.get("data")
    if isinstance(data, dict):
        return data
    if "version" in obj:
        raise ValueError("unsupported backup wrapper")
    return obj


def import_backup(conn, payload: dict, *, branch_id: uuid.UUID, dry_run: bool = False) -> ImportReport:
    """Import one backup payload inside a single transaction."""
    data = unwrap_payload(payload)
    report = ImportReport()
    ctx = ImportContext(conn=conn, branch_id=branch_id, payload=data, report=report, dry_run=dry_run)
    try:
        with conn.transaction():
            import_rooms(ctx)
            import_staff(ctx)
            import_users(ctx)
            import_students(ctx)
            import_installments(ctx)
            import_payments(ctx)
            import_receipts(ctx)
            import_expenses(ctx)
            import_accounts(ctx)
            import_accountMoves(ctx)
            import_attendance(ctx)
            import_staffAttendance(ctx)
            import_staffAdvances(ctx)
            import_staffAssets(ctx)
            import_staffMovements(ctx)
            import_evaluations(ctx)
            import_skillProgress(ctx)
            import_studentPlans(ctx)
            import_assignments(ctx)
            import_lessonLogs(ctx)
            import_tasks(ctx)
            import_certificates(ctx)
            import_dismissals(ctx)
            import_followups(ctx)
            import_collectionFollowups(ctx)
            import_parentMessages(ctx)
            import_communication(ctx)
            import_monthClosures(ctx)
            import_payroll(ctx)
            import_auditLog(ctx)
            for key, value in data.items():
                if key not in KNOWN_COLLECTIONS:
                    items = value if isinstance(value, list) else []
                    unknown = report.result_for(key)
                    unknown.read = len(items)
                    unknown.skipped = len(items)
                    unknown.warnings.append("unknown collection - skipped")
            if dry_run:
                raise _DryRunRollback()
    except _DryRunRollback:
        pass
    return report
