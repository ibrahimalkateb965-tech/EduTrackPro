# EduTrack Pro — Phase 2 Spec: REST API server + MVP importer (Gheras Center)

Owner: Autovem Staff Architect (Claude Code). Status: BINDING for all Phase 2 workers. Companion to `PHASE1_SPEC.md` (§0 non-negotiables, §2 data model and §4 API paths still apply verbatim).

## 0. Non-negotiables (additions to Phase 1 §0)
- Package `edutrack_api` under `edutrack_pro/server/`. Python >= 3.12, managed by `uv` (`pyproject.toml`). No ORM: **psycopg 3** (sync) + `psycopg_pool.ConnectionPool`, parameterised SQL only (`%s` / `%(name)s`), never f-string values into SQL. Table/column identifiers may be interpolated ONLY after validation against the introspected column whitelist, using `psycopg.sql.Identifier`.
- FastAPI + Pydantic v2. JWT = PyJWT HS256 (`JWT_SECRET` from env), claims `sub` (user id), `role`, `jti`, `exp`. Passwords = `argon2-cffi` (`PasswordHasher`, argon2id). Never log or return `password_hash`.
- Config from environment only (`config.py`, `.env` loaded by `python-dotenv` if present): `DATABASE_URL`, `JWT_SECRET`, `JWT_TTL_MINUTES` (default 720), `CORS_ORIGINS` (comma list, default empty), `MAIN_BRANCH_ID` (default `00000000-0000-0000-0000-000000000001`).
- Do NOT run tests, `uv sync`, `pytest`, `psql`, docker, or start the server. Claude Code is the sole testing authority. Write code and stop.
- Every JSON error is `{"error": {"code": "<snake_case>", "message": "<Arabic, user-facing>"}}`. Codes: `unauthorized`, `forbidden`, `not_found`, `validation_error`, `conflict`, `internal_error`.
- Money serialises as JSON numbers (Decimal -> float, 2 dp); uuid -> string; date/timestamptz -> ISO-8601 strings. Implemented once in `serializers.py` (`row_to_json(row: dict) -> dict`).
- Soft delete everywhere: `DELETE` sets `deleted_at = now()`; every read filters `deleted_at IS NULL`; `audit_log` is append-only.
- Time zone for "today"/"this month" = `Asia/Riyadh`.

## 1. Folder layout (server/)
```
server/
  pyproject.toml                (A) uv project: fastapi, uvicorn[standard], psycopg[binary,pool], pyjwt, argon2-cffi, pydantic, python-dotenv; dev: pytest, httpx
  .env.example                  (A) the 5 env vars above with dummy values
  docker-compose.yml            (A) postgres:16-alpine service `db` (POSTGRES_DB=gheras_edutrack) + `api` service; mounts ../db/postgres/ into /docker-entrypoint-initdb.d (files run in name order)
  README.md                     (A) run/importer instructions, English
  edutrack_api/__init__.py      (A) `__version__ = "2.0.0"`
  edutrack_api/config.py        (A) Settings dataclass read from env (see section 0), `get_settings()` cached
  edutrack_api/errors.py        (A) ApiError(status, code, message) + FastAPI exception handlers -> section 0 envelope; RequestValidationError -> 422 validation_error; unhandled -> 500 internal_error
  edutrack_api/main.py          (A) create_app(): lifespan opens/closes pool + introspects columns into app.state, registers handlers, CORS, mounts routers under /api/v1, GET /api/v1/health -> {"status":"ok"}
  edutrack_api/db.py            (B) pool factory `make_pool(settings)`, `get_conn(request)` FastAPI dependency (yields psycopg connection with dict_row, commits on success, rolls back on error), `introspect_columns(conn) -> dict[str, dict[str, ColumnInfo]]` reading information_schema (name, data_type, is_nullable, has_default, is_generated)
  edutrack_api/serializers.py   (B) row_to_json, json_default
  edutrack_api/repositories/__init__.py (B) empty
  edutrack_api/repositories/generic.py (B) GenericRepository (section 3)
  edutrack_api/auth.py          (C) hash/verify password, issue/decode JWT, `current_user` dependency, `require_roles(*roles)` dependency, revoked_tokens check
  edutrack_api/audit.py         (C) `write_audit(conn, actor_user_id, action, entity, entity_id, details)`
  edutrack_api/routers/__init__.py (C) empty
  edutrack_api/routers/auth.py  (C) POST auth/login, POST auth/logout, GET me
  edutrack_api/routers/crud.py  (C) builds the 29 generic resources from RESOURCES (section 2) on top of GenericRepository + hooks (section 4)
  edutrack_api/services/__init__.py (C) empty
  edutrack_api/services/finance.py (C) fee-plan -> installments generator, payment allocation, receipt issue, ledger posting, payroll advance deduction, month closure totals
  edutrack_api/services/words.py (C) arabic_amount_words
  edutrack_api/routers/attendance.py (C) POST attendance/students, POST attendance/staff (bulk upsert)
  edutrack_api/routers/reports.py (C) reports/* + audit-log (section 5)
  edutrack_api/routers/print.py (C) print/* (section 6)
  edutrack_api/importer/__init__.py, gheras_simple_v1.py, __main__.py (B) section 7
  tests/                        (Claude Code only)
db/postgres/003_phase2.sql      (B) section 8
```
Worker letters: A = OpenCode `glm-5.3-flash`, B = OpenCode `muse-spark-1.3-contributor`, C = Codex.

