"""Repository for Staff Geofenced Attendance & Shift Management (Milestone M6)."""

from __future__ import annotations

from datetime import date as dt_date, datetime, time as dt_time, timedelta, timezone
import math
from typing import Any
from uuid import UUID

from edutrack_api.audit import write_audit
from edutrack_api.errors import ApiError

RIYADH_TZ = timezone(timedelta(hours=3))


def haversine_distance_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Calculate the great-circle distance between two points in meters using the Haversine formula."""
    r_earth = 6371000.0  # Earth radius in meters
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)

    a = (
        math.sin(delta_phi / 2.0) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2
    )
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
    return r_earth * c


def get_geofence_settings(conn) -> dict[str, Any]:
    """Retrieve center geofence configurations from system_settings."""
    rows = conn.execute("""
        SELECT key, value FROM system_settings
        WHERE key IN (
            'geofence_center_lat', 'geofence_center_lng',
            'geofence_radius_meters', 'geofence_max_accuracy_meters',
            'geofence_grace_period_minutes'
        )
    """).fetchall()
    d = {r["key"]: r["value"] for r in rows}
    return {
        "center_lat": float(d.get("geofence_center_lat", 24.7136)),
        "center_lng": float(d.get("geofence_center_lng", 46.6753)),
        "radius_meters": float(d.get("geofence_radius_meters", 200.0)),
        "max_accuracy_meters": float(d.get("geofence_max_accuracy_meters", 50.0)),
        "grace_period_minutes": int(d.get("geofence_grace_period_minutes", 15)),
    }


def resolve_staff_id_for_user(conn, user: dict) -> UUID:
    """Return the staff_id linked to the user, or reject unlinked accounts."""
    staff_id = user.get("staff_id")
    if not staff_id:
        raise ApiError(422, "STAFF_NOT_LINKED", "حسابك غير مرتبط بسجل موظف. يرجى مراجعة الإدارة")
    return UUID(str(staff_id))


def get_staff_shift_for_date(conn, staff_id: UUID, query_date: dt_date) -> dict[str, Any]:
    """Retrieve shift definition assigned to staff for the day of week or default active shift."""
    dow = query_date.weekday()  # Python: 0=Mon..6=Sun. PostgreSQL: 0=Sun..6=Sat.
    pg_dow = (dow + 1) % 7

    row = conn.execute("""
        SELECT s.id, s.name, s.start_time, s.end_time, s.grace_period_minutes
        FROM staff_shift_assignments sa
        JOIN staff_shifts s ON s.id = sa.shift_id
        WHERE sa.staff_id = %s AND sa.weekday = %s AND s.is_active = TRUE
        LIMIT 1
    """, (staff_id, pg_dow)).fetchone()

    if row:
        return dict(row)

    # Fallback to default active shift in branch
    def_shift = conn.execute("""
        SELECT id, name, start_time, end_time, grace_period_minutes
        FROM staff_shifts
        WHERE is_active = TRUE
        ORDER BY created_at ASC
        LIMIT 1
    """).fetchone()

    if def_shift:
        return dict(def_shift)

    return {
        "id": None,
        "name": "الدوام الافتراضي",
        "start_time": dt_time(7, 30),
        "end_time": dt_time(14, 0),
        "grace_period_minutes": 15,
    }


def _as_riyadh(value: datetime) -> datetime:
    """Normalize a datetime to Riyadh; naive values are assumed Riyadh-local."""
    return value.astimezone(RIYADH_TZ) if value.tzinfo else value.replace(tzinfo=RIYADH_TZ)


def compute_attendance_minutes(
    shift: dict,
    att_date: dt_date,
    check_in: datetime,
    check_out: datetime | None,
    default_grace: int,
) -> dict[str, Any]:
    """Single source of truth for late/early-leave/work minutes across check-in, check-out and override."""
    grace = shift.get("grace_period_minutes", default_grace)
    shift_start_dt = datetime.combine(att_date, shift["start_time"], tzinfo=RIYADH_TZ)
    shift_end_dt = datetime.combine(att_date, shift["end_time"], tzinfo=RIYADH_TZ)

    check_in_dt = _as_riyadh(check_in)

    late_minutes = 0
    if check_in_dt > shift_start_dt + timedelta(minutes=grace):
        late_minutes = max(0, int((check_in_dt - shift_start_dt).total_seconds() / 60))

    early_leave_minutes = 0
    work_duration_minutes: int | None = None
    if check_out is not None:
        check_out_dt = _as_riyadh(check_out)
        if check_out_dt < shift_end_dt:
            early_leave_minutes = max(0, int((shift_end_dt - check_out_dt).total_seconds() / 60))
        work_duration_minutes = max(0, int((check_out_dt - check_in_dt).total_seconds() / 60))

    return {
        "late_minutes": late_minutes,
        "early_leave_minutes": early_leave_minutes,
        "work_duration_minutes": work_duration_minutes,
    }


def check_in_staff(
    conn,
    user: dict,
    latitude: float,
    longitude: float,
    accuracy: float,
) -> dict[str, Any]:
    """Perform GPS geofenced check-in for a staff member."""
    settings = get_geofence_settings(conn)

    # 1. Accuracy validation
    if accuracy > settings["max_accuracy_meters"]:
        raise ApiError(
            422,
            "INACCURATE_GPS",
            f"دقة إشارة الموقع ضعيفة ({int(accuracy)}م). الحد المقبول {int(settings['max_accuracy_meters'])}م. يرجى المحاولة في مكان مكشوف.",
        )

    # 2. Geofence radius validation
    dist = haversine_distance_meters(
        latitude,
        longitude,
        settings["center_lat"],
        settings["center_lng"],
    )
    if dist > settings["radius_meters"]:
        raise ApiError(
            422,
            "OUTSIDE_GEOFENCE",
            f"أنت خارج نطاق مركز غراس الجغرافي (المسافة: {int(dist)}م، المسموح: {int(settings['radius_meters'])}م).",
        )

    # 3. Server timestamp strictly in Asia/Riyadh
    now_dt = datetime.now(RIYADH_TZ)
    today = now_dt.date()
    staff_id = resolve_staff_id_for_user(conn, user)
    branch_id = user.get("branch_id") or "00000000-0000-0000-0000-000000000001"

    # 4. Check if already checked in today
    existing = conn.execute("""
        SELECT * FROM staff_attendance
        WHERE staff_id = %s AND date = %s
    """, (staff_id, today)).fetchone()

    if existing and existing["check_in_time"] is not None:
        raise ApiError(409, "ALREADY_CHECKED_IN", "تم تسجيل الحضور لهذا اليوم مسبقاً")

    # 5. Shift and Lateness calculation (single source of truth: compute_attendance_minutes)
    shift = get_staff_shift_for_date(conn, staff_id, today)
    minutes = compute_attendance_minutes(shift, today, now_dt, None, settings["grace_period_minutes"])
    late_minutes = minutes["late_minutes"]
    status = "متأخر" if late_minutes > 0 else "حاضر"

    if existing:
        row = conn.execute("""
            UPDATE staff_attendance
            SET check_in_time = %s,
                check_in_lat = %s,
                check_in_lng = %s,
                check_in_accuracy = %s,
                check_in_distance = %s,
                shift_id = %s,
                status = %s,
                late_minutes = %s,
                updated_at = NOW()
            WHERE id = %s
            RETURNING *
        """, (
            now_dt, latitude, longitude, accuracy, round(dist, 2),
            shift["id"], status, late_minutes, existing["id"]
        )).fetchone()
    else:
        row = conn.execute("""
            INSERT INTO staff_attendance (
                branch_id, staff_id, date, status,
                check_in_time, check_in_lat, check_in_lng, check_in_accuracy, check_in_distance,
                shift_id, late_minutes
            ) VALUES (
                %s, %s, %s, %s,
                %s, %s, %s, %s, %s,
                %s, %s
            )
            RETURNING *
        """, (
            branch_id, staff_id, today, status,
            now_dt, latitude, longitude, accuracy, round(dist, 2),
            shift["id"], late_minutes
        )).fetchone()

    write_audit(
        conn,
        actor_user_id=user["id"],
        action="staff_check_in",
        entity="staff_attendance",
        entity_id=row["id"],
        details={
            "distance": round(dist, 2),
            "status": status,
            "late_minutes": late_minutes,
        },
    )
    return dict(row)


def check_out_staff(
    conn,
    user: dict,
    latitude: float,
    longitude: float,
    accuracy: float,
) -> dict[str, Any]:
    """Perform GPS geofenced check-out for a staff member."""
    settings = get_geofence_settings(conn)

    # 1. Accuracy validation
    if accuracy > settings["max_accuracy_meters"]:
        raise ApiError(
            422,
            "INACCURATE_GPS",
            f"دقة إشارة الموقع ضعيفة ({int(accuracy)}م). الحد المقبول {int(settings['max_accuracy_meters'])}م.",
        )

    # 2. Geofence radius validation
    dist = haversine_distance_meters(
        latitude,
        longitude,
        settings["center_lat"],
        settings["center_lng"],
    )
    if dist > settings["radius_meters"]:
        raise ApiError(
            422,
            "OUTSIDE_GEOFENCE",
            f"أنت خارج نطاق مركز غراس الجغرافي (المسافة: {int(dist)}م).",
        )

    # 3. Server timestamp strictly in Asia/Riyadh
    now_dt = datetime.now(RIYADH_TZ)
    today = now_dt.date()
    staff_id = resolve_staff_id_for_user(conn, user)

    # 4. Find today's check-in
    existing = conn.execute("""
        SELECT * FROM staff_attendance
        WHERE staff_id = %s AND date = %s
    """, (staff_id, today)).fetchone()

    if not existing or existing["check_in_time"] is None:
        raise ApiError(400, "NO_CHECK_IN", "لم يتم تسجيل الحضور اليوم بعد")

    if existing["check_out_time"] is not None:
        raise ApiError(409, "ALREADY_CHECKED_OUT", "تم تسجيل الانصراف مسبقاً لهذا اليوم")

    # 5. Shift & Early leave calculation (single source of truth: compute_attendance_minutes)
    shift = get_staff_shift_for_date(conn, staff_id, today)
    minutes = compute_attendance_minutes(
        shift, today, existing["check_in_time"], now_dt, settings["grace_period_minutes"]
    )
    early_leave_minutes = minutes["early_leave_minutes"]
    work_duration = minutes["work_duration_minutes"]

    status = existing["status"]
    if early_leave_minutes > 0 and status in ("حاضر", "present"):
        status = "انصراف مبكر"

    row = conn.execute("""
        UPDATE staff_attendance
        SET check_out_time = %s,
            check_out_lat = %s,
            check_out_lng = %s,
            check_out_accuracy = %s,
            check_out_distance = %s,
            status = %s,
            early_leave_minutes = %s,
            work_duration_minutes = %s,
            updated_at = NOW()
        WHERE id = %s
        RETURNING *
    """, (
        now_dt, latitude, longitude, accuracy, round(dist, 2),
        status, early_leave_minutes, work_duration, existing["id"]
    )).fetchone()

    write_audit(
        conn,
        actor_user_id=user["id"],
        action="staff_check_out",
        entity="staff_attendance",
        entity_id=row["id"],
        details={
            "distance": round(dist, 2),
            "status": status,
            "work_duration_minutes": work_duration,
            "early_leave_minutes": early_leave_minutes,
        },
    )
    return dict(row)


def get_my_today_status(conn, user: dict) -> dict[str, Any]:
    """Retrieve today's attendance and active shift status for logged-in staff."""
    staff_id = resolve_staff_id_for_user(conn, user)
    today = datetime.now(RIYADH_TZ).date()
    shift = get_staff_shift_for_date(conn, staff_id, today)
    settings = get_geofence_settings(conn)

    row = conn.execute("""
        SELECT * FROM staff_attendance
        WHERE staff_id = %s AND date = %s
    """, (staff_id, today)).fetchone()

    return {
        "date": str(today),
        "staff_id": str(staff_id),
        "shift": shift,
        "geofence": {
            "center_lat": settings["center_lat"],
            "center_lng": settings["center_lng"],
            "radius_meters": settings["radius_meters"],
            "max_accuracy_meters": settings["max_accuracy_meters"],
        },
        "attendance": dict(row) if row else None,
    }


