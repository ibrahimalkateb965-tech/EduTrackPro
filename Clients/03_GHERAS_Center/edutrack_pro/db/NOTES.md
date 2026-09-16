# Milestone 1 Notes — Database & Data Layer (Worker B)

Deliverables: `db/postgres/001_schema.sql`, `db/postgres/002_reference.sql`, `db/sqlite/schema.sql`, Room layer under `mobile/android/app/src/main/java/sa/gheras/edutrack/data/` (15 entities, 15 DAOs, `GherasDatabase.kt`, `Converters.kt`). Spec: `docs/PHASE1_SPEC.md` §2. All files UTF-8, no BOM. No tests/builds were run (per spec §0 — testing authority is elsewhere).

## PostgreSQL (001 / 002)

1. **Main branch identity**: fixed UUID `00000000-0000-0000-0000-000000000001`, seeded in `002_reference.sql` («الفرع الرئيسي», city «حوطة بني تميم», `is_main = true`). Every table's `branch_id` defaults to it, so **002 must run before any business inserts**. `branches.branch_id` is a self-FK (main branch parents itself).
2. **Idempotency**: `IF NOT EXISTS` for tables/sequences/indexes, `CREATE OR REPLACE` for the function and view, `DROP TRIGGER IF EXISTS` + `CREATE TRIGGER` inside a `DO` block, `ON CONFLICT DO NOTHING` for all seeds. Both scripts are wrapped in `BEGIN/COMMIT`.
3. **set_updated_at trigger**: attached to every table **except `audit_log`** (append-only; rows must never change). The `DO` block scans all relations in the current schema — assumes the dedicated `gheras_edutrack` database.
4. **audit_log**: carries the common columns (`branch_id`, `created_at`, `updated_at`, `deleted_at`) for schema uniformity; `REVOKE UPDATE, DELETE, TRUNCATE ... FROM PUBLIC` enforces append-only. Phase 2 should create the app role with `INSERT`/`SELECT` only on this table.
5. **receipts**: `receipt_no` comes from sequence `receipts_receipt_no_seq` (owned by the column); one receipt per payment (`UNIQUE (payment_id)`).
6. **payroll_runs.net**: stored generated column = `base + allowances + bonus - deductions - advance_deducted`; non-negative CHECKs on the inputs; `UNIQUE (staff_id, month)`.
7. **Enums not spelled out in §2** (chosen to match MVP semantics, all documented here):
   - `staff.status` ∈ {`active`, `on_leave`, `terminated`}
   - `staff_attendance.status` reuses the 4 Arabic attendance values {حاضر, غائب, متأخر, مستأذن}
   - `installments.status` ∈ {`pending`, `partial`, `paid`} (default `pending`)
   - `tasks.status` ∈ {`open`, `in_progress`, `done`, `cancelled`} (default `open`); priority default `medium`
   - `messages.status` ∈ {`queued`, `sent`, `failed`} (default `queued`)
   - `expenses.method` reuses the payments method enum (nullable)
   - `students.group_name` / `schedules.group_name` reuse the rooms group enum (nullable, no default)
   - `guardians.relation` reuses the guardian-relation enum (nullable)
