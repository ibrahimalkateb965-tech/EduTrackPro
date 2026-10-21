BEGIN;

-- 1. ترقية جدول assignments: موعد تسليم اختياري، مسودات
ALTER TABLE assignments 
    ALTER COLUMN due_date DROP NOT NULL;

ALTER TABLE assignments 
    ADD COLUMN IF NOT EXISTS is_draft BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. جدول المرفقات المرفوعة مؤقتاً لتتبع الملكية (Staged Uploads)
CREATE TABLE IF NOT EXISTS staged_uploads (
    storage_key TEXT PRIMARY KEY,
    uploaded_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    media_type TEXT NOT NULL CHECK (media_type IN ('audio', 'video', 'image', 'document')),
    file_name TEXT NULL,
    bytes BIGINT CHECK (bytes >= 0 AND bytes <= 26214400),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3. جدول مرفقات الواجبات المتعددة مع تتبع الملكية
CREATE TABLE IF NOT EXISTS assignment_files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    assignment_id UUID NOT NULL REFERENCES assignments(id) ON DELETE RESTRICT,
    uploaded_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    storage_key TEXT NOT NULL,
    media_type TEXT NOT NULL CHECK (media_type IN ('audio', 'video', 'image', 'document')),
    file_name TEXT NULL,
    bytes BIGINT CHECK (bytes >= 0 AND bytes <= 26214400),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ NULL
);

CREATE INDEX IF NOT EXISTS idx_assignment_files_assignment ON assignment_files (assignment_id);
CREATE INDEX IF NOT EXISTS idx_assignment_files_branch ON assignment_files (branch_id);

DROP TRIGGER IF EXISTS trg_assignment_files_set_updated_at ON assignment_files;
CREATE TRIGGER trg_assignment_files_set_updated_at
BEFORE UPDATE ON assignment_files
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 4. ترقية جدول submissions: المحاولات، التقدير الرباعي، وسم التأخير، والمفتاح الفريد
ALTER TABLE submissions 
    ADD COLUMN IF NOT EXISTS attempt_number INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS parent_submission_id UUID NULL REFERENCES submissions(id) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS grade_level TEXT NULL,
    ADD COLUMN IF NOT EXISTS revision_note TEXT NULL,
    ADD COLUMN IF NOT EXISTS teacher_feedback_key TEXT NULL,
    ADD COLUMN IF NOT EXISTS is_late BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS reviewed_by_user_id UUID NULL REFERENCES users(id) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS client_request_id UUID NULL;

-- 5. فحوصات الإجهاض الصارمة لجودة البيانات القديمة بعد وجود الأعمدة
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM submissions 
        WHERE deleted_at IS NULL 
        GROUP BY assignment_id, student_id, attempt_number 
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'M2 migration abort: duplicate submissions found for (assignment_id, student_id, attempt_number). Clean up manually before re-running migration.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM submissions 
        WHERE deleted_at IS NULL AND status = 'graded' AND grade IS NULL AND grade_level IS NULL
    ) THEN
        RAISE EXCEPTION 'M2 migration abort: graded submissions found without grade value. Clean up manually before re-running migration.';
    END IF;
END $$;

-- 6. قيود المفاتيح والحالات النمطية المسماة صراحة
ALTER TABLE submissions DROP CONSTRAINT IF EXISTS chk_submissions_status;
ALTER TABLE submissions ADD CONSTRAINT chk_submissions_status 
    CHECK (status IN ('submitted', 'graded', 'returned', 'revision_requested'));

ALTER TABLE submissions DROP CONSTRAINT IF EXISTS chk_submissions_grade_level;
ALTER TABLE submissions ADD CONSTRAINT chk_submissions_grade_level 
    CHECK (grade_level IS NULL OR grade_level IN ('excellent', 'very_good', 'good', 'needs_improvement'));

ALTER TABLE submissions DROP CONSTRAINT IF EXISTS chk_submissions_attempt_number;
ALTER TABLE submissions ADD CONSTRAINT chk_submissions_attempt_number 
    CHECK (attempt_number >= 1);

ALTER TABLE submissions DROP CONSTRAINT IF EXISTS chk_submissions_parent_id;
ALTER TABLE submissions ADD CONSTRAINT chk_submissions_parent_id 
    CHECK ((attempt_number = 1 AND parent_submission_id IS NULL) OR (attempt_number > 1 AND parent_submission_id IS NOT NULL));

ALTER TABLE submissions DROP CONSTRAINT IF EXISTS chk_submissions_revision_note;
ALTER TABLE submissions ADD CONSTRAINT chk_submissions_revision_note 
    CHECK (status <> 'revision_requested' OR (revision_note IS NOT NULL AND length(btrim(revision_note)) > 0));

ALTER TABLE submissions DROP CONSTRAINT IF EXISTS chk_submissions_graded_has_grade;
ALTER TABLE submissions ADD CONSTRAINT chk_submissions_graded_has_grade 
    CHECK (status <> 'graded' OR (grade_level IS NOT NULL OR grade IS NOT NULL));

