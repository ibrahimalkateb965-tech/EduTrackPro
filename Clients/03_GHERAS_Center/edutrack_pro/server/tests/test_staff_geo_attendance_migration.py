"""Migration tests for 021_staff_geo_attendance.sql (Milestone M6)."""

import os
from pathlib import Path
import psycopg
import pytest

MIGRATION_PATH = (
    Path(__file__).resolve().parent.parent.parent
    / "db"
    / "postgres"
    / "021_staff_geo_attendance.sql"
)


@pytest.fixture
def db_conn():
    uri = os.environ.get("DATABASE_URL")
    if not uri and os.path.exists("/tmp/m4gate/uri.txt"):
        uri = Path("/tmp/m4gate/uri.txt").read_text().strip()
    if not uri:
        uri = "postgresql://postgres:@localhost:5432/postgres"

    conn = psycopg.connect(uri, autocommit=False)
    yield conn
    conn.rollback()
    conn.close()


def test_migration_021_idempotent(db_conn):
    sql = MIGRATION_PATH.read_text()
    db_conn.execute(sql)
    db_conn.rollback()

    # Re-run to ensure idempotency
    db_conn.execute(sql)
    db_conn.rollback()


def test_staff_attendance_columns_exist(db_conn):
    cols = db_conn.execute("""
        SELECT column_name, data_type
        FROM information_schema.columns
        WHERE table_name = 'staff_attendance'
    """).fetchall()
    col_names = {c[0] for c in cols}

    expected = {
        "check_in_time", "check_in_lat", "check_in_lng", "check_in_accuracy", "check_in_distance",
        "check_out_time", "check_out_lat", "check_out_lng", "check_out_accuracy", "check_out_distance",
        "shift_id", "late_minutes", "early_leave_minutes", "work_duration_minutes",
        "is_manual_override", "override_by_user_id", "override_reason", "override_at"
    }
    missing = expected - col_names
    assert not missing, f"Missing columns in staff_attendance: {missing}"


def test_staff_shifts_table_exists(db_conn):
    table = db_conn.execute("""
        SELECT table_name
        FROM information_schema.tables
        WHERE table_name = 'staff_shifts'
    """).fetchone()
    assert table is not None


def test_staff_shift_assignments_table_exists(db_conn):
    table = db_conn.execute("""
        SELECT table_name
        FROM information_schema.tables
        WHERE table_name = 'staff_shift_assignments'
    """).fetchone()
    assert table is not None


def test_geofence_settings_seeded(db_conn):
    settings = db_conn.execute("""
        SELECT key, value
        FROM system_settings
        WHERE key IN ('geofence_center_lat', 'geofence_center_lng', 'geofence_radius_meters')
    """).fetchall()
    settings_dict = {s[0]: s[1] for s in settings}
    assert "geofence_center_lat" in settings_dict
    assert "geofence_center_lng" in settings_dict
    assert "geofence_radius_meters" in settings_dict
