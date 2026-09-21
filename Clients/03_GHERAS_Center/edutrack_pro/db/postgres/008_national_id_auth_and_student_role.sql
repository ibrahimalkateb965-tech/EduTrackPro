-- EduTrack Pro - National ID login & student role (Gheras Center)
-- File   : db/postgres/008_national_id_auth_and_student_role.sql
-- Requires: 001 through 007 applied first.
--
-- Properties:
--   * Single transaction (BEGIN/COMMIT).
--   * Idempotent (IF NOT EXISTS / DROP CONSTRAINT IF EXISTS).
--   * Expands users.role CHECK constraint to include 'student'.
--   * Adds national_id and student_id to users for multi-role login.

BEGIN;

-- 1. Add national_id to users (if not exists)
ALTER TABLE users ADD COLUMN IF NOT EXISTS national_id text;

-- 2. Add student_id to users (if not exists)
ALTER TABLE users ADD COLUMN IF NOT EXISTS student_id uuid REFERENCES students(id) ON DELETE RESTRICT;

-- 3. Indexes for fast identity lookups
CREATE INDEX IF NOT EXISTS idx_users_national_id ON users (national_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_users_student_id ON users (student_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_national_id_role ON users (national_id, role) WHERE national_id IS NOT NULL AND deleted_at IS NULL;

-- 4. Expand role check constraint to include 'student'
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_role;
ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (role IN ('manager', 'supervisor', 'teacher', 'guardian', 'student'));

COMMIT;
