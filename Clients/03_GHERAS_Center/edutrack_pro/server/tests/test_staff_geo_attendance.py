"""Unit and API tests for Staff Geofenced Attendance (Milestone M6)."""

from datetime import date, datetime, timedelta, timezone
import os
from uuid import uuid4
import psycopg
from psycopg.rows import dict_row
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from edutrack_api.auth import issue_token
from edutrack_api.config import get_settings
from edutrack_api.main import create_app
from edutrack_api.repositories import staff_attendance_repo
from edutrack_api.routers.staff_attendance import AttendanceOverrideIn

RIYADH_TZ = timezone(timedelta(hours=3))


@pytest.fixture
def db_conn():
    uri = os.environ.get("DATABASE_URL")
    if not uri:
        uri = "postgresql://postgres:@localhost:5432/postgres"

    conn = psycopg.connect(uri, autocommit=False, row_factory=dict_row)
    yield conn
    conn.rollback()
    conn.close()


@pytest.fixture
def app_client(db_conn):
    app = create_app()
    with TestClient(app) as client:
        yield client


def test_haversine_distance():
    # Gheras center: 24.7136, 46.6753
    # Same point: distance 0
    d0 = staff_attendance_repo.haversine_distance_meters(24.7136, 46.6753, 24.7136, 46.6753)
    assert round(d0, 1) == 0.0

    # Approx 100 meters away point
    d1 = staff_attendance_repo.haversine_distance_meters(24.7136, 46.6753, 24.7145, 46.6753)
    assert 90.0 < d1 < 110.0

    # Far away point (e.g. 5 km)
    d2 = staff_attendance_repo.haversine_distance_meters(24.7136, 46.6753, 24.7500, 46.6753)
    assert d2 > 4000.0


def test_check_in_and_check_out_flow(db_conn):
    # Setup test staff user
    branch_id = "00000000-0000-0000-0000-000000000001"
    user_id = db_conn.execute("""
        INSERT INTO users (branch_id, username, password_hash, role, is_active)
        VALUES (%s, %s, 'fakehash', 'teacher', true)
        RETURNING id
    """, (branch_id, f"test_teacher_{uuid4().hex[:6]}")).fetchone()["id"]

    staff_id = db_conn.execute("""
        INSERT INTO staff (branch_id, name, role_title, status)
        VALUES (%s, 'معلمة اختبار', 'معلمة', 'active')
        RETURNING id
    """, (branch_id,)).fetchone()["id"]

    db_conn.execute("UPDATE users SET staff_id = %s WHERE id = %s", (staff_id, user_id))

    user = {"id": user_id, "role": "teacher", "branch_id": branch_id, "staff_id": staff_id}

    # 1. Check in inside geofence (24.7136, 46.6753) with high accuracy (10m)
    checkin_rec = staff_attendance_repo.check_in_staff(
        conn=db_conn,
        user=user,
        latitude=24.7136,
        longitude=46.6753,
        accuracy=10.0,
    )
    assert checkin_rec["check_in_time"] is not None
    assert checkin_rec["check_in_distance"] < 10.0
    assert checkin_rec["status"] in ("حاضر", "متأخر")

    # 2. Duplicate check-in should be rejected
    with pytest.raises(Exception) as exc_info:
        staff_attendance_repo.check_in_staff(
            conn=db_conn,
            user=user,
            latitude=24.7136,
            longitude=46.6753,
            accuracy=10.0,
        )
    assert getattr(exc_info.value, "code", "") == "ALREADY_CHECKED_IN" or "ALREADY_CHECKED_IN" in str(exc_info.value)

    # 3. Check out inside geofence
    checkout_rec = staff_attendance_repo.check_out_staff(
        conn=db_conn,
        user=user,
        latitude=24.7136,
        longitude=46.6753,
        accuracy=15.0,
    )
    assert checkout_rec["check_out_time"] is not None
    assert checkout_rec["work_duration_minutes"] >= 0

    # 4. Duplicate check-out rejected
    with pytest.raises(Exception) as exc_out:
        staff_attendance_repo.check_out_staff(
            conn=db_conn,
            user=user,
            latitude=24.7136,
            longitude=46.6753,
            accuracy=15.0,
        )
    assert getattr(exc_out.value, "code", "") == "ALREADY_CHECKED_OUT" or "ALREADY_CHECKED_OUT" in str(exc_out.value)


