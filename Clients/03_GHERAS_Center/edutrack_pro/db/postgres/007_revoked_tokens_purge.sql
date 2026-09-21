-- EduTrack Pro - PostgreSQL revoked-token purge grant (Gheras Center)
-- File   : db/postgres/007_revoked_tokens_purge.sql
-- Requires: 001 through 006 applied first.
--
-- Properties:
--   * Single transaction (BEGIN/COMMIT).
--   * Idempotent: GRANT is a no-op when the privilege already exists.
--   * Phase 5 (c) D4-a: POST /auth/logout now purges expired rows from revoked_tokens
--     (mobile tokens live 30 days, so revoked rows would otherwise accumulate).
--     gheras_app holds SELECT/INSERT/UPDATE only (003); DELETE is granted on this
--     one table and nothing else.

BEGIN;

GRANT DELETE ON revoked_tokens TO gheras_app;

COMMIT;
