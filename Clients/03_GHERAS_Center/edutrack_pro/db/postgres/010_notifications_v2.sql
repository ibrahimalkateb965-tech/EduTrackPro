-- EduTrack Pro - Notifications v2 (Gheras Center)
-- File   : db/postgres/010_notifications_v2.sql
-- Requires: 001 through 009 applied first.
--
-- Properties:
--   * Single transaction (BEGIN/COMMIT).
--   * Idempotent (IF NOT EXISTS).
--   * Extends notifications table with routing, sender, and broadcast metadata.
--   * Creates notification_broadcasts table for teacher announcements.
--   * Unique fan-out index guarantees idempotency per user per broadcast.

BEGIN;

-- 1. Extend notifications table
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS target_type    text;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS target_id      uuid;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS priority       text NOT NULL DEFAULT 'normal';
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS action_url     text;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS sender_user_id uuid REFERENCES users(id) ON DELETE RESTRICT;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS broadcast_id   uuid;

-- Idempotent check constraints
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_notifications_target_type') THEN
        ALTER TABLE notifications ADD CONSTRAINT chk_notifications_target_type
            CHECK (target_type IS NULL OR target_type IN ('assignment', 'attendance', 'evaluation', 'announcement'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_notifications_priority') THEN
        ALTER TABLE notifications ADD CONSTRAINT chk_notifications_priority
            CHECK (priority IN ('urgent', 'normal'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_notifications_action_url') THEN
        ALTER TABLE notifications ADD CONSTRAINT chk_notifications_action_url
            CHECK (action_url IS NULL OR action_url LIKE 'gheras://%');
    END IF;
END $$;

-- 2. Create notification_broadcasts table
CREATE TABLE IF NOT EXISTS notification_broadcasts (
    id uuid PRIMARY KEY,
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT DEFAULT '00000000-0000-0000-0000-000000000001',
    sender_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    room_id uuid REFERENCES rooms(id) ON DELETE RESTRICT,
    student_ids uuid[] NOT NULL DEFAULT '{}',
    include_guardians boolean NOT NULL DEFAULT true,
    include_students boolean NOT NULL DEFAULT true,
    title text NOT NULL,
    body text,
    priority text NOT NULL DEFAULT 'normal',
    recipient_count integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

-- 3. Indices
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_broadcast_user
    ON notifications (broadcast_id, user_id) WHERE broadcast_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_notifications_user_live
    ON notifications (user_id, sent_at DESC) WHERE deleted_at IS NULL;

COMMIT;