def test_check_in_outside_geofence_rejected(db_conn):
    branch_id = "00000000-0000-0000-0000-000000000001"
    user_id = db_conn.execute("""
        INSERT INTO users (branch_id, username, password_hash, role, is_active)
        VALUES (%s, %s, 'fakehash', 'teacher', true)
        RETURNING id
    """, (branch_id, f"test_teacher_{uuid4().hex[:6]}")).fetchone()["id"]

    user = {"id": user_id, "role": "teacher", "branch_id": branch_id}

    # Far location (e.g. 25.5000, 47.0000 is ~100km away)
    with pytest.raises(Exception) as exc:
        staff_attendance_repo.check_in_staff(
            conn=db_conn,
            user=user,
            latitude=25.5000,
            longitude=47.0000,
            accuracy=10.0,
        )
    assert getattr(exc.value, "code", "") == "OUTSIDE_GEOFENCE" or "OUTSIDE_GEOFENCE" in str(exc.value)


def test_check_in_poor_accuracy_rejected(db_conn):
    branch_id = "00000000-0000-0000-0000-000000000001"
    user_id = db_conn.execute("""
        INSERT INTO users (branch_id, username, password_hash, role, is_active)
        VALUES (%s, %s, 'fakehash', 'teacher', true)
        RETURNING id
    """, (branch_id, f"test_teacher_{uuid4().hex[:6]}")).fetchone()["id"]

    user = {"id": user_id, "role": "teacher", "branch_id": branch_id}

    # Accuracy 150m (exceeds max 50m)
    with pytest.raises(Exception) as exc:
        staff_attendance_repo.check_in_staff(
            conn=db_conn,
            user=user,
            latitude=24.7136,
            longitude=46.6753,
            accuracy=150.0,
        )
    assert getattr(exc.value, "code", "") == "INACCURATE_GPS" or "INACCURATE_GPS" in str(exc.value)


def test_supervisor_manual_override_and_anti_self_approval(db_conn):
    branch_id = "00000000-0000-0000-0000-000000000001"
    # Create supervisor 1
    sup1_user = db_conn.execute("""
        INSERT INTO users (branch_id, username, password_hash, role, is_active)
        VALUES (%s, %s, 'fakehash', 'supervisor', true)
        RETURNING id
    """, (branch_id, f"sup1_{uuid4().hex[:6]}")).fetchone()["id"]
    sup1_staff = db_conn.execute("""
        INSERT INTO staff (branch_id, name, role_title, status)
        VALUES (%s, 'مشرفة 1', 'مشرفة', 'active')
        RETURNING id
    """, (branch_id,)).fetchone()["id"]
    db_conn.execute("UPDATE users SET staff_id = %s WHERE id = %s", (sup1_staff, sup1_user))

    # Create teacher
    tch_user = db_conn.execute("""
        INSERT INTO users (branch_id, username, password_hash, role, is_active)
        VALUES (%s, %s, 'fakehash', 'teacher', true)
        RETURNING id
    """, (branch_id, f"tch_{uuid4().hex[:6]}")).fetchone()["id"]
    tch_staff = db_conn.execute("""
        INSERT INTO staff (branch_id, name, role_title, status)
        VALUES (%s, 'معلمة عادية', 'معلمة', 'active')
        RETURNING id
    """, (branch_id,)).fetchone()["id"]
    db_conn.execute("UPDATE users SET staff_id = %s WHERE id = %s", (tch_staff, tch_user))

    # Record attendance for teacher
    att_row = db_conn.execute("""
        INSERT INTO staff_attendance (branch_id, staff_id, date, status, check_in_time)
        VALUES (%s, %s, %s, 'غائب', NOW())
        RETURNING id
    """, (branch_id, tch_staff, date.today())).fetchone()

    # Supervisor overrides teacher attendance -> Success
    updated = staff_attendance_repo.override_staff_attendance(
        conn=db_conn,
        approver_user={"id": sup1_user, "staff_id": sup1_staff, "role": "supervisor"},
        attendance_id=att_row["id"],
        status="مستأذن",
        override_reason="عذر مرضي معتمد ومقدم للإدارة",
        deduction=0.0,
    )
    assert updated["status"] == "مستأذن"
    assert updated["is_manual_override"] is True
    assert updated["override_reason"] == "عذر مرضي معتمد ومقدم للإدارة"

    # Record attendance for Supervisor 1
    sup_att = db_conn.execute("""
        INSERT INTO staff_attendance (branch_id, staff_id, date, status, check_in_time)
        VALUES (%s, %s, %s, 'غائب', NOW())
        RETURNING id
    """, (branch_id, sup1_staff, date.today() - timedelta(days=1))).fetchone()

    # Rule M11: Supervisor 1 attempts to self-approve their own record -> Rejection!
    with pytest.raises(Exception) as exc_self:
        staff_attendance_repo.override_staff_attendance(
            conn=db_conn,
            approver_user={"id": sup1_user, "staff_id": sup1_staff, "role": "supervisor"},
            attendance_id=sup_att["id"],
            status="حاضر",
            override_reason="تعديل حضور ذاتي",
        )
    assert getattr(exc_self.value, "code", "") == "ANTI_SELF_APPROVAL" or "ANTI_SELF_APPROVAL" in str(exc_self.value)