## 2. Resource registry (path -> table)
```
students, guardians, staff, users, rooms, schedules, fee-plans->fee_plans, installments, payments, receipts,
expenses, expense-categories->expense_categories, payroll-runs->payroll_runs, staff-advances->staff_advances,
staff-assets->staff_assets, ledger-accounts->ledger_accounts, ledger-entries->ledger_entries, assignments,
submissions, lesson-logs->lesson_logs, study-plans->study_plans, skill-progress->skill_progress, evaluations,
tasks, messages, certificates, dismissals, branches, month-closures->month_closures
```
Role matrix: `manager` = everything. `supervisor` = everything except `users`, `branches`, `ledger-*`, `payroll-runs`, `month-closures` (read-only on those). `teacher` / `guardian` = 403 on all web CRUD (mobile scoping is Phase 3; leave one `# TODO Phase 3` comment).

## 3. GenericRepository contract (`repositories/generic.py`)
```python
@dataclass(frozen=True)
class ColumnInfo:
    name: str
    data_type: str
    is_nullable: bool
    has_default: bool
    is_generated: bool

class GenericRepository:
    SYSTEM_COLUMNS = {"id", "created_at", "updated_at", "deleted_at"}
    def __init__(self, conn, table: str, columns: dict[str, ColumnInfo]): ...
    def list(self, *, filters: dict[str, str] | None = None, limit: int = 100, offset: int = 0,
             q: str | None = None, order_by: str = "created_at", descending: bool = True) -> tuple[list[dict], int]
        # (rows, total). filters only on whitelisted column names -> raises ValueError(column) otherwise.
        # q = ILIKE '%q%' OR-ed over the text columns among {name, title, description, username, note, student_name}.
    def get(self, id: uuid.UUID) -> dict | None
    def create(self, data: dict) -> dict            # drops unknown keys, SYSTEM_COLUMNS and generated cols; INSERT ... RETURNING *
    def update(self, id, data: dict) -> dict | None # same whitelist; sets updated_at = now(); RETURNING *
    def soft_delete(self, id) -> bool               # UPDATE ... SET deleted_at = now() WHERE id=%s AND deleted_at IS NULL
```
List response = `{"items": [...], "total": n, "limit": l, "offset": o}`. Query params: `limit` (1-500, default 100), `offset`, `q`, plus any column name as an exact-match filter (e.g. `?student_id=...&status=active`). Unknown column filters -> 422 `validation_error`.
psycopg error mapping (in `errors.py`, used by every router): `CheckViolation` / `NotNullViolation` / `ForeignKeyViolation` -> 422 `validation_error` (message includes the constraint name); `UniqueViolation` -> 409 `conflict`.

