-- EduTrack Pro - PostgreSQL Phase 2 upgrade (Gheras Center)
-- File   : db/postgres/003_phase2.sql
-- Requires: db/postgres/001_schema.sql and 002_reference.sql applied first.
--
-- Properties:
--   * Single transaction (BEGIN/COMMIT).
--   * Idempotent: safe to re-run (IF EXISTS / IF NOT EXISTS / WHERE NOT EXISTS).
--   * Extends payment method checks, adds revoked_tokens, app role,
--     default cash account and the first manager seed.
--   * Money: numeric(12,2) SAR. Dates: date. Timestamps: timestamptz.

BEGIN;

-- 1. Allow two extra payment methods used by the MVP import.
ALTER TABLE IF EXISTS payments DROP CONSTRAINT IF EXISTS chk_payments_method;
ALTER TABLE IF EXISTS payments ADD CONSTRAINT chk_payments_method CHECK (method IN ('كاش', 'تحويل بنكي', 'تابي', 'تقسيط المركز', 'مدى', 'Apple Pay', 'بطاقة', 'تمارا', 'جهاز نقاط بيع'));

ALTER TABLE IF EXISTS expenses DROP CONSTRAINT IF EXISTS chk_expenses_method;
ALTER TABLE IF EXISTS expenses ADD CONSTRAINT chk_expenses_method CHECK (method IN ('كاش', 'تحويل بنكي', 'تابي', 'تقسيط المركز', 'مدى', 'Apple Pay', 'بطاقة', 'تمارا', 'جهاز نقاط بيع'));

-- 2. Revoked JWT ids for logout.
CREATE TABLE IF NOT EXISTS revoked_tokens (
    jti uuid PRIMARY KEY,
    expires_at timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_revoked_tokens_expires_at ON revoked_tokens (expires_at);

-- 3. Application role with least privilege on append-only audit_log.
DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gheras_app') THEN CREATE ROLE gheras_app LOGIN PASSWORD 'change_me'; END IF; END $$;

GRANT USAGE ON SCHEMA public TO gheras_app;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO gheras_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO gheras_app;
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM gheras_app;
GRANT INSERT, SELECT ON audit_log TO gheras_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE ON TABLES TO gheras_app;

-- 4. Default cash account used when a payment or expense has no account.
INSERT INTO ledger_accounts (branch_id, name, kind, opening_balance)
SELECT '00000000-0000-0000-0000-000000000001', 'الصندوق', 'cash', 0
WHERE NOT EXISTS (SELECT 1 FROM ledger_accounts LIMIT 1);

-- 5. First manager seed. Login stays closed until the hash is set.
-- Operator: run python -m edutrack_api.importer --set-admin-password
INSERT INTO users (branch_id, username, password_hash, role, is_active)
SELECT '00000000-0000-0000-0000-000000000001', 'admin', '$argon2id$REPLACE_ON_FIRST_RUN', 'manager', true
WHERE NOT EXISTS (SELECT 1 FROM users LIMIT 1);

COMMIT;

-- To activate the admin login, set a real hash with:
-- python -m edutrack_api.importer --set-admin-password
-- The command prompts twice, hashes with argon2id, and updates the admin row.