def get_my_attendance_history(
    conn,
    user: dict,
    month: str,  # YYYY-MM
) -> dict[str, Any]:
    """Retrieve attendance logs and metrics for staff member in a given month."""
    staff_id = resolve_staff_id_for_user(conn, user)
    rows = conn.execute("""
        SELECT * FROM staff_attendance
        WHERE staff_id = %s AND to_char(date, 'YYYY-MM') = %s
        ORDER BY date DESC
    """, (staff_id, month)).fetchall()

    items = [dict(r) for r in rows]
    total_days = len(items)
    present_days = sum(1 for r in items if r["status"] in ("حاضر", "present"))
    late_days = sum(1 for r in items if r["status"] in ("متأخر", "late") or r.get("late_minutes", 0) > 0)
    early_leave_days = sum(1 for r in items if r["status"] in ("انصراف مبكر", "early_leave") or r.get("early_leave_minutes", 0) > 0)
    absent_days = sum(1 for r in items if r["status"] in ("غائب", "absent"))
    total_work_minutes = sum(r.get("work_duration_minutes", 0) for r in items)

    return {
        "month": month,
        "records": items,
        "summary": {
            "total_days": total_days,
            "present_days": present_days,
            "late_days": late_days,
            "early_leave_days": early_leave_days,
            "absent_days": absent_days,
            "total_work_hours": round(total_work_minutes / 60.0, 1),
        },
    }


