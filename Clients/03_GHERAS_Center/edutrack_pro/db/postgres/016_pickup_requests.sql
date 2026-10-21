BEGIN;

ALTER TABLE staff_attendance
    ADD COLUMN IF NOT EXISTS deduction NUMERIC(12,2) NOT NULL DEFAULT 0.00 CHECK (deduction >= 0);

CREATE TABLE IF NOT EXISTS pickup_delegates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    created_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    name TEXT NOT NULL,
    national_id TEXT NOT NULL,
    phone TEXT NOT NULL,
    relation TEXT NOT NULL,
    is_approved BOOLEAN NOT NULL DEFAULT TRUE,
    client_request_id UUID NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_pickup_delegates_active
    ON pickup_delegates (student_id, national_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_pickup_delegates_client_id
    ON pickup_delegates (client_request_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_pickup_delegates_branch ON pickup_delegates (branch_id);

DROP TRIGGER IF EXISTS trg_pickup_delegates_set_updated_at ON pickup_delegates;
CREATE TRIGGER trg_pickup_delegates_set_updated_at BEFORE UPDATE ON pickup_delegates
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

GRANT SELECT, INSERT, UPDATE ON pickup_delegates TO gheras_app;

CREATE TABLE IF NOT EXISTS pickup_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    requested_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    delegate_id UUID NULL REFERENCES pickup_delegates(id) ON DELETE RESTRICT,
    is_early_leave BOOLEAN NOT NULL DEFAULT FALSE,
    early_leave_approved BOOLEAN NOT NULL DEFAULT FALSE,
    early_leave_approved_by UUID NULL REFERENCES users(id),
    early_leave_approved_at TIMESTAMPTZ NULL,
    pickup_date DATE NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('notified', 'in_prep', 'ready', 'completed', 'failed', 'cancelled')),
    prep_checklist JSONB NOT NULL DEFAULT '{}'::jsonb,
    teacher_note TEXT NULL,
    failure_reason TEXT NULL,
    cancel_reason TEXT NULL,
    verified_receiver_name TEXT NULL,
    verified_receiver_id_no TEXT NULL,
    verified_receiver_phone TEXT NULL,
    verified_receiver_relation TEXT NULL,
    verified_by_user_id UUID NULL REFERENCES users(id),
    notified_at TIMESTAMPTZ NULL,
    prep_started_at TIMESTAMPTZ NULL,
    ready_at TIMESTAMPTZ NULL,
    completed_at TIMESTAMPTZ NULL,
    cancelled_at TIMESTAMPTZ NULL,
    client_request_id UUID NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_pickup_requests_client_id
    ON pickup_requests (client_request_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_pickup_requests_active_cycle
    ON pickup_requests (student_id, pickup_date)
    WHERE status IN ('notified', 'in_prep', 'ready') AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_pickup_requests_branch ON pickup_requests (branch_id);
CREATE INDEX IF NOT EXISTS idx_pickup_requests_queue
    ON pickup_requests (branch_id, pickup_date, status) WHERE deleted_at IS NULL;

DROP TRIGGER IF EXISTS trg_pickup_requests_set_updated_at ON pickup_requests;
CREATE TRIGGER trg_pickup_requests_set_updated_at BEFORE UPDATE ON pickup_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

GRANT SELECT, INSERT, UPDATE ON pickup_requests TO gheras_app;

ALTER TABLE dismissals ADD COLUMN IF NOT EXISTS pickup_request_id UUID NULL REFERENCES pickup_requests(id);
CREATE INDEX IF NOT EXISTS idx_dismissals_pickup_request_id ON dismissals (pickup_request_id);

INSERT INTO system_settings (key, value, description)
VALUES ('pickup_cutoff_time', '18:30', 'الموعد النهائي لاستلام الأطفال')
ON CONFLICT (key) DO NOTHING;

COMMIT;
