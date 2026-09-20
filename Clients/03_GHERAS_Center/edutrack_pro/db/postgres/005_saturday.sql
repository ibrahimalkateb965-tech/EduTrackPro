-- EduTrack Pro - PostgreSQL Saturday schedule upgrade (Gheras Center)
-- File   : db/postgres/005_saturday.sql
-- Requires: db/postgres/001_schema.sql through 004_phase3.sql applied first.
--
-- Properties:
--   * Single transaction (BEGIN/COMMIT).
--   * Idempotent: safe to re-run (DROP IF EXISTS / ADD CHECK).
--   * Widens schedules day check to include Saturday.

BEGIN;
ALTER TABLE schedules DROP CONSTRAINT IF EXISTS chk_schedules_day;
ALTER TABLE schedules ADD CONSTRAINT chk_schedules_day
    CHECK (day IN ('السبت', 'الأحد', 'الاثنين', 'الثلاثاء', 'الأربعاء', 'الخميس'));
COMMIT;