## 4. Business hooks (routers/crud.py delegates to services/finance.py)
- `POST fee-plans` -> insert plan, then generate `count` installments: seq_no 1..count, `due_date = start_date + (seq_no-1)*interval_days`, amounts split equally with the remainder on the last one (Decimal, 2 dp). Response includes `installments: [...]`.
- `POST payments` -> insert payment; if `installment_id` given, add to its `paid_amount` and set status (`paid` if paid_amount >= amount, else `partial`); if not given, allocate FIFO across the student's unpaid installments (oldest due_date first) and set `installment_id` to the first touched one; create the `receipts` row (receipt_no from sequence); post `ledger_entries` (`in`, amount, ref_table='payments', ref_id, occurred_on=paid_on) on `account_id` = body.account_id or the default cash account (first `ledger_accounts` where kind='cash' ordered by created_at; create «الصندوق» if none). Response includes `receipt` and `installments_touched`.
- `POST expenses` -> insert; post `ledger_entries` (`out`) on body.account_id or the default cash account. Accepts `category` (Arabic name) instead of `category_id`; resolves/creates the category.
- `POST payroll-runs` -> if there is an open `staff_advances` (settled=false) for the staff, set `advance_deducted = min(remaining, monthly)` and update the advance (remaining/settled); `net` is DB-generated (never sent).
- `POST month-closures` -> compute `totals_json` = `{collected, expenses, payroll, net, students_count}` for the month; `closed_by` = actor, `closed_at` = now(); 409 `conflict` if the month is already closed.
- `POST users` -> body `password` (plain) is hashed into `password_hash`; `permissions: {attendance, daily_evaluation, monthly_evaluation, students, finance}` upserts `user_permissions`. `PATCH users/:id` accepts the same. Responses never include `password_hash`.
- Every successful POST/PATCH/DELETE writes one `audit_log` row (`actor_user_id`, `action` in create/update/delete, `entity` = table, `entity_id`, `details_json` = changed keys only).
- Every write happens inside the request's single transaction (the `get_conn` dependency commits once).

## 5. Reports (routers/reports.py) — exact JSON keys the dashboard reads
- `GET reports/daily?date=YYYY-MM-DD` (default today): `{date, students_count, present_today, absent_today, late_today, collected_today, outstanding_total, expenses_month, absences: [{student_id, name, guardian_phone, room_name, note}]}`
- `GET reports/attendance?from&to&room_id?`: `{from, to, items: [{student_id, student_name, room_name, date, status, note}], summary: {present, absent, late, excused}}`
- `GET reports/finance?from?&to?`: `{from, to, collected, expenses, payroll, net, outstanding_total, outstanding_by_student: [{student_id, student_name, total_planned, total_paid, outstanding}]}` (from `v_student_balance`; `outstanding` = balance).
- `GET reports/monthly?month=YYYY-MM`: `{month, students_count, new_students, attendance: {present, absent, late, excused, rate}, finance: {collected, expenses, payroll, net}, top_absent: [{student_id, student_name, absent_days}]}` and upsert the result into `monthly_reports.payload_json` (one row per month).
- `GET reports/student/:id`: `{student, attendance_summary: {present, absent, late, excused}, evaluations: [...], skill_progress: [...], balance: {total_planned, total_paid, outstanding}, payments: [...]}`
- `GET audit-log?limit&offset&entity?` -> list envelope, newest first, each row joined with `actor_name`.

## 6. Print data (routers/print.py)
Each endpoint returns exactly the Mustache field set documented in `web/print/NOTES.md` for that template (scalars + loops, same key names), plus `template: "<file name without .html>"`. `amount_words` = Arabic number-to-words for SAR via `services/words.py: arabic_amount_words(Decimal) -> str` (pure function, e.g. «خمسمائة ريال فقط لا غير», digits inside the string stay Western). Dates render as `YYYY-MM-DD`. Missing entity -> 404 `not_found`.

