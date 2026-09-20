-- EduTrack Pro - PostgreSQL system settings upgrade (Gheras Center)
-- File   : db/postgres/006_settings.sql
-- Requires: 001 through 005 applied first.
--
-- Properties:
--   * Single transaction (BEGIN/COMMIT).
--   * Idempotent: safe to re-run (CREATE IF NOT EXISTS / DO NOTHING / DROP TRIGGER IF EXISTS).
--   * Adds system_settings key/value store plus a partial unique login guard on users.

BEGIN;

-- 1. Key/value system settings (academic year, center identity for printouts).
CREATE TABLE IF NOT EXISTS system_settings (
    key varchar(64) PRIMARY KEY,
    value text NOT NULL,
    description text,
    updated_at timestamptz NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE ON system_settings TO gheras_app;

DROP TRIGGER IF EXISTS trg_system_settings_updated_at ON system_settings;
CREATE TRIGGER trg_system_settings_updated_at BEFORE UPDATE ON system_settings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 2. Defaults. DO NOTHING: never overwrite values the manager already edited.
INSERT INTO system_settings (key, value, description) VALUES
    ('academic_year', '1447-1448 هـ', 'العام الدراسي المطبوع على الكروت والتقارير'),
    ('center_name', 'مركز غراس للرعاية النهارية والتعليم الذكي', 'اسم المركز الرسمي'),
    ('center_phone', '0550000000', 'هاتف المركز'),
    ('center_address', 'حوطة بني تميم', 'عنوان المركز'),
    ('manager_title', 'مدير عام المركز', 'المسمى الوظيفي للمدير في المطبوعات'),
    ('manager_name', 'إدارة المركز', 'اسم المدير في المطبوعات')
ON CONFLICT (key) DO NOTHING;

-- 3. One active login per staff member.
--    Production already holds two active users rows for the same staff_id (2026-09-20 incident);
--    a partial unique index cannot be created over them, so retire every duplicate except the
--    oldest row first. Idempotent: on re-run the subquery finds nothing.
--    3a. The known stray test row ('معلم ،1', staff_id 20cf6983-c352-4f6f-86f5-611f5f725020) is
--        retired by id first, so the real account survives regardless of created_at ordering.
--        No-op where the row does not exist (fresh databases, re-runs).
UPDATE users
   SET deleted_at = now(), is_active = false
 WHERE id = 'c0997630-1d40-43f2-8efa-be8b3c46d322'
   AND deleted_at IS NULL;

UPDATE users u
   SET deleted_at = now(), is_active = false
  FROM (
        SELECT id,
               row_number() OVER (PARTITION BY staff_id ORDER BY created_at, id) AS rn
          FROM users
         WHERE deleted_at IS NULL AND staff_id IS NOT NULL
       ) d
 WHERE u.id = d.id AND d.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_active_staff
    ON users (staff_id)
    WHERE deleted_at IS NULL AND staff_id IS NOT NULL;

COMMIT;