CREATE UNIQUE INDEX IF NOT EXISTS uq_submissions_attempt 
ON submissions (assignment_id, student_id, attempt_number) 
WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_submissions_client_req 
ON submissions (client_request_id) 
WHERE client_request_id IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_submissions_parent 
ON submissions (parent_submission_id) 
WHERE parent_submission_id IS NOT NULL;

-- ترحيل الدرجات الرقمية القديمة إلى التقدير الرباعي
UPDATE submissions 
SET grade_level = CASE 
    WHEN grade >= 90 THEN 'excellent'
    WHEN grade >= 80 THEN 'very_good'
    WHEN grade >= 70 THEN 'good'
    ELSE 'needs_improvement'
END
WHERE status = 'graded' AND grade_level IS NULL AND grade IS NOT NULL;

-- 7. ترقية جدول submission_files لدعم وسائط متعددة مع الملكية وسقف 25 ميجابايت
ALTER TABLE submission_files 
    ADD COLUMN IF NOT EXISTS uploaded_by_user_id UUID NULL REFERENCES users(id) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS media_type TEXT NOT NULL DEFAULT 'image' CHECK (media_type IN ('audio', 'video', 'image', 'document')),
    ADD COLUMN IF NOT EXISTS file_name TEXT NULL;

-- 8. جداول تذكيرات الواجبات المفككة مع عمود kind ومانع التكرار (R4-4)
CREATE TABLE IF NOT EXISTS assignment_reminders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    assignment_id UUID NOT NULL REFERENCES assignments(id) ON DELETE RESTRICT,
    sent_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    kind TEXT NOT NULL DEFAULT 'bulk' CHECK (kind IN ('bulk', 'individual')),
    message_text TEXT NOT NULL,
    client_request_id UUID NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_assignment_reminders_client_req 
ON assignment_reminders (client_request_id) 
WHERE client_request_id IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_assignment_reminders_assignment_kind_sent 
ON assignment_reminders (assignment_id, kind, sent_at DESC);

CREATE INDEX IF NOT EXISTS idx_assignment_reminders_branch ON assignment_reminders (branch_id);

CREATE TABLE IF NOT EXISTS assignment_reminder_recipients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reminder_id UUID NOT NULL REFERENCES assignment_reminders(id) ON DELETE RESTRICT,
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_reminder_student UNIQUE (reminder_id, student_id)
);

CREATE INDEX IF NOT EXISTS idx_reminder_recipients_reminder ON assignment_reminder_recipients (reminder_id);
CREATE INDEX IF NOT EXISTS idx_reminder_recipients_student_reminder ON assignment_reminder_recipients (student_id, reminder_id);

-- 9. التحديث المعياري لروابط التعليمات السابقة وربطها في assignment_files (R4-1)
UPDATE assignments 
SET instructions = regexp_replace(instructions, '(https?://[^/"\s]+)?/api/v1/static/uploads/', '/api/v1/me/files/', 'g')
WHERE instructions LIKE '%/static/uploads/%';

-- إدراج ملفات المعلمة السابقة في assignment_files لتجاوز فحص الـ ACL
INSERT INTO assignment_files (branch_id, assignment_id, uploaded_by_user_id, storage_key, media_type, file_name, bytes, created_at)
SELECT a.branch_id, a.id, a.teacher_user_id,
       m[1] AS storage_key,
       CASE WHEN m[1] ~* '\.(mp3|m4a|aac)$' THEN 'audio'
            WHEN m[1] ~* '\.(mp4)$' THEN 'video'
            WHEN m[1] ~* '\.(pdf)$' THEN 'document'
            ELSE 'image' END AS media_type,
       m[1] AS file_name,
       0 AS bytes,
       a.created_at
FROM assignments a,
     LATERAL regexp_matches(a.instructions, '/api/v1/me/files/([^"''\s\)]+)', 'g') AS m
ON CONFLICT DO NOTHING;

-- 10. ترقية قيود المواد الدراسية المعتمدة لمركز غراس القرآني والمدرسي (K7)
ALTER TABLE assignments DROP CONSTRAINT IF EXISTS chk_assignments_subject;
ALTER TABLE assignments ADD CONSTRAINT chk_assignments_subject 
    CHECK (subject IS NULL OR subject IN ('القرآن الكريم', 'القرآن', 'لغتي', 'الرياضيات', 'الإنجليزي', 'قدرات', 'عام'));

-- 11. قيد التفرد لجدول assignment_students لمنع التكرار ودعم ON CONFLICT (K8)
DELETE FROM assignment_students a USING assignment_students b 
WHERE a.id > b.id AND a.assignment_id = b.assignment_id AND a.student_id = b.student_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_assignment_students 
ON assignment_students (assignment_id, student_id);

GRANT SELECT, INSERT, UPDATE ON assignments TO gheras_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON assignment_students TO gheras_app;
GRANT SELECT, INSERT, UPDATE ON staged_uploads TO gheras_app;
GRANT SELECT, INSERT, UPDATE ON assignment_files TO gheras_app;
GRANT SELECT, INSERT, UPDATE ON submissions TO gheras_app;
GRANT SELECT, INSERT, UPDATE ON submission_files TO gheras_app;
GRANT SELECT, INSERT ON assignment_reminders TO gheras_app;
GRANT SELECT, INSERT ON assignment_reminder_recipients TO gheras_app;

COMMIT;