## 7. Importer `gheras_simple_v1` (importer/gheras_simple_v1.py) — Day-10 data migration
- CLI: `python -m edutrack_api.importer <backup.json> [--dry-run] [--branch-id ...]` and `python -m edutrack_api.importer --set-admin-password`. Accepts either the raw `localStorage['gheras_simple_v1']` object (`{students:[...], ...}`) or the download wrapper `{"version":"GHERAS-BACKUP-1","createdAt":...,"data":{...}}`.
- Idempotent: every row id = `uuid.uuid5(NAMESPACE_GHERAS, f"{collection}:{legacy_id}")` with `NAMESPACE_GHERAS = uuid.uuid5(uuid.NAMESPACE_DNS, "edutrack.gheras.sa")`; all inserts `ON CONFLICT (id) DO NOTHING`. Whole import = one transaction; `--dry-run` rolls back and prints counts.
- Public API for tests: `import_backup(conn, payload: dict, *, branch_id: uuid.UUID, dry_run: bool = False) -> ImportReport` where `ImportReport.rows: list[CollectionResult(collection, read, inserted, skipped, warnings: list[str])]`.
- Collection -> table mapping and field mapping (MVP keys are camelCase; MVP ids are `Date.now()` strings):
  - `rooms` {id,name,group} -> `rooms(name, group_name)`; unknown group -> 'الصباح'.
  - `staff` {id,name,role,salary,phone?} -> `staff(name, role_title=role or 'موظف', base_salary=salary or 0, phone)`.
  - `users` {id,name,username,password,role,staffId,roomId,active} -> `users(username, password_hash=argon2(password or username), role, staff_id, room_id, is_active=active is not False)`; role map: 'مدير/مديرة'->manager, 'مشرف/مشرفة'->supervisor, 'معلم/معلمة'->teacher, 'ولي أمر'->guardian, else teacher. `permissions[]` {staffId,role,roomId,attendance,dailyEvaluation,monthlyEvaluation,students,finance} -> `user_permissions` for the user with that `staffId`. WARNING listing usernames whose password was defaulted.
  - `students` (keys: id,name,nationalId,birthDate,nationality,hasDifficulties,difficulty,childNotes,fatherName,fatherPhone,motherName,motherPhone,guardianPhone,guardianRelation,pickupType,pickupName,pickupRelation,pickupPhone,previousStudy,previousSchool,previousLevel,educationNotes,group,roomId,roomName,status,fees,booksFee,busFee,totalDue,installments,paid) -> `students` ('نعم'/'لا' strings -> bool; `difficulty`->difficulty_notes; `group`->group_name; roomId -> uuid5('rooms', roomId) if that room was imported else resolve by roomName; status map 'نشط'/missing->active, 'مفصول'->dismissed, 'مؤرشف'->archived). Then one `fee_plans` row per student with totalDue > 0: `total_amount=totalDue, count=max(1, installments), start_date = earliest installment dueDate for the student or the import date, interval_days=30`. Guardians: create `guardians` rows for father/mother when name+phone present, link in `student_guardians` (`is_primary` = the one matching guardianRelation).
  - `installments` {id,studentId,number,amount,paid,dueDate,status} -> `installments(fee_plan_id = the student's plan, seq_no = number or running index per student, due_date, amount, paid_amount=paid or 0, status derived: paid/partial/pending)`.
  - `payments` {id,studentId,amount,method,date,note} -> `payments(student_id, amount, method mapped: 'جهاز نقاط بيع'->'مدى', 'تمارا'->'تابي', 'بطاقة ائتمانية'->'بطاقة', unknown->'كاش' with WARNING, paid_on=date)`; rows with amount <= 0 skipped with WARNING. `receipts` {id,paymentId,no,date} -> `receipts(payment_id, receipt_no=no, issued_on=date)`; after import `setval('receipts_receipt_no_seq', max(receipt_no))`.
  - `expenses` {id,description,category,amount,method,date,vendor,invoiceNo,note} -> `expenses` (category resolved/created by name in `expense_categories`; note = ' | '.join of non-empty note/vendor/invoiceNo; method mapped like payments, NULL if empty).
  - `attendance` {id,studentId,date,status,note,teacherId} -> `student_attendance` (status map 'حضور'/'present'->'حاضر', 'غياب'/'absent'->'غائب', 'تأخر'/'late'->'متأخر', 'استئذان'->'مستأذن'); duplicates on (student_id,date) -> `ON CONFLICT DO NOTHING`.
  - `staffAttendance` -> `staff_attendance`; `staffAdvances` {id,staffId,amount,count,monthly,remaining,paid,date,reason,status} -> `staff_advances` (settled = status=='مسدد' or remaining<=0); `staffAssets` {id,staffId,asset,serial,value,date,note,status} -> `staff_assets`; `staffMovements` {id,staffId,type,amount,date,description,note} -> `staff_movements`.
  - `accounts` {id,name,type|kind,opening|balance} -> `ledger_accounts(kind: 'بنك'/'bank'->bank else cash)`; `accountMoves` {id,accountId,type,amount,date,description,sourceType,sourceId} -> `ledger_entries(entry_type: 'إيداع'/'in'->in, 'سحب'/'out'->out, 'تحويل'->transfer_out, ref_table=sourceType, ref_id=uuid5(sourceType, sourceId) when sourceId present)`.
  - `evaluations` {id,studentId,type,subject,date,value,teacherId} -> `evaluations` (type map: 'يومي'->daily, 'أسبوعي'->weekly, 'شهري'->monthly, else daily); `skillProgress`->skill_progress; `studentPlans`->study_plans; `assignments` (+`studentIds[]`->assignment_students); `lessonLogs`->lesson_logs; `tasks`; `certificates`; `dismissals`; `followups`->student_followups; `collectionFollowups`->collection_followups; `parentMessages`/`communication`->messages; `monthClosures`->month_closures; `payroll`->payroll_runs; `auditLog`->audit_log (actor NULL, details_json={legacy_user, legacy_details}).
  - Unknown collections or rows failing validation are counted and listed in the final summary, never abort the import (except a broken JSON file). Use per-row SAVEPOINTs so one bad row does not poison the transaction.