def list_staff_attendance_for_supervisor(
    conn,
    date: dt_date | None = None,
    month: str | None = None,
    staff_id: UUID | None = None,
) -> list[dict[str, Any]]:
    """Supervisor/Admin view of staff attendance."""
    query = """
        SELECT sa.*, s.name AS staff_name, s.role_title, sh.name AS shift_name
        FROM staff_attendance sa
        JOIN staff s ON s.id = sa.staff_id
        LEFT JOIN staff_shifts sh ON sh.id = sa.shift_id
        WHERE 1=1
    """
    params: list[Any] = []
    if date:
        query += " AND sa.date = %s"
        params.append(date)
    if month:
        query += " AND to_char(sa.date, 'YYYY-MM') = %s"
        params.append(month)
    if staff_id:
        query += " AND sa.staff_id = %s"
        params.append(staff_id)

    query += " ORDER BY sa.date DESC, s.name ASC"
    rows = conn.execute(query, tuple(params)).fetchall()
    return [dict(r) for r in rows]


def override_staff_attendance(
    conn,
    approver_user: dict,
    attendance_id: UUID,
    status: str,
    override_reason: str,
    check_in_time: datetime | None = None,
    check_out_time: datetime | None = None,
    deduction: float = 0.0,
    note: str | None = None,
) -> dict[str, Any]:
    """Supervisor/Admin manual correction with Rule M11 anti-self-approval."""
    target = conn.execute("""
        SELECT * FROM staff_attendance WHERE id = %s
    """, (attendance_id,)).fetchone()

    if not target:
        raise ApiError(404, "NOT_FOUND", "سجل الحضور غير موجود")

    # Rule M11: anti-self-approval. The approver may be a manager with no staff record,
    # so no resolve here: staff_id=None simply means the approver cannot be the target.
    approver_staff_id = approver_user.get("staff_id")
    if approver_staff_id is not None and UUID(str(approver_staff_id)) == target["staff_id"]:
        raise ApiError(
            403,
            "ANTI_SELF_APPROVAL",
            "لا يمكن للمشرفة أو المديرة تعديل أو اعتماد سجل حضورها الشخصي",
        )

    effective_check_in = check_in_time or target["check_in_time"]
    effective_check_out = check_out_time or target["check_out_time"]
    if (
        effective_check_in is not None
        and effective_check_out is not None
        and _as_riyadh(effective_check_out) < _as_riyadh(effective_check_in)
    ):
        raise ApiError(422, "INVALID_TIME_RANGE", "وقت الانصراف لا يمكن أن يسبق وقت الحضور")

    if effective_check_in is not None:
        shift = get_staff_shift_for_date(conn, target["staff_id"], target["date"])
        minutes = compute_attendance_minutes(
            shift,
            target["date"],
            effective_check_in,
            effective_check_out,
            get_geofence_settings(conn)["grace_period_minutes"],
        )
    else:
        minutes = {"late_minutes": 0, "early_leave_minutes": 0, "work_duration_minutes": None}
    late_minutes = minutes["late_minutes"]
    early_leave_minutes = minutes["early_leave_minutes"]
    work_duration_minutes = minutes["work_duration_minutes"] or 0

    now_dt = datetime.now(RIYADH_TZ)
    old_values = {
        "status": target["status"],
        "check_in_time": str(target["check_in_time"]) if target["check_in_time"] else None,
        "check_out_time": str(target["check_out_time"]) if target["check_out_time"] else None,
        "late_minutes": target["late_minutes"],
        "early_leave_minutes": target["early_leave_minutes"],
        "work_duration_minutes": target["work_duration_minutes"],
        "deduction": float(target.get("deduction") or 0.0),
    }

    updated = conn.execute("""
        UPDATE staff_attendance
        SET status = %s,
            is_manual_override = TRUE,
            override_by_user_id = %s,
            override_reason = %s,
            override_at = %s,
            check_in_time = COALESCE(%s, check_in_time),
            check_out_time = COALESCE(%s, check_out_time),
            late_minutes = %s,
            early_leave_minutes = %s,
            work_duration_minutes = %s,
            deduction = %s,
            note = COALESCE(%s, note),
            updated_at = NOW()
        WHERE id = %s
        RETURNING *
    """, (
        status, approver_user["id"], override_reason, now_dt,
        check_in_time, check_out_time,
        late_minutes, early_leave_minutes, work_duration_minutes,
        deduction, note, attendance_id
    )).fetchone()

    write_audit(
        conn,
        actor_user_id=approver_user["id"],
        action="staff_attendance_manual_override",
        entity="staff_attendance",
        entity_id=attendance_id,
        details={
            "old": old_values,
            "new": {
                "status": status,
                "check_in_time": str(effective_check_in) if effective_check_in else None,
                "check_out_time": str(effective_check_out) if effective_check_out else None,
                "late_minutes": late_minutes,
                "early_leave_minutes": early_leave_minutes,
                "work_duration_minutes": work_duration_minutes,
                "reason": override_reason,
                "deduction": deduction,
            },
        },
    )
    return dict(updated)
