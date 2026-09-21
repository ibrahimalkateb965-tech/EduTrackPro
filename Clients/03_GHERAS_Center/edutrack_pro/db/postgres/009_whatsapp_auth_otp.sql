-- EduTrack Pro - WhatsApp OTP Auth & Users Phone (Gheras Center)
-- File   : db/postgres/009_whatsapp_auth_otp.sql
-- Requires: 001 through 008 applied first.
--
-- Properties:
--   * Single transaction (BEGIN/COMMIT).
--   * Idempotent (IF NOT EXISTS).
--   * Adds phone text column to users with index.
--   * Backfills users.phone from staff, guardians, and students.
--   * Creates auth_otps table for two-factor WhatsApp authentication.

BEGIN;

-- 1. Add phone column to users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone text;

-- Index for phone lookups
CREATE INDEX IF NOT EXISTS idx_users_phone ON users (phone) WHERE deleted_at IS NULL;

-- 2. Backfill users.phone from linked entities where null
UPDATE users u
SET phone = s.phone
FROM staff s
WHERE u.staff_id = s.id
  AND u.phone IS NULL
  AND s.phone IS NOT NULL
  AND u.deleted_at IS NULL;

UPDATE users u
SET phone = g.phone
FROM guardians g
WHERE u.guardian_id = g.id
  AND u.phone IS NULL
  AND g.phone IS NOT NULL
  AND u.deleted_at IS NULL;

UPDATE users u
SET phone = COALESCE(st.guardian_phone, st.father_phone, st.mother_phone)
FROM students st
WHERE u.student_id = st.id
  AND u.phone IS NULL
  AND COALESCE(st.guardian_phone, st.father_phone, st.mother_phone) IS NOT NULL
  AND u.deleted_at IS NULL;

-- 3. Create auth_otps table for WhatsApp verification
CREATE TABLE IF NOT EXISTS auth_otps (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    phone text NOT NULL,
    otp_code_hash text NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    is_used boolean NOT NULL DEFAULT false,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Index for active non-expired OTP queries
CREATE INDEX IF NOT EXISTS idx_auth_otps_user ON auth_otps (user_id, expires_at) WHERE is_used = false;

COMMIT;