def test_unlinked_user_rejected_not_bound(db_conn):
    branch_id = "00000000-0000-0000-0000-000000000001"
    user_id = db_conn.execute("""
        INSERT INTO users (branch_id, username, password_hash, role, is_active)
        VALUES (%s, %s, 'fakehash', 'teacher', true)
        RETURNING id
    """, (branch_id, f"unlinked_{uuid4().hex[:6]}")).fetchone()["id"]

    user = {"id": user_id, "role": "teacher", "branch_id": branch_id, "staff_id": None}
    staff_before = db_conn.execute("SELECT COUNT(*) AS c FROM staff").fetchone()["c"]

    with pytest.raises(Exception) as exc:
        staff_attendance_repo.check_in_staff(
            conn=db_conn,
            user=user,
            latitude=24.7136,
            longitude=46.6753,
            accuracy=10.0,
        )
    assert getattr(exc.value, "code", "") == "STAFF_NOT_LINKED"

    staff_after = db_conn.execute("SELECT COUNT(*) AS c FROM staff").fetchone()["c"]
    assert staff_after == staff_before
    assert db_conn.execute(
        "SELECT staff_id FROM users WHERE id = %s", (user_id,)
    ).fetchone()["staff_id"] is None


def test_status_reads_do_not_write(db_conn):
    branch_id = "00000000-0000-0000-0000-000000000001"
    user_id = db_conn.execute("""
        INSERT INTO users (branch_id, username, password_hash, role, is_active)
        VALUES (%s, %s, 'fakehash', 'teacher', true)
        RETURNING id
    """, (branch_id, f"unlinked_status_{uuid4().hex[:6]}")).fetchone()["id"]

    user = {"id": user_id, "role": "teacher", "branch_id": branch_id, "staff_id": None}
    staff_before = db_conn.execute("SELECT COUNT(*) AS c FROM staff").fetchone()["c"]

    with pytest.raises(Exception) as exc:
        staff_attendance_repo.get_my_today_status(db_conn, user)
    assert getattr(exc.value, "code", "") == "STAFF_NOT_LINKED"

    staff_after = db_conn.execute("SELECT COUNT(*) AS c FROM staff").fetchone()["c"]
    assert staff_after == staff_before


def test_manager_without_staff_can_override(db_conn):
    branch_id = "00000000-0000-0000-0000-000000000001"
    mgr_user_id = db_conn.execute("""
        INSERT INTO users (branch_id, username, password_hash, role, is_active)
        VALUES (%s, %s, 'fakehash', 'manager', true)
        RETURNING id
    """, (branch_id, f"mgr_{uuid4().hex[:6]}")).fetchone()["id"]

    tch_staff = db_conn.execute("""
        INSERT INTO staff (branch_id, name, role_title, status)
        VALUES (%s, 'معلمة تجربة', 'معلمة', 'active')
        RETURNING id
    """, (branch_id,)).fetchone()["id"]

    att_row = db_conn.execute("""
        INSERT INTO staff_attendance (branch_id, staff_id, date, status, check_in_time)
        VALUES (%s, %s, %s, 'غائب', NOW())
        RETURNING id
    """, (branch_id, tch_staff, date.today())).fetchone()

    updated = staff_attendance_repo.override_staff_attendance(
        conn=db_conn,
        approver_user={"id": mgr_user_id, "staff_id": None, "role": "manager"},
        attendance_id=att_row["id"],
        status="مستأذن",
        override_reason="عذر إداري معتمد من المديرة",
    )
    assert updated["status"] == "مستأذن"
    assert updated["is_manual_override"] is True


def test_override_recomputes_minutes(db_conn):
    branch_id = "00000000-0000-0000-0000-000000000001"
    mgr_user_id = db_conn.execute("""
        INSERT INTO users (branch_id, username, password_hash, role, is_active)
        VALUES (%s, %s, 'fakehash', 'manager', true)
        RETURNING id
    """, (branch_id, f"mgr_min_{uuid4().hex[:6]}")).fetchone()["id"]

    tch_staff = db_conn.execute("""
        INSERT INTO staff (branch_id, name, role_title, status)
        VALUES (%s, 'معلمة إعادة الحساب', 'معلمة', 'active')
        RETURNING id
    """, (branch_id,)).fetchone()["id"]

    today = date.today()
    shift = staff_attendance_repo.get_staff_shift_for_date(db_conn, tch_staff, today)
    shift_start_dt = datetime.combine(today, shift["start_time"], tzinfo=RIYADH_TZ)
    shift_end_dt = datetime.combine(today, shift["end_time"], tzinfo=RIYADH_TZ)

    att_row = db_conn.execute("""
        INSERT INTO staff_attendance (branch_id, staff_id, date, status, check_in_time)
        VALUES (%s, %s, %s, 'حاضر', %s)
        RETURNING id
    """, (branch_id, tch_staff, today, shift_start_dt)).fetchone()

    new_check_in = shift_start_dt + timedelta(minutes=40)
    new_check_out = shift_end_dt - timedelta(minutes=60)

    updated = staff_attendance_repo.override_staff_attendance(
        conn=db_conn,
        approver_user={"id": mgr_user_id, "staff_id": None, "role": "manager"},
        attendance_id=att_row["id"],
        status="متأخر",
        override_reason="تصحيح توقيت الحضور والانصراف",
        check_in_time=new_check_in,
        check_out_time=new_check_out,
    )

    assert updated["late_minutes"] == 40
    assert updated["early_leave_minutes"] == 60
    assert updated["work_duration_minutes"] == int((new_check_out - new_check_in).total_seconds() / 60)


