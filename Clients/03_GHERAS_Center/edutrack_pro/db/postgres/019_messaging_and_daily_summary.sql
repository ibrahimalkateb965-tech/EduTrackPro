BEGIN;

-- 1. جدول سلاسل المحادثات الثنائية (Threads)
CREATE TABLE IF NOT EXISTS threads (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    guardian_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    staff_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    subject TEXT NOT NULL,
    linked_assignment_id UUID NULL REFERENCES assignments(id) ON DELETE SET NULL,
    linked_summary_id UUID NULL, -- سيتم ربطه بمفتاح خارجي بعد إنشاء daily_summaries
    status TEXT NOT NULL DEFAULT 'open' CONSTRAINT chk_threads_status CHECK (status IN ('open', 'closed', 'archived')),
    last_message_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_message_preview TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ NULL
);

-- 2. جدول رسائل المحادثة (Thread Messages) مع تتبع الحالات الثلاث
CREATE TABLE IF NOT EXISTS thread_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    thread_id UUID NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    sender_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    sender_role TEXT NOT NULL CONSTRAINT chk_thread_messages_sender_role CHECK (sender_role IN ('guardian', 'teacher', 'supervisor', 'manager')),
    body TEXT NOT NULL,
    attachment_storage_key TEXT NULL,
    attachment_type TEXT NULL CONSTRAINT chk_thread_messages_attachment_type CHECK (attachment_type IS NULL OR attachment_type IN ('image', 'audio', 'video', 'pdf', 'file')),
    attachment_name TEXT NULL,
    attachment_size_bytes BIGINT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ NULL,
    read_at TIMESTAMPTZ NULL,
    client_message_id TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ NULL
);

-- 3. جدول الملخص اليومي الصفي للدرس (Daily Summaries)
CREATE TABLE IF NOT EXISTS daily_summaries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE RESTRICT,
    teacher_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    summary_date DATE NOT NULL,
    subject TEXT NOT NULL,
    title TEXT NOT NULL,
    class_summary TEXT NOT NULL,
    homework_summary TEXT NULL,
    attachments JSONB NOT NULL DEFAULT '[]'::jsonb,
    is_published BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ NULL
);

-- 4. ربط سلاسل المحادثات بملخصات الدروس اليومية
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_threads_linked_summary'
    ) THEN
        ALTER TABLE threads 
            ADD CONSTRAINT fk_threads_linked_summary 
            FOREIGN KEY (linked_summary_id) REFERENCES daily_summaries(id) ON DELETE SET NULL;
    END IF;
END $$;

-- 5. جدول الملاحظات الفردية السرية للطلاب في الملخص اليومي (Daily Summary Notes)
CREATE TABLE IF NOT EXISTS daily_summary_notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    summary_id UUID NOT NULL REFERENCES daily_summaries(id) ON DELETE CASCADE,
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    private_note TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ NULL
);

-- 6. الفهارس وقواعد منع التكرار ودعم إعادة المحاولة
CREATE INDEX IF NOT EXISTS idx_threads_student_id ON threads(student_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_threads_guardian_user_id ON threads(guardian_user_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_threads_staff_user_id ON threads(staff_user_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_threads_last_message_at ON threads(last_message_at DESC);

CREATE INDEX IF NOT EXISTS idx_thread_messages_thread_sent ON thread_messages(thread_id, sent_at ASC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_thread_messages_unread ON thread_messages(thread_id, read_at) WHERE read_at IS NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_thread_messages_client_id ON thread_messages(thread_id, sender_user_id, client_message_id) WHERE client_message_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_daily_summaries_room_date_subject ON daily_summaries(room_id, summary_date, subject) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_daily_summaries_room_date ON daily_summaries(room_id, summary_date DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_daily_summaries_published ON daily_summaries(is_published, summary_date DESC) WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_daily_summary_notes_summary_student ON daily_summary_notes(summary_id, student_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_daily_summary_notes_student ON daily_summary_notes(student_id) WHERE deleted_at IS NULL;

-- 7. توسيع قيد أنواع الإشعارات ليشمل الرسائل والملخص اليومي
ALTER TABLE notifications 
    DROP CONSTRAINT IF EXISTS chk_notifications_target_type;

ALTER TABLE notifications 
    ADD CONSTRAINT chk_notifications_target_type 
    CHECK (target_type IS NULL OR target_type IN (
        'assignment', 'submission', 'attendance', 'evaluation', 
        'announcement', 'pickup', 'excuse', 'thread_message', 'daily_summary'
    ));

-- 8. تثبيت مشغلات التحديث التلقائي لـ updated_at
DROP TRIGGER IF EXISTS trg_threads_updated_at ON threads;
CREATE TRIGGER trg_threads_updated_at BEFORE UPDATE ON threads FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_thread_messages_updated_at ON thread_messages;
CREATE TRIGGER trg_thread_messages_updated_at BEFORE UPDATE ON thread_messages FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_daily_summaries_updated_at ON daily_summaries;
CREATE TRIGGER trg_daily_summaries_updated_at BEFORE UPDATE ON daily_summaries FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_daily_summary_notes_updated_at ON daily_summary_notes;
CREATE TRIGGER trg_daily_summary_notes_updated_at BEFORE UPDATE ON daily_summary_notes FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 9. منح الصلاحيات الإنتاجية لمستخدم قاعدة البيانات
GRANT SELECT, INSERT, UPDATE, DELETE ON threads, thread_messages, daily_summaries, daily_summary_notes TO gheras_app;

COMMIT;
