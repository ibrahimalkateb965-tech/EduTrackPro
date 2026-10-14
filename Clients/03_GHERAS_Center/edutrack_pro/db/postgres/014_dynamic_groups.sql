-- EduTrack Pro - PostgreSQL migration: relax static group_name check constraints
-- File   : db/postgres/014_dynamic_groups.sql
-- Enables dynamic creation of custom study groups across students, rooms, and schedules.

BEGIN;

ALTER TABLE students DROP CONSTRAINT IF EXISTS chk_students_group_name;
ALTER TABLE rooms DROP CONSTRAINT IF EXISTS chk_rooms_group_name;
ALTER TABLE schedules DROP CONSTRAINT IF EXISTS chk_schedules_group_name;

COMMIT;
