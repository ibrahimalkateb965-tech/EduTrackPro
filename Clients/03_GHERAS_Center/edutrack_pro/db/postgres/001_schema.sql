-- ============================================================================
-- EduTrack Pro - PostgreSQL production schema (Gheras Center)
-- File   : db/postgres/001_schema.sql
-- Dialect: PostgreSQL 13+
--
-- Properties:
--   * Single transaction (BEGIN/COMMIT).
--   * Idempotent: safe to re-run (IF NOT EXISTS / OR REPLACE / DO block).
--   * Every table carries: id (uuid PK), branch_id (FK -> branches, default
--     main branch), created_at, updated_at, deleted_at (soft delete).
--   * Money: numeric(12,2) SAR. Dates: date. Timestamps: timestamptz.
--   * Main branch fixed id: 00000000-0000-0000-0000-000000000001
--     (seeded by 002_reference.sql - run it before inserting business data).
--   * Enum-like domains enforced with CHECK constraints.
--   * set_updated_at() trigger on every table except audit_log (append-only).
-- ============================================================================

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ----------------------------------------------------------------------------
-- Shared trigger function: refresh updated_at on every UPDATE.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

-- ----------------------------------------------------------------------------
-- Sequence for sequential receipt numbers (1, 2, 3, ...).
-- ----------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS receipts_receipt_no_seq
    AS integer START WITH 1 INCREMENT BY 1 NO CYCLE;

-- ============================================================================
-- Core reference tables
-- ============================================================================

