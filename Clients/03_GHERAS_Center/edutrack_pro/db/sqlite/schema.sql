-- ============================================================================
-- EduTrack Pro - SQLite offline cache schema (Gheras Center, mobile)
-- File   : db/sqlite/schema.sql
-- Dialect: SQLite 3.35+
--
-- Purpose:
--   * Offline cache of the subset of tables the mobile app needs.
--   * Mirrors db/postgres/001_schema.sql for the cached tables.
--
-- Conventions:
--   * id TEXT primary key (uuid string from the server).
--   * branch_id kept for row fidelity (single-branch device cache).
--   * Money REAL (SAR). Booleans INTEGER 0/1.
--   * Dates TEXT (ISO 8601 yyyy-MM-dd), times TEXT (HH:MM),
--     timestamps TEXT (ISO 8601 UTC yyyy-MM-ddTHH:MM:SSZ).
--   * Soft delete via deleted_at, same as PostgreSQL.
--   * users.staff_id is kept as a plain column with no FK because the
--     staff table is not part of the offline cache.
-- ============================================================================

PRAGMA foreign_keys = ON;

-- ----------------------------------------------------------------------------
-- rooms
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rooms (
    id TEXT PRIMARY KEY,
    branch_id TEXT,
    name TEXT NOT NULL,
    group_name TEXT NOT NULL CHECK (group_name IN ('الصباح', 'المساء', 'الإنجليزي', 'القدرات')),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    deleted_at TEXT
);

-- ----------------------------------------------------------------------------
-- guardians
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS guardians (
    id TEXT PRIMARY KEY,
    branch_id TEXT,
    name TEXT NOT NULL,
    phone TEXT NOT NULL,
    relation TEXT CHECK (relation IN ('الأب', 'الأم', 'ولي الأمر', 'شخص آخر')),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    deleted_at TEXT,
    CONSTRAINT uq_guardians_phone UNIQUE (phone)
);

-- ----------------------------------------------------------------------------
-- users (login accounts cached for the current device user)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    branch_id TEXT,
    username TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('manager', 'supervisor', 'teacher', 'guardian')),
    staff_id TEXT,
    guardian_id TEXT REFERENCES guardians (id),
    room_id TEXT REFERENCES rooms (id),
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    deleted_at TEXT,
    CONSTRAINT uq_users_username UNIQUE (username)
);

-- ----------------------------------------------------------------------------
-- students
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS students (
    id TEXT PRIMARY KEY,
    branch_id TEXT,
    name TEXT NOT NULL,
    national_id TEXT,
    birth_date TEXT,
    nationality TEXT,
    has_difficulties INTEGER NOT NULL DEFAULT 0,
    difficulty_notes TEXT,
    child_notes TEXT,
    father_name TEXT,
    father_phone TEXT,
    mother_name TEXT,
    mother_phone TEXT,
    guardian_phone TEXT,
    guardian_relation TEXT CHECK (guardian_relation IN ('الأب', 'الأم', 'ولي الأمر', 'شخص آخر')),
    pickup_type TEXT,
    pickup_name TEXT,
    pickup_relation TEXT,
    pickup_phone TEXT,
    previous_study INTEGER NOT NULL DEFAULT 0,
    previous_school TEXT,
    previous_level TEXT,
    education_notes TEXT,
    room_id TEXT REFERENCES rooms (id),
    group_name TEXT CHECK (group_name IN ('الصباح', 'المساء', 'الإنجليزي', 'القدرات')),
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'dismissed', 'archived')),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    deleted_at TEXT
);

-- ----------------------------------------------------------------------------
-- schedules
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS schedules (
    id TEXT PRIMARY KEY,
    branch_id TEXT,
    room_id TEXT NOT NULL REFERENCES rooms (id),
    teacher_user_id TEXT REFERENCES users (id),
    day TEXT NOT NULL CHECK (day IN ('الأحد', 'الاثنين', 'الثلاثاء', 'الأربعاء', 'الخميس')),
    start_time TEXT NOT NULL,
    end_time TEXT NOT NULL,
    subject TEXT NOT NULL CHECK (subject IN ('القرآن', 'لغتي', 'الإنجليزي', 'الرياضيات')),
    group_name TEXT CHECK (group_name IN ('الصباح', 'المساء', 'الإنجليزي', 'القدرات')),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    deleted_at TEXT
);

-- ----------------------------------------------------------------------------
-- student_attendance (one row per student per day)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS student_attendance (
    id TEXT PRIMARY KEY,
    branch_id TEXT,
    student_id TEXT NOT NULL REFERENCES students (id),
    date TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('حاضر', 'غائب', 'متأخر', 'مستأذن')),
    note TEXT,
    recorded_by_user_id TEXT REFERENCES users (id),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    deleted_at TEXT,
    CONSTRAINT uq_student_attendance_student_date UNIQUE (student_id, date)
);

-- ----------------------------------------------------------------------------
-- assignments
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS assignments (
    id TEXT PRIMARY KEY,
    branch_id TEXT,
    title TEXT NOT NULL,
    subject TEXT CHECK (subject IN ('القرآن', 'لغتي', 'الإنجليزي', 'الرياضيات')),
    kind TEXT,
    due_date TEXT NOT NULL,
    teacher_user_id TEXT REFERENCES users (id),
    instructions TEXT,
    page_ref TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    deleted_at TEXT
);

-- ----------------------------------------------------------------------------
-- assignment_students
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS assignment_students (
    id TEXT PRIMARY KEY,
    branch_id TEXT,
    assignment_id TEXT NOT NULL REFERENCES assignments (id),
    student_id TEXT NOT NULL REFERENCES students (id),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    deleted_at TEXT
);

