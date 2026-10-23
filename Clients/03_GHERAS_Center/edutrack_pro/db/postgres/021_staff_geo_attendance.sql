-- EduTrack Pro - PostgreSQL staff geofenced attendance upgrade (Milestone M6)
-- File   : db/postgres/021_staff_geo_attendance.sql
-- Requires: 001 through 020 applied first.

BEGIN;

-- 1. Shifts definitions
CREATE TABLE IF NOT EXISTS staff_shifts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    name VARCHAR(100) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    grace_period_minutes INTEGER NOT NULL DEFAULT 15,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. Staff shift assignments
CREATE TABLE IF NOT EXISTS staff_shift_assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    staff_id UUID NOT NULL REFERENCES staff(id) ON DELETE RESTRICT,
    shift_id UUID NOT NULL REFERENCES staff_shifts(id) ON DELETE CASCADE,
    weekday INTEGER NOT NULL CHECK (weekday BETWEEN 0 AND 6),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_staff_shift_assignment UNIQUE (staff_id, shift_id, weekday)
);

-- 3. Geofence and shift settings in system_settings
INSERT INTO system_settings (key, value, description) VALUES
    ('geofence_center_lat', '24.7136000', 'خط عرض مركز غراس للتحقق الجغرافي'),
    ('geofence_center_lng', '46.6753000', 'خط طول مركز غراس للتحقق الجغرافي'),
    ('geofence_radius_meters', '200', 'نصف قطر النطاق الجغرافي المسموح به بالأمتار'),
    ('geofence_max_accuracy_meters', '50', 'الحد الأقصى لدقة GPS المقبولة بالأمتار'),
    ('geofence_grace_period_minutes', '15', 'فترة السماح قبل احتساب التأخير بالدقائق')
ON CONFLICT (key) DO NOTHING;

-- 4. Alter staff_attendance table for geofenced punches & manual override
ALTER TABLE staff_attendance
    ADD COLUMN IF NOT EXISTS check_in_time TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS check_in_lat NUMERIC(10, 7),
    ADD COLUMN IF NOT EXISTS check_in_lng NUMERIC(10, 7),
    ADD COLUMN IF NOT EXISTS check_in_accuracy NUMERIC(8, 2),
    ADD COLUMN IF NOT EXISTS check_in_distance NUMERIC(8, 2),
    ADD COLUMN IF NOT EXISTS check_out_time TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS check_out_lat NUMERIC(10, 7),
    ADD COLUMN IF NOT EXISTS check_out_lng NUMERIC(10, 7),
    ADD COLUMN IF NOT EXISTS check_out_accuracy NUMERIC(8, 2),
    ADD COLUMN IF NOT EXISTS check_out_distance NUMERIC(8, 2),
    ADD COLUMN IF NOT EXISTS shift_id UUID REFERENCES staff_shifts(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS late_minutes INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS early_leave_minutes INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS work_duration_minutes INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS is_manual_override BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS override_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS override_reason TEXT,
    ADD COLUMN IF NOT EXISTS override_at TIMESTAMPTZ;

-- Drop and recreate status check to allow extended statuses
ALTER TABLE staff_attendance DROP CONSTRAINT IF EXISTS chk_staff_attendance_status;
ALTER TABLE staff_attendance ADD CONSTRAINT chk_staff_attendance_status
    CHECK (status IN ('حاضر', 'غائب', 'متأخر', 'مستأذن', 'انصراف مبكر', 'ناقص', 'present', 'late', 'absent', 'excused', 'early_leave', 'incomplete'));

-- 5. Seed default general shift if not exists
INSERT INTO staff_shifts (id, branch_id, name, start_time, end_time, grace_period_minutes)
VALUES ('00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000001', 'الدوام الصباحي المعتمد', '07:30:00', '14:00:00', 15)
ON CONFLICT (id) DO NOTHING;

-- Grants
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gheras_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON staff_shifts TO gheras_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON staff_shift_assignments TO gheras_app;
        GRANT SELECT, INSERT, UPDATE ON staff_attendance TO gheras_app;
    END IF;
END $$;

COMMIT;