-- branches: future expansion of the center is modelled as branches only.
CREATE TABLE IF NOT EXISTS branches (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    name text NOT NULL,
    city text,
    is_main boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- rooms: classrooms/groups of the center.
CREATE TABLE IF NOT EXISTS rooms (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    name text NOT NULL,
    group_name text NOT NULL CONSTRAINT chk_rooms_group_name CHECK (group_name IN ('الصباح', 'المساء', 'الإنجليزي', 'القدرات')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- guardians: shared guardian identities keyed by unique phone number.
CREATE TABLE IF NOT EXISTS guardians (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    name text NOT NULL,
    phone text NOT NULL,
    relation text CONSTRAINT chk_guardians_relation CHECK (relation IN ('الأب', 'الأم', 'ولي الأمر', 'شخص آخر')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT uq_guardians_phone UNIQUE (phone)
);

-- students: enrolled children (full MVP registration form).
CREATE TABLE IF NOT EXISTS students (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    name text NOT NULL,
    national_id text,
    birth_date date,
    nationality text,
    has_difficulties boolean NOT NULL DEFAULT false,
    difficulty_notes text,
    child_notes text,
    father_name text,
    father_phone text,
    mother_name text,
    mother_phone text,
    guardian_phone text,
    guardian_relation text CONSTRAINT chk_students_guardian_relation CHECK (guardian_relation IN ('الأب', 'الأم', 'ولي الأمر', 'شخص آخر')),
    pickup_type text,
    pickup_name text,
    pickup_relation text,
    pickup_phone text,
    previous_study boolean NOT NULL DEFAULT false,
    previous_school text,
    previous_level text,
    education_notes text,
    room_id uuid REFERENCES rooms(id) ON DELETE RESTRICT,
    group_name text CONSTRAINT chk_students_group_name CHECK (group_name IN ('الصباح', 'المساء', 'الإنجليزي', 'القدرات')),
    status text NOT NULL DEFAULT 'active' CONSTRAINT chk_students_status CHECK (status IN ('active', 'dismissed', 'archived')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- student_guardians: many-to-many link student <-> guardian.
CREATE TABLE IF NOT EXISTS student_guardians (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id uuid NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    guardian_id uuid NOT NULL REFERENCES guardians(id) ON DELETE RESTRICT,
    is_primary boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT uq_student_guardians UNIQUE (student_id, guardian_id)
);

-- staff: employees of the center.
CREATE TABLE IF NOT EXISTS staff (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    name text NOT NULL,
    role_title text NOT NULL,
    base_salary numeric(12,2) NOT NULL DEFAULT 0 CONSTRAINT chk_staff_base_salary CHECK (base_salary >= 0),
    phone text,
    hire_date date,
    status text NOT NULL DEFAULT 'active' CONSTRAINT chk_staff_status CHECK (status IN ('active', 'on_leave', 'terminated')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- users: login accounts (manager / supervisor / teacher / guardian).
-- password_hash holds an argon2id or bcrypt string - never plaintext.
CREATE TABLE IF NOT EXISTS users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    username text NOT NULL,
    password_hash text NOT NULL,
    role text NOT NULL CONSTRAINT chk_users_role CHECK (role IN ('manager', 'supervisor', 'teacher', 'guardian')),
    staff_id uuid REFERENCES staff(id) ON DELETE RESTRICT,
    guardian_id uuid REFERENCES guardians(id) ON DELETE RESTRICT,
    room_id uuid REFERENCES rooms(id) ON DELETE RESTRICT,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT uq_users_username UNIQUE (username)
);

-- user_permissions: per-user permission flags (one row per user).
CREATE TABLE IF NOT EXISTS user_permissions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    attendance boolean NOT NULL DEFAULT false,
    daily_evaluation boolean NOT NULL DEFAULT false,
    monthly_evaluation boolean NOT NULL DEFAULT false,
    students boolean NOT NULL DEFAULT false,
    finance boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT uq_user_permissions_user UNIQUE (user_id)
);

-- ============================================================================
-- Scheduling, attendance
-- ============================================================================

-- schedules: weekly lesson slots (Sunday..Thursday) per room.
CREATE TABLE IF NOT EXISTS schedules (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE RESTRICT,
    teacher_user_id uuid REFERENCES users(id) ON DELETE RESTRICT,
    day text NOT NULL CONSTRAINT chk_schedules_day CHECK (day IN ('الأحد', 'الاثنين', 'الثلاثاء', 'الأربعاء', 'الخميس')),
    start_time time NOT NULL,
    end_time time NOT NULL,
    subject text NOT NULL CONSTRAINT chk_schedules_subject CHECK (subject IN ('القرآن', 'لغتي', 'الإنجليزي', 'الرياضيات')),
    group_name text CONSTRAINT chk_schedules_group_name CHECK (group_name IN ('الصباح', 'المساء', 'الإنجليزي', 'القدرات')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- student_attendance: one record per student per day.
CREATE TABLE IF NOT EXISTS student_attendance (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id uuid NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    date date NOT NULL,
    status text NOT NULL CONSTRAINT chk_student_attendance_status CHECK (status IN ('حاضر', 'غائب', 'متأخر', 'مستأذن')),
    note text,
    recorded_by_user_id uuid REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT uq_student_attendance_student_date UNIQUE (student_id, date)
);

-- staff_attendance: one record per staff member per day.
CREATE TABLE IF NOT EXISTS staff_attendance (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    staff_id uuid NOT NULL REFERENCES staff(id) ON DELETE RESTRICT,
    date date NOT NULL,
    status text NOT NULL CONSTRAINT chk_staff_attendance_status CHECK (status IN ('حاضر', 'غائب', 'متأخر', 'مستأذن')),
    note text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT uq_staff_attendance_staff_date UNIQUE (staff_id, date)
);

-- ============================================================================
-- Fees and payments
-- ============================================================================

-- fee_plans: a payment plan split into equal installments.
CREATE TABLE IF NOT EXISTS fee_plans (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id uuid NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    total_amount numeric(12,2) NOT NULL CONSTRAINT chk_fee_plans_total CHECK (total_amount >= 0),
    count integer NOT NULL CONSTRAINT chk_fee_plans_count CHECK (count > 0),
    start_date date NOT NULL,
    interval_days integer NOT NULL CONSTRAINT chk_fee_plans_interval CHECK (interval_days IN (7, 14, 30)),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- installments: generated rows of a fee plan.
CREATE TABLE IF NOT EXISTS installments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    fee_plan_id uuid NOT NULL REFERENCES fee_plans(id) ON DELETE RESTRICT,
    seq_no integer NOT NULL CONSTRAINT chk_installments_seq CHECK (seq_no > 0),
    due_date date NOT NULL,
    amount numeric(12,2) NOT NULL CONSTRAINT chk_installments_amount CHECK (amount >= 0),
    paid_amount numeric(12,2) NOT NULL DEFAULT 0 CONSTRAINT chk_installments_paid CHECK (paid_amount >= 0),
    status text NOT NULL DEFAULT 'pending' CONSTRAINT chk_installments_status CHECK (status IN ('pending', 'partial', 'paid')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT uq_installments_plan_seq UNIQUE (fee_plan_id, seq_no)
);

-- payments: money actually received from a guardian.
CREATE TABLE IF NOT EXISTS payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id uuid NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    installment_id uuid REFERENCES installments(id) ON DELETE RESTRICT,
    amount numeric(12,2) NOT NULL CONSTRAINT chk_payments_amount CHECK (amount > 0),
    method text NOT NULL CONSTRAINT chk_payments_method CHECK (method IN ('كاش', 'تحويل بنكي', 'تابي', 'تقسيط المركز', 'مدى', 'Apple Pay', 'بطاقة')),
    paid_on date NOT NULL,
    note text,
    gateway_ref text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- receipts: printed proof of payment (1:1 with payments, sequential number).
CREATE TABLE IF NOT EXISTS receipts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    payment_id uuid NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    receipt_no integer NOT NULL DEFAULT nextval('receipts_receipt_no_seq'),
    issued_on timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT uq_receipts_payment UNIQUE (payment_id),
    CONSTRAINT uq_receipts_no UNIQUE (receipt_no)
);

ALTER SEQUENCE IF EXISTS receipts_receipt_no_seq OWNED BY receipts.receipt_no;

-- ============================================================================
-- Expenses, payroll, staff extras
-- ============================================================================

-- expense_categories: reference list (seeded in 002_reference.sql).
CREATE TABLE IF NOT EXISTS expense_categories (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    name text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT uq_expense_categories_branch_name UNIQUE (branch_id, name)
);

-- ledger_accounts: cash / bank accounts of the center.
CREATE TABLE IF NOT EXISTS ledger_accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    name text NOT NULL,
    kind text NOT NULL CONSTRAINT chk_ledger_accounts_kind CHECK (kind IN ('cash', 'bank')),
    opening_balance numeric(12,2) NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- expenses: money paid out by the center.
CREATE TABLE IF NOT EXISTS expenses (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    description text NOT NULL,
    category_id uuid NOT NULL REFERENCES expense_categories(id) ON DELETE RESTRICT,
    amount numeric(12,2) NOT NULL CONSTRAINT chk_expenses_amount CHECK (amount >= 0),
    paid_on date NOT NULL,
    method text CONSTRAINT chk_expenses_method CHECK (method IN ('كاش', 'تحويل بنكي', 'تابي', 'تقسيط المركز', 'مدى', 'Apple Pay', 'بطاقة')),
    account_id uuid REFERENCES ledger_accounts(id) ON DELETE RESTRICT,
    note text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- payroll_runs: monthly salary computation per staff member.
-- net is a stored generated column: base + allowances + bonus - deductions - advance_deducted.
CREATE TABLE IF NOT EXISTS payroll_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    staff_id uuid NOT NULL REFERENCES staff(id) ON DELETE RESTRICT,
    month text NOT NULL CONSTRAINT chk_payroll_month CHECK (month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    base numeric(12,2) NOT NULL DEFAULT 0 CONSTRAINT chk_payroll_base CHECK (base >= 0),
    allowances numeric(12,2) NOT NULL DEFAULT 0 CONSTRAINT chk_payroll_allowances CHECK (allowances >= 0),
    bonus numeric(12,2) NOT NULL DEFAULT 0 CONSTRAINT chk_payroll_bonus CHECK (bonus >= 0),
    deductions numeric(12,2) NOT NULL DEFAULT 0 CONSTRAINT chk_payroll_deductions CHECK (deductions >= 0),
    advance_deducted numeric(12,2) NOT NULL DEFAULT 0 CONSTRAINT chk_payroll_advance CHECK (advance_deducted >= 0),
    net numeric(12,2) GENERATED ALWAYS AS (base + allowances + bonus - deductions - advance_deducted) STORED,
    note text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT uq_payroll_staff_month UNIQUE (staff_id, month)
);

-- staff_advances: money advanced to staff, later deducted from payroll.
CREATE TABLE IF NOT EXISTS staff_advances (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    staff_id uuid NOT NULL REFERENCES staff(id) ON DELETE RESTRICT,
    amount numeric(12,2) NOT NULL CONSTRAINT chk_staff_advances_amount CHECK (amount > 0),
    date date NOT NULL,
    note text,
    settled boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- staff_assets: assets handed to staff (laptop, key, ...).
CREATE TABLE IF NOT EXISTS staff_assets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    staff_id uuid NOT NULL REFERENCES staff(id) ON DELETE RESTRICT,
    asset_name text NOT NULL,
    date date NOT NULL,
    note text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- staff_movements: internal movement / event log per staff member.
CREATE TABLE IF NOT EXISTS staff_movements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    staff_id uuid NOT NULL REFERENCES staff(id) ON DELETE RESTRICT,
    date date NOT NULL,
    note text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- ============================================================================
-- Ledger
-- ============================================================================

-- ledger_entries: money movement per account. ref_table/ref_id is a
-- polymorphic pointer to the source row (payment, expense, ...) - no FK.
CREATE TABLE IF NOT EXISTS ledger_entries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    account_id uuid NOT NULL REFERENCES ledger_accounts(id) ON DELETE RESTRICT,
    entry_type text NOT NULL CONSTRAINT chk_ledger_entries_type CHECK (entry_type IN ('in', 'out', 'transfer_in', 'transfer_out')),
    amount numeric(12,2) NOT NULL CONSTRAINT chk_ledger_entries_amount CHECK (amount > 0),
    ref_table text,
    ref_id uuid,
    occurred_on date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- reconciliations: expected vs actual balance per account per period.
CREATE TABLE IF NOT EXISTS reconciliations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    account_id uuid NOT NULL REFERENCES ledger_accounts(id) ON DELETE RESTRICT,
    period text NOT NULL CONSTRAINT chk_reconciliations_period CHECK (period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    expected numeric(12,2) NOT NULL,
    actual numeric(12,2) NOT NULL,
    approved_by uuid REFERENCES users(id) ON DELETE RESTRICT,
    approved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- month_closures: frozen month for finance (one closure per month).
CREATE TABLE IF NOT EXISTS month_closures (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    month text NOT NULL CONSTRAINT chk_month_closures_month CHECK (month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    closed_by uuid REFERENCES users(id) ON DELETE RESTRICT,
    closed_at timestamptz,
    totals_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT uq_month_closures_month UNIQUE (month)
);

-- ============================================================================
-- Homework and lessons
-- ============================================================================

-- assignments: homework given by a teacher to selected students.
CREATE TABLE IF NOT EXISTS assignments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    title text NOT NULL,
    subject text CONSTRAINT chk_assignments_subject CHECK (subject IN ('القرآن', 'لغتي', 'الإنجليزي', 'الرياضيات')),
    kind text,
    due_date date NOT NULL,
    teacher_user_id uuid REFERENCES users(id) ON DELETE RESTRICT,
    instructions text,
    page_ref text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- assignment_students: students targeted by an assignment.
CREATE TABLE IF NOT EXISTS assignment_students (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    assignment_id uuid NOT NULL REFERENCES assignments(id) ON DELETE RESTRICT,
    student_id uuid NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- submissions: a student's submission for an assignment.
CREATE TABLE IF NOT EXISTS submissions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    assignment_id uuid NOT NULL REFERENCES assignments(id) ON DELETE RESTRICT,
    student_id uuid NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    submitted_at timestamptz NOT NULL DEFAULT now(),
    status text NOT NULL DEFAULT 'submitted' CONSTRAINT chk_submissions_status CHECK (status IN ('submitted', 'graded', 'returned')),
    grade numeric(5,2) CONSTRAINT chk_submissions_grade CHECK (grade >= 0 AND grade <= 100),
    feedback text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- submission_files: photos/files attached to a submission.
CREATE TABLE IF NOT EXISTS submission_files (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    submission_id uuid NOT NULL REFERENCES submissions(id) ON DELETE RESTRICT,
    storage_key text NOT NULL,
    width integer,
    height integer,
    bytes bigint CONSTRAINT chk_submission_files_bytes CHECK (bytes >= 0),
    sha256 text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- lesson_logs: what actually happened in a scheduled lesson.
CREATE TABLE IF NOT EXISTS lesson_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    schedule_id uuid NOT NULL REFERENCES schedules(id) ON DELETE RESTRICT,
    date date NOT NULL,
    status text NOT NULL CONSTRAINT chk_lesson_logs_status CHECK (status IN ('تمت', 'مؤجلة', 'ملغاة')),
    covered text,
    homework text,
    notes text,
    teacher_user_id uuid REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- ============================================================================
-- Learning tracking
-- ============================================================================

-- study_plans: individual plan per student per subject/skill.
CREATE TABLE IF NOT EXISTS study_plans (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id uuid NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    subject text NOT NULL,
    skill text NOT NULL,
    goal text,
    start_date date NOT NULL,
    end_date date,
    activities text,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- skill_progress: dated level snapshots of a student's skills.
CREATE TABLE IF NOT EXISTS skill_progress (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id uuid NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    subject text NOT NULL,
    skill text NOT NULL,
    level text NOT NULL CONSTRAINT chk_skill_progress_level CHECK (level IN ('متميز', 'متقن', 'جيد', 'يحتاج متابعة', 'يحتاج دعم')),
    date date NOT NULL,
    note text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- evaluations: daily / weekly / monthly scores per subject.
CREATE TABLE IF NOT EXISTS evaluations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id uuid NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    subject text NOT NULL,
    eval_type text NOT NULL CONSTRAINT chk_evaluations_type CHECK (eval_type IN ('daily', 'weekly', 'monthly')),
    date date NOT NULL,
    value numeric(5,2) NOT NULL CONSTRAINT chk_evaluations_value CHECK (value >= 0),
    teacher_user_id uuid REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- ============================================================================
-- Follow-ups, tasks, communication
-- ============================================================================

-- student_followups: educational/administrative follow-up notes per student.
CREATE TABLE IF NOT EXISTS student_followups (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id uuid NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    date date NOT NULL,
    note text,
    by_user_id uuid REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- collection_followups: payment collection attempts per installment.
CREATE TABLE IF NOT EXISTS collection_followups (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    installment_id uuid NOT NULL REFERENCES installments(id) ON DELETE RESTRICT,
    contacted_on date NOT NULL,
    channel text,
    outcome text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- tasks: internal to-do items, optionally tied to a student or assignee.
CREATE TABLE IF NOT EXISTS tasks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    title text NOT NULL,
    student_id uuid REFERENCES students(id) ON DELETE RESTRICT,
    assignee_user_id uuid REFERENCES users(id) ON DELETE RESTRICT,
    priority text NOT NULL DEFAULT 'medium' CONSTRAINT chk_tasks_priority CHECK (priority IN ('high', 'medium', 'low')),
    due_date date,
    description text,
    status text NOT NULL DEFAULT 'open' CONSTRAINT chk_tasks_status CHECK (status IN ('open', 'in_progress', 'done', 'cancelled')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- message_templates: reusable message bodies (seeded in 002_reference.sql).
CREATE TABLE IF NOT EXISTS message_templates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    template_key text NOT NULL,
    title text NOT NULL,
    body text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT uq_message_templates_key UNIQUE (template_key)
);

-- messages: messages sent to guardians about a student.
CREATE TABLE IF NOT EXISTS messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id uuid NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    channel text NOT NULL CONSTRAINT chk_messages_channel CHECK (channel IN ('whatsapp', 'sms', 'app')),
    template_key text,
    body text NOT NULL,
    sent_at timestamptz NOT NULL DEFAULT now(),
    status text NOT NULL DEFAULT 'queued' CONSTRAINT chk_messages_status CHECK (status IN ('queued', 'sent', 'failed')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- ============================================================================
-- Certificates, dismissals, reports
-- ============================================================================

-- certificates: excellence/completion certificates issued to students.
CREATE TABLE IF NOT EXISTS certificates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id uuid NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    title text NOT NULL,
    issued_on date NOT NULL,
    reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- dismissals: daily student pickup log.
CREATE TABLE IF NOT EXISTS dismissals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id uuid NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    date date NOT NULL,
    method text NOT NULL CONSTRAINT chk_dismissals_method CHECK (method IN ('استلام من ولي الأمر', 'استلام من شخص مصرح', 'الباص', 'استلام إداري بإذن')),
    receiver_name text,
    receiver_relation text,
    receiver_phone text,
    receiver_id_no text,
    note text,
    recorded_by_user_id uuid REFERENCES users(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- monthly_reports: generated snapshot of a month (payload kept as jsonb).
CREATE TABLE IF NOT EXISTS monthly_reports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    month text NOT NULL CONSTRAINT chk_monthly_reports_month CHECK (month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    generated_at timestamptz NOT NULL DEFAULT now(),
    payload_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- notifications: in-app notifications per user.
CREATE TABLE IF NOT EXISTS notifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    kind text NOT NULL,
    title text NOT NULL,
    body text,
    read_at timestamptz,
    sent_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- ============================================================================
-- Audit (append-only)
-- ============================================================================

-- audit_log: who did what. UPDATE/DELETE are revoked below; no updated_at
-- trigger is attached because rows must never change after insert.
CREATE TABLE IF NOT EXISTS audit_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    actor_user_id uuid REFERENCES users(id) ON DELETE RESTRICT,
    action text NOT NULL,
    entity text NOT NULL,
    entity_id uuid,
    details_json jsonb,
    at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

REVOKE UPDATE, DELETE, TRUNCATE ON TABLE audit_log FROM PUBLIC;

-- ============================================================================
-- Indexes (every FK + hot query paths: (student_id, date), due_date, paid_on)
-- ============================================================================

CREATE INDEX IF NOT EXISTS uq_branches_single_main ON branches (is_main) WHERE is_main;

CREATE INDEX IF NOT EXISTS idx_rooms_branch_id ON rooms (branch_id);

CREATE INDEX IF NOT EXISTS idx_guardians_branch_id ON guardians (branch_id);

CREATE INDEX IF NOT EXISTS idx_students_branch_id ON students (branch_id);
CREATE INDEX IF NOT EXISTS idx_students_room_id ON students (room_id);
CREATE INDEX IF NOT EXISTS idx_students_status ON students (status);

CREATE INDEX IF NOT EXISTS idx_student_guardians_guardian_id ON student_guardians (guardian_id);
CREATE INDEX IF NOT EXISTS idx_student_guardians_branch_id ON student_guardians (branch_id);

CREATE INDEX IF NOT EXISTS idx_staff_branch_id ON staff (branch_id);

CREATE INDEX IF NOT EXISTS idx_users_branch_id ON users (branch_id);
CREATE INDEX IF NOT EXISTS idx_users_staff_id ON users (staff_id);
CREATE INDEX IF NOT EXISTS idx_users_guardian_id ON users (guardian_id);
CREATE INDEX IF NOT EXISTS idx_users_room_id ON users (room_id);

CREATE INDEX IF NOT EXISTS idx_schedules_branch_id ON schedules (branch_id);
CREATE INDEX IF NOT EXISTS idx_schedules_room_id ON schedules (room_id);
CREATE INDEX IF NOT EXISTS idx_schedules_teacher_user_id ON schedules (teacher_user_id);

CREATE INDEX IF NOT EXISTS idx_student_attendance_branch_id ON student_attendance (branch_id);
CREATE INDEX IF NOT EXISTS idx_student_attendance_date ON student_attendance (date);
CREATE INDEX IF NOT EXISTS idx_student_attendance_recorded_by ON student_attendance (recorded_by_user_id);

CREATE INDEX IF NOT EXISTS idx_staff_attendance_branch_id ON staff_attendance (branch_id);
CREATE INDEX IF NOT EXISTS idx_staff_attendance_date ON staff_attendance (date);

CREATE INDEX IF NOT EXISTS idx_fee_plans_branch_id ON fee_plans (branch_id);
CREATE INDEX IF NOT EXISTS idx_fee_plans_student_id ON fee_plans (student_id);

CREATE INDEX IF NOT EXISTS idx_installments_branch_id ON installments (branch_id);
CREATE INDEX IF NOT EXISTS idx_installments_fee_plan_id ON installments (fee_plan_id);
CREATE INDEX IF NOT EXISTS idx_installments_due_date ON installments (due_date);

CREATE INDEX IF NOT EXISTS idx_payments_branch_id ON payments (branch_id);
CREATE INDEX IF NOT EXISTS idx_payments_student_id ON payments (student_id);
CREATE INDEX IF NOT EXISTS idx_payments_installment_id ON payments (installment_id);
CREATE INDEX IF NOT EXISTS idx_payments_paid_on ON payments (paid_on);

CREATE INDEX IF NOT EXISTS idx_receipts_branch_id ON receipts (branch_id);

CREATE INDEX IF NOT EXISTS idx_ledger_accounts_branch_id ON ledger_accounts (branch_id);

CREATE INDEX IF NOT EXISTS idx_expenses_branch_id ON expenses (branch_id);
CREATE INDEX IF NOT EXISTS idx_expenses_category_id ON expenses (category_id);
CREATE INDEX IF NOT EXISTS idx_expenses_account_id ON expenses (account_id);
CREATE INDEX IF NOT EXISTS idx_expenses_paid_on ON expenses (paid_on);

CREATE INDEX IF NOT EXISTS idx_payroll_runs_branch_id ON payroll_runs (branch_id);

CREATE INDEX IF NOT EXISTS idx_staff_advances_branch_id ON staff_advances (branch_id);
CREATE INDEX IF NOT EXISTS idx_staff_advances_staff_id ON staff_advances (staff_id);

CREATE INDEX IF NOT EXISTS idx_staff_assets_branch_id ON staff_assets (branch_id);
CREATE INDEX IF NOT EXISTS idx_staff_assets_staff_id ON staff_assets (staff_id);

CREATE INDEX IF NOT EXISTS idx_staff_movements_branch_id ON staff_movements (branch_id);
CREATE INDEX IF NOT EXISTS idx_staff_movements_staff_id ON staff_movements (staff_id);

CREATE INDEX IF NOT EXISTS idx_ledger_entries_branch_id ON ledger_entries (branch_id);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_account_id ON ledger_entries (account_id);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_occurred_on ON ledger_entries (occurred_on);

CREATE INDEX IF NOT EXISTS idx_reconciliations_branch_id ON reconciliations (branch_id);
CREATE INDEX IF NOT EXISTS idx_reconciliations_account_id ON reconciliations (account_id);
CREATE INDEX IF NOT EXISTS idx_reconciliations_approved_by ON reconciliations (approved_by);

CREATE INDEX IF NOT EXISTS idx_month_closures_branch_id ON month_closures (branch_id);
CREATE INDEX IF NOT EXISTS idx_month_closures_closed_by ON month_closures (closed_by);

CREATE INDEX IF NOT EXISTS idx_assignments_branch_id ON assignments (branch_id);
CREATE INDEX IF NOT EXISTS idx_assignments_teacher_user_id ON assignments (teacher_user_id);
CREATE INDEX IF NOT EXISTS idx_assignments_due_date ON assignments (due_date);

CREATE INDEX IF NOT EXISTS idx_assignment_students_branch_id ON assignment_students (branch_id);
CREATE INDEX IF NOT EXISTS idx_assignment_students_assignment_id ON assignment_students (assignment_id);
CREATE INDEX IF NOT EXISTS idx_assignment_students_student_id ON assignment_students (student_id);

CREATE INDEX IF NOT EXISTS idx_submissions_branch_id ON submissions (branch_id);
CREATE INDEX IF NOT EXISTS idx_submissions_assignment_id ON submissions (assignment_id);
CREATE INDEX IF NOT EXISTS idx_submissions_student_id ON submissions (student_id);

CREATE INDEX IF NOT EXISTS idx_submission_files_branch_id ON submission_files (branch_id);
CREATE INDEX IF NOT EXISTS idx_submission_files_submission_id ON submission_files (submission_id);

CREATE INDEX IF NOT EXISTS idx_lesson_logs_branch_id ON lesson_logs (branch_id);
CREATE INDEX IF NOT EXISTS idx_lesson_logs_schedule_date ON lesson_logs (schedule_id, date);
CREATE INDEX IF NOT EXISTS idx_lesson_logs_teacher_user_id ON lesson_logs (teacher_user_id);

CREATE INDEX IF NOT EXISTS idx_study_plans_branch_id ON study_plans (branch_id);
CREATE INDEX IF NOT EXISTS idx_study_plans_student_id ON study_plans (student_id);

CREATE INDEX IF NOT EXISTS idx_skill_progress_branch_id ON skill_progress (branch_id);
CREATE INDEX IF NOT EXISTS idx_skill_progress_student_id ON skill_progress (student_id);

CREATE INDEX IF NOT EXISTS idx_evaluations_branch_id ON evaluations (branch_id);
CREATE INDEX IF NOT EXISTS idx_evaluations_student_id ON evaluations (student_id);
CREATE INDEX IF NOT EXISTS idx_evaluations_teacher_user_id ON evaluations (teacher_user_id);

CREATE INDEX IF NOT EXISTS idx_student_followups_branch_id ON student_followups (branch_id);
CREATE INDEX IF NOT EXISTS idx_student_followups_student_date ON student_followups (student_id, date);
CREATE INDEX IF NOT EXISTS idx_student_followups_by_user_id ON student_followups (by_user_id);

CREATE INDEX IF NOT EXISTS idx_collection_followups_branch_id ON collection_followups (branch_id);
CREATE INDEX IF NOT EXISTS idx_collection_followups_installment_id ON collection_followups (installment_id);

CREATE INDEX IF NOT EXISTS idx_tasks_branch_id ON tasks (branch_id);
CREATE INDEX IF NOT EXISTS idx_tasks_student_id ON tasks (student_id);
CREATE INDEX IF NOT EXISTS idx_tasks_assignee_user_id ON tasks (assignee_user_id);
CREATE INDEX IF NOT EXISTS idx_tasks_due_date ON tasks (due_date);

CREATE INDEX IF NOT EXISTS idx_messages_branch_id ON messages (branch_id);
CREATE INDEX IF NOT EXISTS idx_messages_student_id ON messages (student_id);
CREATE INDEX IF NOT EXISTS idx_messages_sent_at ON messages (sent_at);

CREATE INDEX IF NOT EXISTS idx_message_templates_branch_id ON message_templates (branch_id);

CREATE INDEX IF NOT EXISTS idx_certificates_branch_id ON certificates (branch_id);
CREATE INDEX IF NOT EXISTS idx_certificates_student_id ON certificates (student_id);

CREATE INDEX IF NOT EXISTS idx_dismissals_branch_id ON dismissals (branch_id);
CREATE INDEX IF NOT EXISTS idx_dismissals_student_date ON dismissals (student_id, date);
CREATE INDEX IF NOT EXISTS idx_dismissals_recorded_by ON dismissals (recorded_by_user_id);

CREATE INDEX IF NOT EXISTS idx_monthly_reports_branch_id ON monthly_reports (branch_id);

CREATE INDEX IF NOT EXISTS idx_notifications_branch_id ON notifications (branch_id);
CREATE INDEX IF NOT EXISTS idx_notifications_user_id ON notifications (user_id);

CREATE INDEX IF NOT EXISTS idx_audit_log_branch_id ON audit_log (branch_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor_user_id ON audit_log (actor_user_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_entity ON audit_log (entity, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_at ON audit_log (at);

-- ============================================================================
-- updated_at triggers (every table except append-only audit_log)
-- ============================================================================
DO $$
DECLARE
    tab record;
BEGIN
    FOR tab IN
        SELECT c.relname AS table_name
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = current_schema()
          AND c.relkind = 'r'
          AND c.relname <> 'audit_log'
          AND EXISTS (
              SELECT 1 FROM pg_attribute a
              WHERE a.attrelid = c.oid AND a.attname = 'updated_at' AND NOT a.attisdropped
          )
    LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_%s_set_updated_at ON %I', tab.table_name, tab.table_name);
        EXECUTE format('CREATE TRIGGER trg_%s_set_updated_at BEFORE UPDATE ON %I
                        FOR EACH ROW EXECUTE FUNCTION set_updated_at()', tab.table_name, tab.table_name);
    END LOOP;
END
$$;

-- ============================================================================
-- v_student_balance: total planned (fee plans) - total paid (payments)
-- ============================================================================
CREATE OR REPLACE VIEW v_student_balance AS
SELECT
    s.id AS student_id,
    s.name AS student_name,
    COALESCE(fp.total_planned, 0) AS total_planned,
    COALESCE(p.total_paid, 0) AS total_paid,
    COALESCE(fp.total_planned, 0) - COALESCE(p.total_paid, 0) AS balance
FROM students s
LEFT JOIN (
    SELECT student_id, SUM(total_amount) AS total_planned
    FROM fee_plans
    WHERE deleted_at IS NULL
    GROUP BY student_id
) fp ON fp.student_id = s.id
LEFT JOIN (
    SELECT student_id, SUM(amount) AS total_paid
    FROM payments
    WHERE deleted_at IS NULL
    GROUP BY student_id
) p ON p.student_id = s.id
WHERE s.deleted_at IS NULL;

COMMIT;