-- ----------------------------------------------------------------------------
-- submissions
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS submissions (
    id TEXT PRIMARY KEY,
    branch_id TEXT,
    assignment_id TEXT NOT NULL REFERENCES assignments (id),
    student_id TEXT NOT NULL REFERENCES students (id),
    submitted_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    status TEXT NOT NULL DEFAULT 'submitted' CHECK (status IN ('submitted', 'graded', 'returned')),
    grade REAL CHECK (grade >= 0 AND grade <= 100),
    feedback TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    deleted_at TEXT
);

-- ----------------------------------------------------------------------------
-- submission_files
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS submission_files (
    id TEXT PRIMARY KEY,
    branch_id TEXT,
    submission_id TEXT NOT NULL REFERENCES submissions (id),
    storage_key TEXT NOT NULL,
    width INTEGER,
    height INTEGER,
    bytes INTEGER,
    sha256 TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    deleted_at TEXT
);

-- ----------------------------------------------------------------------------
-- lesson_logs
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lesson_logs (
    id TEXT PRIMARY KEY,
    branch_id TEXT,
    schedule_id TEXT NOT NULL REFERENCES schedules (id),
    date TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('تمت', 'مؤجلة', 'ملغاة')),
    covered TEXT,
    homework TEXT,
    notes TEXT,
    teacher_user_id TEXT REFERENCES users (id),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    deleted_at TEXT
);

-- ----------------------------------------------------------------------------
-- evaluations
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS evaluations (
    id TEXT PRIMARY KEY,
    branch_id TEXT,
    student_id TEXT NOT NULL REFERENCES students (id),
    subject TEXT NOT NULL,
    eval_type TEXT NOT NULL CHECK (eval_type IN ('daily', 'weekly', 'monthly')),
    date TEXT NOT NULL,
    value REAL NOT NULL CHECK (value >= 0),
    teacher_user_id TEXT REFERENCES users (id),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    deleted_at TEXT
);

-- ----------------------------------------------------------------------------
-- skill_progress
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS skill_progress (
    id TEXT PRIMARY KEY,
    branch_id TEXT,
    student_id TEXT NOT NULL REFERENCES students (id),
    subject TEXT NOT NULL,
    skill TEXT NOT NULL,
    level TEXT NOT NULL CHECK (level IN ('متميز', 'متقن', 'جيد', 'يحتاج متابعة', 'يحتاج دعم')),
    date TEXT NOT NULL,
    note TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    deleted_at TEXT
);

-- ----------------------------------------------------------------------------
-- study_plans
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS study_plans (
    id TEXT PRIMARY KEY,
    branch_id TEXT,
    student_id TEXT NOT NULL REFERENCES students (id),
    subject TEXT NOT NULL,
    skill TEXT NOT NULL,
    goal TEXT,
    start_date TEXT NOT NULL,
    end_date TEXT,
    activities TEXT,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    deleted_at TEXT
);

-- ----------------------------------------------------------------------------
-- notifications
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notifications (
    id TEXT PRIMARY KEY,
    branch_id TEXT,
    user_id TEXT NOT NULL REFERENCES users (id),
    kind TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT,
    read_at TEXT,
    sent_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    deleted_at TEXT
);

-- ============================================================================
-- Indexes (FK columns + hot query paths)
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_students_room_id ON students (room_id);
CREATE INDEX IF NOT EXISTS idx_students_status ON students (status);

CREATE INDEX IF NOT EXISTS idx_users_guardian_id ON users (guardian_id);
CREATE INDEX IF NOT EXISTS idx_users_room_id ON users (room_id);

CREATE INDEX IF NOT EXISTS idx_schedules_room_id ON schedules (room_id);
CREATE INDEX IF NOT EXISTS idx_schedules_teacher_user_id ON schedules (teacher_user_id);

CREATE INDEX IF NOT EXISTS idx_student_attendance_date ON student_attendance (date);
CREATE INDEX IF NOT EXISTS idx_student_attendance_recorded_by ON student_attendance (recorded_by_user_id);

CREATE INDEX IF NOT EXISTS idx_assignments_teacher_user_id ON assignments (teacher_user_id);
CREATE INDEX IF NOT EXISTS idx_assignments_due_date ON assignments (due_date);

CREATE INDEX IF NOT EXISTS idx_assignment_students_assignment_id ON assignment_students (assignment_id);
CREATE INDEX IF NOT EXISTS idx_assignment_students_student_id ON assignment_students (student_id);

CREATE INDEX IF NOT EXISTS idx_submissions_assignment_id ON submissions (assignment_id);
CREATE INDEX IF NOT EXISTS idx_submissions_student_id ON submissions (student_id);

CREATE INDEX IF NOT EXISTS idx_submission_files_submission_id ON submission_files (submission_id);

CREATE INDEX IF NOT EXISTS idx_lesson_logs_schedule_date ON lesson_logs (schedule_id, date);
CREATE INDEX IF NOT EXISTS idx_lesson_logs_teacher_user_id ON lesson_logs (teacher_user_id);

CREATE INDEX IF NOT EXISTS idx_evaluations_student_id ON evaluations (student_id);
CREATE INDEX IF NOT EXISTS idx_evaluations_teacher_user_id ON evaluations (teacher_user_id);

CREATE INDEX IF NOT EXISTS idx_skill_progress_student_id ON skill_progress (student_id);

CREATE INDEX IF NOT EXISTS idx_study_plans_student_id ON study_plans (student_id);

CREATE INDEX IF NOT EXISTS idx_notifications_user_id ON notifications (user_id);
