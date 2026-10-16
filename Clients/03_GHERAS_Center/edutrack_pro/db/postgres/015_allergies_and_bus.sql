-- 015_allergies_and_bus.sql
-- Gheras EduTrack Pro - Allergy profile, bus subscription split, receiver name
-- Idempotent migration matching 012/014

ALTER TABLE students ADD COLUMN IF NOT EXISTS has_allergies boolean NOT NULL DEFAULT false;
ALTER TABLE students ADD COLUMN IF NOT EXISTS allergy_notes text;
ALTER TABLE students ADD COLUMN IF NOT EXISTS has_bus_subscription boolean NOT NULL DEFAULT false;
ALTER TABLE students ADD COLUMN IF NOT EXISTS bus_fee numeric(10,2) NOT NULL DEFAULT 0;
ALTER TABLE students ADD COLUMN IF NOT EXISTS bus_paid numeric(10,2) NOT NULL DEFAULT 0;
ALTER TABLE students ADD COLUMN IF NOT EXISTS bus_remaining numeric(10,2) GENERATED ALWAYS AS (bus_fee - bus_paid) STORED;

-- Constraints on students
ALTER TABLE students DROP CONSTRAINT IF EXISTS chk_students_allergy_notes;
ALTER TABLE students ADD CONSTRAINT chk_students_allergy_notes CHECK (has_allergies OR allergy_notes IS NULL);

ALTER TABLE students DROP CONSTRAINT IF EXISTS chk_students_bus_fee;
ALTER TABLE students ADD CONSTRAINT chk_students_bus_fee CHECK (bus_fee >= 0);

ALTER TABLE students DROP CONSTRAINT IF EXISTS chk_students_bus_paid;
ALTER TABLE students ADD CONSTRAINT chk_students_bus_paid CHECK (bus_paid >= 0 AND bus_paid <= bus_fee);

ALTER TABLE students DROP CONSTRAINT IF EXISTS chk_students_bus_flag;
ALTER TABLE students ADD CONSTRAINT chk_students_bus_flag CHECK (has_bus_subscription OR (bus_fee = 0 AND bus_paid = 0));

-- Payments table: bus_amount split column
ALTER TABLE payments ADD COLUMN IF NOT EXISTS bus_amount numeric(12,2) NOT NULL DEFAULT 0;

ALTER TABLE payments DROP CONSTRAINT IF EXISTS chk_payments_bus_amount;
ALTER TABLE payments ADD CONSTRAINT chk_payments_bus_amount CHECK (bus_amount >= 0 AND bus_amount <= amount);

-- Receipts table: issued_by_user_id
ALTER TABLE receipts ADD COLUMN IF NOT EXISTS issued_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL;

-- Redefine v_student_balance to subtract bus_amount from payments total
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
    SELECT student_id, SUM(amount - bus_amount) AS total_paid
    FROM payments
    WHERE deleted_at IS NULL
    GROUP BY student_id
) p ON p.student_id = s.id
WHERE s.deleted_at IS NULL;
