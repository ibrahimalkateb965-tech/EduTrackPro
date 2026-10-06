-- ============================================================================
-- Migration 012: EduTrack Pro Enterprise Expansion (Gheras Center)
-- 1. Teacher Performance Management & Supervisor KPI Evaluations
-- 2. Bulk Room Allocation & Hourly Room Occupancy Scheduling
-- 3. Dynamic Educational Tracks & Departments
-- 4. Student Profile Photos, Registration Fees & Behavioral Audit Logs
-- ============================================================================

-- 1. Educational Tracks / Departments (Dynamic tracks added by administration)
CREATE TABLE IF NOT EXISTS educational_tracks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    name text NOT NULL,
    code text,
    description text,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT uq_educational_tracks_name UNIQUE (branch_id, name)
);

-- Seed initial default tracks
INSERT INTO educational_tracks (branch_id, name, code, description)
VALUES 
    ('00000000-0000-0000-0000-000000000001', 'حلقات القرآن الكريم', 'QURAN', 'المسار القرآني العام وتحفيظ وتلاوة القرآن الكريم'),
    ('00000000-0000-0000-0000-000000000001', 'قسم لغتي والقراءة', 'LUGHATI', 'تأسيس القراءة والكتابة واللغة العربية'),
    ('00000000-0000-0000-0000-000000000001', 'قسم اللغة الإنجليزية', 'ENGLISH', 'تأسيس ومحادثة اللغة الإنجليزية'),
    ('00000000-0000-0000-0000-000000000001', 'قسم القدرات اللفظية والكمية', 'QUDRAT', 'تدريب وتأهيل اختبارات القدرات العامة'),
    ('00000000-0000-0000-0000-000000000001', 'متابعة المناهج المدرسية', 'CURRICULUM', 'متابعة وتقوية المقررات والواجبات المدرسية')
ON CONFLICT (branch_id, name) DO NOTHING;

-- 2. Alter students table: add track_id, photo_url, registration_fee
ALTER TABLE students 
    ADD COLUMN IF NOT EXISTS track_id uuid REFERENCES educational_tracks(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS photo_url text,
    ADD COLUMN IF NOT EXISTS registration_fee numeric(12,2) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS initial_payment_amount numeric(12,2) DEFAULT 0;

-- 3. Teacher Performance & Supervisor KPI Evaluations
CREATE TABLE IF NOT EXISTS teacher_evaluations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    teacher_id uuid NOT NULL REFERENCES staff(id) ON DELETE RESTRICT,
    supervisor_id uuid NOT NULL REFERENCES staff(id) ON DELETE RESTRICT,
    period_type text NOT NULL DEFAULT 'weekly' CONSTRAINT chk_teacher_eval_period CHECK (period_type IN ('daily', 'weekly', 'monthly')),
    evaluation_date date NOT NULL DEFAULT CURRENT_DATE,
    -- The 4 Core Evaluation Pillars (Scores from 0 to 10)
    interaction_score numeric(4,2) NOT NULL CONSTRAINT chk_interaction_score CHECK (interaction_score >= 0 AND interaction_score <= 10),
    guardian_comm_score numeric(4,2) NOT NULL CONSTRAINT chk_guardian_comm_score CHECK (guardian_comm_score >= 0 AND guardian_comm_score <= 10),
    discipline_score numeric(4,2) NOT NULL CONSTRAINT chk_discipline_score CHECK (discipline_score >= 0 AND discipline_score <= 10),
    curriculum_score numeric(4,2) NOT NULL CONSTRAINT chk_curriculum_score CHECK (curriculum_score >= 0 AND curriculum_score <= 10),
    -- Computed Total Score (Average / 10)
    total_score numeric(4,2) GENERATED ALWAYS AS ((interaction_score + guardian_comm_score + discipline_score + curriculum_score) / 4.0) STORED,
    rating text NOT NULL DEFAULT 'جيد' CONSTRAINT chk_teacher_eval_rating CHECK (rating IN ('ممتاز', 'جيد جداً', 'جيد', 'يحتاج تحسين')),
    supervisor_notes text,
    improvement_plan text,
    teacher_acknowledged boolean NOT NULL DEFAULT false,
    acknowledged_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_teacher_evaluations_teacher_date 
    ON teacher_evaluations(teacher_id, evaluation_date);

-- 4. Student Behavioral & Communication Logs (Audit Trail)
CREATE TABLE IF NOT EXISTS student_behavioral_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id uuid NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    room_id uuid REFERENCES rooms(id) ON DELETE SET NULL,
    author_id uuid NOT NULL REFERENCES staff(id) ON DELETE RESTRICT,
    log_type text NOT NULL CONSTRAINT chk_behavioral_log_type CHECK (log_type IN ('positive', 'negative', 'communication')),
    category text NOT NULL DEFAULT 'سلوكي', -- 'سلوكي', 'أكاديمي', 'تواصل ولي أمر'
    points integer NOT NULL DEFAULT 0,
    note text NOT NULL,
    notified_guardian boolean NOT NULL DEFAULT false,
    logged_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_student_behavioral_logs_student 
    ON student_behavioral_logs(student_id, logged_at DESC);

-- 5. Hourly Room Occupancy Scheduling (Hour-by-hour conflict-free grid)
CREATE TABLE IF NOT EXISTS room_hourly_schedules (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    day text NOT NULL CONSTRAINT chk_room_hourly_day CHECK (day IN ('السبت', 'الأحد', 'الاثنين', 'الثلاثاء', 'الأربعاء', 'الخميس')),
    start_hour integer NOT NULL CONSTRAINT chk_start_hour CHECK (start_hour >= 6 AND start_hour <= 22),
    end_hour integer NOT NULL CONSTRAINT chk_end_hour CHECK (end_hour >= 7 AND end_hour <= 23 AND end_hour > start_hour),
    track_id uuid REFERENCES educational_tracks(id) ON DELETE SET NULL,
    teacher_id uuid REFERENCES staff(id) ON DELETE SET NULL,
    title text NOT NULL,
    is_occupied boolean NOT NULL DEFAULT true,
    max_capacity integer NOT NULL DEFAULT 15,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- Seed standard expense categories for center operations
INSERT INTO expense_categories (branch_id, name)
VALUES 
    ('00000000-0000-0000-0000-000000000001', 'الرواتب والأجور'),
    ('00000000-0000-0000-0000-000000000001', 'الصيانة ومصاريف المنشأة'),
    ('00000000-0000-0000-0000-000000000001', 'السلف والمكافآت والخصومات'),
    ('00000000-0000-0000-0000-000000000001', 'الأنشطة والضيافة والجوائز'),
    ('00000000-0000-0000-0000-000000000001', 'الأدوات والمطبوعات المدرسية')
ON CONFLICT (branch_id, name) DO NOTHING;
