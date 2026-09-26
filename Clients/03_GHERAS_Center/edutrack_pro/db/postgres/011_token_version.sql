-- EduTrack Pro - Token Version Migration for Multi-Device Session Invalidation
-- File   : db/postgres/011_token_version.sql

BEGIN;

ALTER TABLE users ADD COLUMN IF NOT EXISTS token_version integer NOT NULL DEFAULT 1;

COMMIT;