def test_override_rejects_inverted_times(db_conn):
    branch_id = "00000000-0000-0000-0000-000000000001"
    mgr_user_id = db_conn.execute("""
        INSERT INTO users (branch_id, username, password_hash, role, is_active)
        VALUES (%s, %s, 'fakehash', 'manager', true)
        RETURNING id
    """, (branch_id, f"mgr_inv_{uuid4().hex[:6]}")).fetchone()["id"]

    tch_staff = db_conn.execute("""
        INSERT INTO staff (branch_id, name, role_title, status)
        VALUES (%s, 'معلمة توقيت معكوس', 'معلمة', 'active')
        RETURNING id
    """, (branch_id,)).fetchone()["id"]

    today = date.today()
    shift = staff_attendance_repo.get_staff_shift_for_date(db_conn, tch_staff, today)
    shift_start_dt = datetime.combine(today, shift["start_time"], tzinfo=RIYADH_TZ)
    shift_end_dt = datetime.combine(today, shift["end_time"], tzinfo=RIYADH_TZ)

    att_row = db_conn.execute("""
        INSERT INTO staff_attendance (branch_id, staff_id, date, status, check_in_time)
        VALUES (%s, %s, %s, 'حاضر', %s)
        RETURNING id
    """, (branch_id, tch_staff, today, shift_start_dt)).fetchone()

    with pytest.raises(Exception) as exc:
        staff_attendance_repo.override_staff_attendance(
            conn=db_conn,
            approver_user={"id": mgr_user_id, "staff_id": None, "role": "manager"},
            attendance_id=att_row["id"],
            status="حاضر",
            override_reason="تصحيح غير صالح الزمن",
            check_in_time=shift_end_dt,
            check_out_time=shift_start_dt,
        )
    assert getattr(exc.value, "code", "") == "INVALID_TIME_RANGE"


def test_override_audit_includes_times(db_conn):
    branch_id = "00000000-0000-0000-0000-000000000001"
    mgr_user_id = db_conn.execute("""
        INSERT INTO users (branch_id, username, password_hash, role, is_active)
        VALUES (%s, %s, 'fakehash', 'manager', true)
        RETURNING id
    """, (branch_id, f"mgr_audit_{uuid4().hex[:6]}")).fetchone()["id"]

    tch_staff = db_conn.execute("""
        INSERT INTO staff (branch_id, name, role_title, status)
        VALUES (%s, 'معلمة تدقيق', 'معلمة', 'active')
        RETURNING id
    """, (branch_id,)).fetchone()["id"]

    att_row = db_conn.execute("""
        INSERT INTO staff_attendance (branch_id, staff_id, date, status, check_in_time)
        VALUES (%s, %s, %s, 'غائب', NOW())
        RETURNING id
    """, (branch_id, tch_staff, date.today())).fetchone()

    updated = staff_attendance_repo.override_staff_attendance(
        conn=db_conn,
        approver_user={"id": mgr_user_id, "staff_id": None, "role": "manager"},
        attendance_id=att_row["id"],
        status="متأخر",
        override_reason="تصحيح موثق بالتوقيتات",
        check_in_time=datetime.combine(date.today(), datetime.now(RIYADH_TZ).time(), tzinfo=RIYADH_TZ),
    )

    audit_row = db_conn.execute("""
        SELECT details_json FROM audit_log
        WHERE action = 'staff_attendance_manual_override' AND entity_id = %s
        ORDER BY at DESC
        LIMIT 1
    """, (updated["id"],)).fetchone()

    details = audit_row["details_json"]
    assert details["old"]["check_in_time"] is not None
    assert details["new"]["check_in_time"] is not None


def test_override_status_literal_rejected():
    with pytest.raises(ValidationError):
        AttendanceOverrideIn(
            attendance_id=uuid4(),
            status="bogus",
            override_reason="حالة غير موجودة في القائمة",
        )