- Output: a table `collection | read | inserted | skipped | warnings` printed with Western digits, English.

## 8. Migration `db/postgres/003_phase2.sql` (idempotent, one transaction)
1. Extend `chk_payments_method` and `chk_expenses_method` to also allow 'تمارا' and 'جهاز نقاط بيع' (`ALTER TABLE ... DROP CONSTRAINT IF EXISTS ...; ADD CONSTRAINT ...`).
2. `CREATE TABLE IF NOT EXISTS revoked_tokens (jti uuid PRIMARY KEY, expires_at timestamptz NOT NULL)` + index on expires_at.
3. Application role: `DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='gheras_app') THEN CREATE ROLE gheras_app LOGIN PASSWORD 'change_me'; END IF; END $$;` then `GRANT USAGE ON SCHEMA public TO gheras_app`, `GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO gheras_app`, `GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO gheras_app`, `REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM gheras_app`, `GRANT INSERT, SELECT ON audit_log TO gheras_app`, `ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE ON TABLES TO gheras_app`.
4. Seed: default cash account «الصندوق» (kind cash, opening_balance 0) if no `ledger_accounts` row exists.
5. Seed the first manager only if `users` is empty: username `admin`, `password_hash` = the literal placeholder `'$argon2id$REPLACE_ON_FIRST_RUN'` (login must fail until replaced) + a SQL comment telling the operator to run `python -m edutrack_api.importer --set-admin-password`.

## 9. Definition of done per worker
- Every file compiles (`python -m py_compile`), imports resolve, no `TODO` except the Phase 3 marker in section 2, no forbidden words (Phase 1 section 0), no Eastern digits, no secrets.
- Workers write only the files assigned to their letter; they read only the files named in their dispatch prompt.
