BEGIN;

-- 1. جدول تقارير الطلاب الشهرية (Monthly Student Reports)
CREATE TABLE IF NOT EXISTS monthly_student_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE RESTRICT,
    month TEXT NOT NULL CONSTRAINT chk_monthly_student_reports_month CHECK (month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    teacher_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    status TEXT NOT NULL DEFAULT 'draft' CONSTRAINT chk_monthly_student_reports_status CHECK (
        status IN ('draft', 'pending_approval', 'needs_revision', 'approved', 'published')
    ),
    attendance_stats JSONB NOT NULL DEFAULT '{"total_days": 0, "present": 0, "absent": 0, "excused": 0, "late": 0, "attendance_pct": 0}'::jsonb,
    subject_ratings JSONB NOT NULL DEFAULT '[]'::jsonb,
    strengths TEXT NULL,
    improvements TEXT NULL,
    recommendations TEXT NULL,
    revision_notes TEXT NULL,
    submitted_at TIMESTAMPTZ NULL,
    reviewed_by_user_id UUID NULL REFERENCES users(id) ON DELETE RESTRICT,
    reviewed_at TIMESTAMPTZ NULL,
    published_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ NULL
);

-- 2. الفهارس الفريدة وفهارس الأداء
CREATE UNIQUE INDEX IF NOT EXISTS uq_monthly_student_reports_active 
    ON monthly_student_reports(student_id, month) 
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_monthly_student_reports_room_month 
    ON monthly_student_reports(room_id, month) 
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_monthly_student_reports_status 
    ON monthly_student_reports(status) 
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_monthly_student_reports_teacher 
    ON monthly_student_reports(teacher_user_id) 
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_monthly_student_reports_student 
    ON monthly_student_reports(student_id) 
    WHERE deleted_at IS NULL;

-- 3. تفعيل مشغل تحديث الطابع الزمني updated_at
DROP TRIGGER IF EXISTS trg_monthly_student_reports_updated_at ON monthly_student_reports;
CREATE TRIGGER trg_monthly_student_reports_updated_at
    BEFORE UPDATE ON monthly_student_reports
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 4. توسيع قيد أنواع الإشعارات ليشمل التقرير الشهري
ALTER TABLE notifications 
    DROP CONSTRAINT IF EXISTS chk_notifications_target_type;

ALTER TABLE notifications 
    ADD CONSTRAINT chk_notifications_target_type 
    CHECK (target_type IS NULL OR target_type IN (
        'assignment', 'submission', 'attendance', 'evaluation', 
        'announcement', 'pickup', 'excuse', 'thread_message', 
        'daily_summary', 'monthly_report'
    ));

-- 5. الصلاحيات
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gheras_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON monthly_student_reports TO gheras_app;
    END IF;
END $$;

COMMIT;
