BEGIN;

-- 1. تحديث قيد حالات حضور الطلاب بإضافة "غياب بعذر" فقط (P3)
ALTER TABLE student_attendance 
    DROP CONSTRAINT IF EXISTS chk_student_attendance_status;

ALTER TABLE student_attendance 
    ADD CONSTRAINT chk_student_attendance_status 
    CHECK (status IN ('حاضر', 'غائب', 'متأخر', 'مستأذن', 'غياب بعذر'));

-- 2. إضافة عمود المسودة لدعم حفظ مسودة رصد الحضور دون اعتماد
ALTER TABLE student_attendance 
    ADD COLUMN IF NOT EXISTS is_draft BOOLEAN NOT NULL DEFAULT FALSE;

-- 3. إنشاء جدول أعذار الغياب الشامل (بدون attendance_id لمنع الاعتماد الدائري P11)
CREATE TABLE IF NOT EXISTS attendance_excuses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    excuse_date DATE NOT NULL,
    submitted_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    reason_text TEXT NOT NULL,
    attachment_storage_key TEXT NULL,
    status TEXT NOT NULL DEFAULT 'pending' CONSTRAINT chk_attendance_excuses_status CHECK (status IN ('pending', 'approved', 'rejected', 'needs_info')),
    supervisor_notes TEXT NULL,
    reviewed_by_user_id UUID NULL REFERENCES users(id) ON DELETE RESTRICT,
    reviewed_at TIMESTAMPTZ NULL,
    client_request_id TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ NULL
);

-- 4. ربط جدول الحضور بجدول الأعذار (علاقة أحادية الاتجاه لمنع أخطاء الحذف P11)
ALTER TABLE student_attendance 
    ADD COLUMN IF NOT EXISTS excuse_id UUID REFERENCES attendance_excuses(id) ON DELETE SET NULL;

-- 5. فهارس الأداء ومنع التكرار ودعم إعادة المحاولة (P10, n1)
CREATE INDEX IF NOT EXISTS idx_attendance_excuses_student_date ON attendance_excuses(student_id, excuse_date);
CREATE INDEX IF NOT EXISTS idx_attendance_excuses_status ON attendance_excuses(status) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_attendance_excuses_branch_id ON attendance_excuses(branch_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_attendance_excuses_active_day ON attendance_excuses(student_id, excuse_date) WHERE deleted_at IS NULL AND status IN ('pending', 'approved', 'needs_info');
CREATE UNIQUE INDEX IF NOT EXISTS uq_attendance_excuses_client_request_id ON attendance_excuses(submitted_by_user_id, client_request_id) WHERE client_request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_student_attendance_draft_partial ON student_attendance(date) WHERE is_draft = TRUE;

-- 6. توسيع قيد أنواع الإشعارات ليشمل "excuse" مع الحفاظ على صياغة 017 النصية (m6)
ALTER TABLE notifications 
    DROP CONSTRAINT IF EXISTS chk_notifications_target_type;

ALTER TABLE notifications 
    ADD CONSTRAINT chk_notifications_target_type 
    CHECK (target_type IS NULL OR target_type IN ('assignment', 'submission', 'attendance', 'evaluation', 'announcement', 'pickup', 'excuse'));

-- 7. تثبيت مشغل التحديث التلقائي لـ updated_at (P9)
DROP TRIGGER IF EXISTS trg_attendance_excuses_updated_at ON attendance_excuses;
CREATE TRIGGER trg_attendance_excuses_updated_at
    BEFORE UPDATE ON attendance_excuses
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 8. منح الصلاحيات الإنتاجية لمستخدم قاعدة البيانات (P9)
GRANT SELECT, INSERT, UPDATE ON attendance_excuses TO gheras_app;

COMMIT;