8. **No CHECK added** where §2 gives no domain: `students.pickup_type`, `assignments.kind`, `collection_followups.channel/outcome`, `notifications.kind`.
9. **Uniques added where natural**: `guardians.phone`, `users.username`, `student_guardians(student_id, guardian_id)`, `installments(fee_plan_id, seq_no)`, `expense_categories(branch_id, name)`, `month_closures.month`, `message_templates.template_key`, and a partial unique index enforcing a single `is_main` branch. Deliberately **not** unique: `submissions(assignment_id, student_id)` and `lesson_logs(schedule_id, date)` (resubmissions/repeated logs possible) — plain indexes only.
10. **month/period** columns are `text` with regex CHECK `^[0-9]{4}-(0[1-9]|1[0-2])$` (YYYY-MM, Western numerals only).
11. **evaluations.value**: `numeric(5,2)` with `CHECK (value >= 0)` only — MVP scale (0-10 vs 0-100) unknown. `submissions.grade` is capped 0..100 to match the homework rubric total in §5.
12. **ledger_entries.ref_table/ref_id** is a polymorphic pointer (payment/expense/...) — no FK, indexed on `(entity)` analogues elsewhere; `occurred_on` indexed for finance ranges.
13. **v_student_balance** = `SUM(fee_plans.total_amount) − SUM(payments.amount)` per student (soft-deleted rows excluded), plus student name for convenience.
14. **FK actions**: all `ON DELETE RESTRICT` — hard deletes are not a workflow (soft delete via `deleted_at`).
15. **002 seeds**: main branch, the 18 MVP expense categories, and 3 message templates (`absence`, `installment_due`, `new_assignment`) with Mustache-style `{{placeholders}}` matching the print-template convention. No default user is seeded (no secrets in repo; bootstrap user is a Phase 2 runtime concern).

## SQLite offline cache (db/sqlite/schema.sql)

16. Mirrors the PostgreSQL columns for the 15 cached tables; `id TEXT` (server uuid), money `REAL`, booleans `INTEGER 0/1`, dates `TEXT` (ISO `yyyy-MM-dd`), times `TEXT` (`HH:MM`), timestamps `TEXT` (ISO-8601 UTC `yyyy-MM-ddTHH:MM:SSZ`, same shape as `Instant.toString()`).
17. `users.staff_id` is kept as a plain column **without FK** because `staff` is not part of the 15-table cache. All other FKs point inside the cached set. `PRAGMA foreign_keys = ON` is declared at the top.
18. Timestamp defaults use `strftime('%Y-%m-%dT%H:%M:%SZ', 'now')` so locally-created rows match server wire format.
19. `branch_id` columns are retained for row fidelity but **not indexed** — the device cache serves a single center.

## Room layer (data/entity, data/dao, data/db)

20. One file per entity and per DAO; classes named `<Thing>Entity` (`RoomEntity` avoids clashing with `androidx.room.Room`). Package names per spec: `sa.gheras.edutrack.data.entity` / `.dao` / `.db`.
21. Nullability mirrors `db/sqlite/schema.sql` exactly (`created_at`/`updated_at` non-null; `deleted_at` nullable). `@ColumnInfo(defaultValue=...)` reproduces the SQLite defaults so Room's generated DDL matches the reference schema.
22. `Converters.kt` maps `Instant`/`LocalDate`/`LocalTime` ↔ ISO text — **requires core library desugaring (or minSdk 26)** in `mobile/android/app/build.gradle`; that build file belongs to another worker.
23. DAO pattern: `upsert`/`upsertAll` (`OnConflictStrategy.REPLACE` — server rows win by id), `update`, `delete`, `deleteById`, `markDeleted` (soft delete), `getById` (suspend), plus `Flow` observers that filter `deleted_at IS NULL`. `UserEntity.password_hash` is cached as-is (hash only, never plaintext) so the current user can sign in offline.
24. `UserEntity.staffId` has no `@ForeignKey` (staff not cached) but keeps the column; `guardian_id`/`room_id` FKs are declared.
25. `GherasDatabase.DATABASE_NAME = "gheras_edutrack"` per spec §0. Schema version 1, `exportSchema = false`.
26. `AssignmentDao.observeByStudent` joins through `assignment_students`; `SkillProgressDao.observeLatestByStudent` returns the latest row per skill via a correlated `MAX(date)` subquery.

## Open items for other workers

- Phase 2 server: create the app DB role (grant `INSERT`/`SELECT` only on `audit_log`), wire JWT auth, and generate `installments` rows from `fee_plans` (count × interval_days from start_date).
- Web/GLM worker: `v_student_balance` backs `print/student-receipt/:studentId` and the finance reports.
- The `updated_at` value is DB-owned (`now()`); the sync layer must not overwrite it on the server.
