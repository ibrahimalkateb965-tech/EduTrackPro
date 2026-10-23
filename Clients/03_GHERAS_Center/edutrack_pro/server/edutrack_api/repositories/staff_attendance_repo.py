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
    """Find or bind staff_id for user."""
    staff_id = user.get("staff_id")
    if staff_id:
        return UUID(str(staff_id))

    # Fallback: check if user has a staff record with matching branch or email
    st = conn.execute(
        "SELECT id FROM staff WHERE branch_id = %s LIMIT 1",
        (user.get("branch_id") or "00000000-0000-0000-0000-000000000001",),
    ).fetchone()
    if st:
        return st["id"]

    # Create linked staff member if none exists
    new_staff_id = conn.execute("""
        INSERT INTO staff (branch_id, name, role_title, status)
        VALUES (%s, %s, %s, 'active')
        RETURNING id
    """, (
        user.get("branch_id") or "00000000-0000-0000-0000-000000000001",
        user.get("name") or user.get("username") or "موظف",
        user.get("role") or "teacher",
    )).fetchone()["id"]

    conn.execute("UPDATE users SET staff_id = %s WHERE id = %s", (new_staff_id, user["id"]))
    return new_staff_id


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

    # 5. Shift and Lateness calculation
    shift = get_staff_shift_for_date(conn, staff_id, today)
    shift_start = shift["start_time"]
    grace = shift.get("grace_period_minutes", settings["grace_period_minutes"])

    shift_start_dt = datetime.combine(today, shift_start, tzinfo=RIYADH_TZ)
    grace_cutoff_dt = shift_start_dt + timedelta(minutes=grace)

    late_minutes = 0
    if now_dt > grace_cutoff_dt:
        status = "متأخر"
        late_minutes = max(0, int((now_dt - shift_start_dt).total_seconds() / 60))
    else:
        status = "حاضر"

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

    # 5. Shift & Early leave calculation
    shift = get_staff_shift_for_date(conn, staff_id, today)
    shift_end = shift["end_time"]
    shift_end_dt = datetime.combine(today, shift_end, tzinfo=RIYADH_TZ)

    early_leave_minutes = 0
    status = existing["status"]
    if now_dt < shift_end_dt:
        early_leave_minutes = max(0, int((shift_end_dt - now_dt).total_seconds() / 60))
        if status in ("حاضر", "present"):
            status = "انصراف مبكر"

    check_in_time = existing["check_in_time"]
    work_duration = max(0, int((now_dt - check_in_time).total_seconds() / 60))

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
        "shift": shift,
        "geofence": {
            "center_lat": settings["center_lat"],
            "center_lng": settings["center_lng"],
            "radius_meters": settings["radius_meters"],
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

    approver_staff_id = resolve_staff_id_for_user(conn, approver_user)
    target_staff_id = target["staff_id"]

    # Rule M11: anti-self-approval
    if approver_staff_id == target_staff_id:
        raise ApiError(
            403,
            "ANTI_SELF_APPROVAL",
            "لا يمكن للمشرفة أو المديرة تعديل أو اعتماد سجل حضورها الشخصي",
        )

    now_dt = datetime.now(RIYADH_TZ)
    old_values = {
        "status": target["status"],
        "check_in_time": str(target["check_in_time"]) if target["check_in_time"] else None,
        "check_out_time": str(target["check_out_time"]) if target["check_out_time"] else None,
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
            deduction = %s,
            note = COALESCE(%s, note),
            updated_at = NOW()
        WHERE id = %s
        RETURNING *
    """, (
        status, approver_user["id"], override_reason, now_dt,
        check_in_time, check_out_time, deduction, note, attendance_id
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
                "reason": override_reason,
                "deduction": deduction,
            },
        },
    )
    return dict(updated)
