# EduTrack Pro — Phase 5 (b) Specification: Mobile Role Scoping (`teacher` / `guardian`)

- **Owner**: Autovem Master Architect (Claude Code CLI)
- **Client**: Gheras Center (`Clients/03_GHERAS_Center`)
- **Status**: **[IMPLEMENTED — batches 0–3 [APPROVED] 2026-09-20; commits `d136a4b` (0a scope.py), `1be6f01` (0b tests), `9ee3705` (batch 1), `650e171` (batch 2), batch 3 = the commit that carries this line; 100 tests green on embedded PG 16; deploy pending]**
- **Baseline**: `af6afff` on `main` (Phase 4 closed in production, v=3.0, migrations 001–006)
- **Process**: superpowers brainstorming, architectural path. Sections 1–4 approved; self-review done 2026-09-20. Next: Ibrahim reviews this file → `superpowers:writing-plans` → batches per §4.4 (routers to OpenCode Worker B, `scope.py` + tests by Claude Code only).

---

## 0. Decisions already taken (chat, 2026-09-20 ~16:00–17:00)

| # | Question | Decision |
| :--- | :--- | :--- |
| D1 | Phase scope | **Read + teacher's own writes**: scoped GETs for both roles, room-scope the 3 existing teacher routes, one new write (`POST /me/lesson-logs`). |
| D2 | "Teacher's students" | **Home room ∪ scheduled rooms**: `users.room_id` ∪ `schedules.room_id WHERE teacher_user_id = me`. |
| D3 | Guardian fee data | **Yes, read-only**: `installments` + `receipts` for own children. No ledger, no other students. |
| D4 | Architecture | **Approach A**: dedicated `routers/me.py` + `scope.py` dependency; `crud.py` stays manager/supervisor-only and untouched. (B = row-level scoping inside generic CRUD rejected: 30-table allow-list, high leak blast radius. C = reads only rejected: mobile `LessonLogDao` would have no backend.) |

### Findings that motivated the design (code facts, verified)

- Roles exist end-to-end: `users.role ∈ {manager, supervisor, teacher, guardian}` (`001_schema.sql:148`), JWT carries `role`, `auth.current_user` already loads `staff_id`, `guardian_id`, `room_id`. **No schema migration needed.**
- Scoping keys exist: teacher → `users.room_id` + `schedules/assignments/lesson_logs/evaluations.teacher_user_id`; guardian → `users.guardian_id` → `student_guardians` → `students`.
- All `/api/v1/<resource>` CRUD routes are `require_roles("manager","supervisor")` → teacher/guardian get 403 today.
- **Security defect (pre-existing since Phase 2):** `routers/attendance.py` admits `teacher` on three routes with **no row scope** — `POST /attendance/students`, `GET /daily-evaluations`, `POST /evaluations/daily` (also `POST /daily-evaluations` alias). A teacher can read/write attendance and daily evaluations for any student in the center. Fixed by Section 1 guards. **Shipped as hotfix `2a63c50` (B-5.1) together with B-5.2: `POST /attendance/staff` restricted to manager/supervisor. `scope.py` now holds the teacher branch of §1; the guardian branch and `require_scope` land with `routers/me.py`.**
- Dashboard assigns a teacher one room («الحلقة», `web/dashboard/js/views/staff.js:202`) but the schedule editor assigns a teacher per slot (`rooms.js:568`) → a teacher can legitimately cover rooms other than `room_id` (drives D2).
- Mobile Room DAOs already present under `mobile/android/app/src/main/java/sa/gheras/edutrack/data/dao/`: Assignment, AssignmentStudent, Evaluation, Guardian, LessonLog, Notification, Room, Schedule, SkillProgress, StudentAttendance → bounds the endpoint surface.
- Offer (Grand Slam §Package 2): teacher = attendance + homework camera + grades; guardian = child attendance, grades, homework. Mada payments = later package.

---

## 1. Scope resolution — **[APPROVED]**

New module `edutrack_api/scope.py`:

```python
@dataclass(frozen=True)
class Scope:
    role: str                     # "teacher" | "guardian"
    room_ids: frozenset[UUID]     # teacher: home room ∪ scheduled rooms; guardian: ∅
    student_ids: frozenset[UUID]  # guardian: linked children; teacher: students in room_ids (active, not deleted)
    def assert_students(self, ids: Iterable[UUID]) -> None  # raises ApiError(403, "forbidden") on any id outside student_ids
```

- `resolve_scope(conn, user) -> Scope` — one query per role:
  - **teacher**: `room_ids = {users.room_id} ∪ SELECT room_id FROM schedules WHERE teacher_user_id = me AND deleted_at IS NULL`; `student_ids = SELECT id FROM students WHERE room_id = ANY(room_ids) AND status = 'active' AND deleted_at IS NULL`.
  - **guardian**: `student_ids = SELECT sg.student_id FROM student_guardians sg JOIN students s ON s.id = sg.student_id AND s.deleted_at IS NULL WHERE sg.guardian_id = users.guardian_id AND sg.deleted_at IS NULL`. Dismissed/archived children **stay visible** (history).
  - Teacher with `room_id NULL` and no schedules → empty scope; endpoints return empty lists (200), not 403. Guardian with `guardian_id NULL` → 403 `forbidden` (misconfigured account, manager must fix).
- FastAPI dependency `require_scope` = `require_roles("teacher", "guardian")` + `resolve_scope`. Manager/supervisor are **rejected** on `/me/*` (403) — they use the dashboard routes; `/me/*` stays a single-purpose boundary.
- Empty-`student_ids` fast path: every list query short-circuits to `{"items": [], "total": 0, ...}` without touching the DB (avoids `= ANY('{}')` edge cases).
- `attendance.py` guards: the three teacher-reachable routes call `resolve_scope` only when `user["role"] == "teacher"` and run `scope.assert_students(...)` on every posted/filtered `student_id`; the teacher path of `GET /daily-evaluations` appends `AND e.student_id = ANY(%s)`. Manager/supervisor behaviour unchanged.

---

## 2. Endpoint contract — **[APPROVED]**

Router `routers/me.py`, prefix `/api/v1/me`, registered in `main.py` before `crud.router`.

All list endpoints: `limit` (1–500, default 100) / `offset`; envelope `{"items", "total", "limit", "offset"}` — identical to `crud.py` so the app reuses the same `fetchAll` pattern. All rows pass through `row_to_json` (Rule 9 float safety). Every query appends `AND <fk> = ANY(%(student_ids)s)` (or `room_id = ANY(%(room_ids)s)`) — a client-supplied id is never trusted without the scope check.

T = teacher, G = guardian.

| Method | Path | Roles | Filters | Returns |
| :--- | :--- | :--- | :--- | :--- |
| GET | `/me/profile` | T, G | — | `{user: {id, username, role, name}, scope: {room_ids, student_ids}, center: {name, phone}}` — `name` via `staff` / `guardians`; `center` from `system_settings` (`services/settings.py`) |
| GET | `/me/students` | T, G | `status` | T: full student rows for `room_ids`; G: **projected** rows (Section 3) for `student_ids` |
| GET | `/me/rooms` | T | — | rooms in `room_ids` |
| GET | `/me/schedule` | T, G | `day` | T: `schedules WHERE room_id = ANY(room_ids)`; G: schedules for the rooms of the guardian's children; joined `room_name`, `teacher_name` |
| GET | `/me/attendance` | T, G | `student_id`, `date_from`, `date_to` | `student_attendance` in scope; joined `student_name` |
| GET | `/me/evaluations` | T, G | `student_id`, `eval_type`, `date_from`, `date_to` | `evaluations` in scope; joined `student_name` |
| GET | `/me/assignments` | T, G | `student_id`, `due_from`, `due_to` | T: `assignments WHERE teacher_user_id = me` **or** linked via `assignment_students` to scope; G: assignments linked to children, each row carries `student_ids: [...]` (in scope only) |
| GET | `/me/submissions` | T, G | `assignment_id`, `student_id` | `submissions` in scope; joined `assignment_title`, `student_name`; `files: [{id, storage_key, width, height}]` |
| GET | `/me/lesson-logs` | T, G | `schedule_id`, `date_from`, `date_to` | T: logs where `teacher_user_id = me` or `schedule.room_id ∈ room_ids`; G: logs for the children's rooms **without** `notes` (internal teacher notes) |
| GET | `/me/skill-progress` | T, G | `student_id`, `subject` | `skill_progress` in scope |
| GET | `/me/installments` | **G only** | `student_id`, `status` | `installments` for children; joined `fee_plan` name |
| GET | `/me/receipts` | **G only** | `student_id` | `receipts` for children |
| GET | `/me/notifications` | T, G | `unread` (bool) | `notifications WHERE user_id = me` |
| POST | `/me/notifications/{id}/read` | T, G | — | sets `read_at`; own rows only (404 otherwise) |
| POST | `/me/lesson-logs` | **T only** | body `{schedule_id, date, status, covered?, homework?, notes?}` | upsert on `(schedule_id, date)`; `schedule.room_id` must be in `room_ids` else 403; `teacher_user_id = me`; audit row |

Rules:
- Teacher writes for attendance and daily evaluations **stay on the existing routes** (`POST /attendance/students`, `POST /evaluations/daily`) with the Section 1 guards — no duplicate write paths.
- Teacher on `/me/installments` or `/me/receipts` → 403; guardian on `/me/rooms` or `POST /me/lesson-logs` → 403.
- A `student_id` filter outside scope → no match (empty list, 200), never 403, so the app cannot probe for valid ids.

---

## 3. Guardian projection, teacher writes & guards — **[APPROVED 2026-09-20]**

Code facts behind this section: the dashboard writes lesson logs through the generic `POST /lesson-logs` (`crud.py:31`, no uniqueness), so duplicates on `(schedule_id, date)` may already exist in production; the mobile `LessonLogDao.getByScheduleAndDate(...) LIMIT 1` already treats the pair as unique. `attendance.py` guards shipped early as hotfix `2a63c50` (B-5.1 + B-5.2).

### 3.1 Student projections (`GET /me/students` — both roles get a projected row, never `SELECT *`)

| Column set | Guardian | Teacher |
| :--- | :--- | :--- |
| `id, name, birth_date, nationality, gender, room_id, room_name, group_name, status, has_difficulties` | yes | yes |
| `difficulty_notes, child_notes` (notes written for the teacher) | no | yes |
| `guardian_phone, guardian_relation` (one contact number for the call/WhatsApp hook) | no | **yes** (decided by Ibrahim 2026-09-20: keep, so the teacher can call/WhatsApp the family directly) |
| `national_id, father_*, mother_*, pickup_*, previous_*, education_notes, branch_id, timestamps` | no | no |

Allow-list, not deny-list: a future column is hidden by default. The guardian already knows the child's ID and phones; excluding them limits the blast radius of a mis-linked account.

### 3.2 `POST /me/lesson-logs`

- Body `{schedule_id, date, status, covered?, homework?, notes?}`; `status ∈ {'تمت','مؤجلة','ملغاة'}` (`chk_lesson_logs_status`), `date` ISO → otherwise 422 `validation_error`.
- Scope: `SELECT room_id, branch_id FROM schedules WHERE id = %s AND deleted_at IS NULL`; no row → 404 `not_found`; `room_id ∉ scope.room_ids` → 403 `forbidden`. Covering a slot owned by another teacher is **allowed** (D2 is room-based).
- **Uniqueness: SELECT-then-write, no 007 migration.** Match `WHERE schedule_id = %s AND date = %s AND deleted_at IS NULL ORDER BY updated_at DESC LIMIT 1` so pre-existing duplicates do not break the write. UPDATE = full replace of `status, covered, homework, notes` + `teacher_user_id = me, updated_at = now()` (mobile sends the whole entity). INSERT sets `teacher_user_id = me` and `branch_id` from the schedule. Rejected alternative: a partial unique index would need a production dedupe pass first and would turn the dashboard's generic `POST /lesson-logs` into 409s — a behaviour change outside this phase. Race window = one teacher on one slot; negligible. Same pattern as `save_daily_evaluations`.
- Response: the row (200, as `crud.py` create) + `write_audit(actor, "update", "lesson_logs", row.id, {schedule_id, date, status})`.

### 3.3 `attendance.py` guards — shipped (`2a63c50`)

`_teacher_scope()` helper; whole-batch `scope.assert_students(...)` after `_validate` and before any write; `GET /daily-evaluations` appends `AND e.student_id = ANY(%s)` with an empty-scope short-circuit `{items: [], total: 0}`; `POST /attendance/staff` is manager/supervisor only (B-5.2: it writes `payroll_runs`; no teacher client exists). 403 message «الطالب خارج نطاق صلاحيتك». Tests: `tests/test_teacher_scope.py` (10). Nothing further to design.

### 3.4 Guardian read details

- `/me/lesson-logs` for guardian drops `notes` (§2); `covered` and `homework` stay.
- `/me/assignments` rows carry `student_ids` intersected with scope only; `/me/submissions` never returns another child's files.

## 4. Errors, pagination, testing & delivery — **[APPROVED 2026-09-20]**

Code facts behind this section: `errors.py` provides `ApiError(status, code, message)` with Arabic `DEFAULT_MESSAGES` and `map_db_error`; `repositories/generic.py:79` paginates with a single `COUNT(*) OVER() AS _total` query and `ORDER BY <col>, id`; `db.get_conn` commits on success; `deploy/deploy.sh:94` runs `up -d --build api`; tests live in `server/tests/` with `conftest.make_user` / `login` and skip when `TEST_DATABASE_URL` is unset.

### 4.1 Errors

Reuse `ApiError` and `DEFAULT_MESSAGES`; no new error module.

| Case | Status / code | Message |
| :--- | :--- | :--- |
| manager/supervisor on any `/me/*` | 403 `forbidden` | «هذه الواجهة مخصصة لتطبيق المعلم وولي الأمر» |
| guardian with `users.guardian_id NULL` | 403 `forbidden` | «حساب ولي الأمر غير مرتبط بطالب — راجع إدارة المركز» |
| teacher on `/me/installments`, `/me/receipts`; guardian on `/me/rooms`, `POST /me/lesson-logs` | 403 `forbidden` | default |
| `schedule.room_id ∉ room_ids` on `POST /me/lesson-logs` | 403 `forbidden` | «الحلقة خارج نطاق صلاحيتك» (mirrors the `2a63c50` message «الطالب خارج نطاق صلاحيتك») |
| schedule not found; notification not own | 404 `not_found` | default — no distinction between "not yours" and "does not exist" (anti-probing, same rule as §2) |
| bad `limit`/`offset` | 422 `validation_error` | «قيم التصفح غير صحيحة» (same string as `crud.py:226`) |
| bad ISO date, bad `status`, missing body field | 422 `validation_error` | «بيانات غير صالحة» (default) |
| DB constraint violations | via `map_db_error` | — |

Out-of-scope filter values (`student_id`, `schedule_id`, `assignment_id`) are **never** an error: they are intersected with the scope and yield an empty 200 (§2).

### 4.2 Pagination, ordering & module layout

- Envelope `{"items", "total", "limit", "offset"}`; `limit` 1–500, default 100; `total` from `COUNT(*) OVER() AS _total` in the same query (pattern of `generic.py:79`), popped from each row before serialisation.
- **Fixed, deterministic order per table** so `offset` paging never skips or repeats rows — every ORDER BY ends with `, id`:

| Table | ORDER BY |
| :--- | :--- |
| `student_attendance`, `evaluations`, `lesson_logs`, `skill_progress` | `date DESC, id` |
| `assignments` | `due_date DESC, id` |
| `submissions` | `submitted_at DESC, id` |
| `schedules` | `day, start_time, id` (`day` is `text` — deterministic, not weekday-chronological; the app groups by weekday itself) |
| `students`, `rooms` | `name, id` |
| `notifications` | `created_at DESC, id` |
| `installments` | `due_date, id` |
| `receipts` | `issued_on DESC, id` |

  Column names verified against `db/postgres/001_schema.sql` on 2026-09-20.
- No `q` free-text search on `/me/*` — the app filters locally in Room.
- **Module layout (decided):** `routers/me.py` = 15 thin handlers (auth dependency, query-param validation, repo call, `row_to_json`); `repositories/me_repo.py` = plain functions `(conn, scope, filters, limit, offset) -> (rows, total)` plus `upsert_lesson_log(conn, scope, user, body) -> row`; `scope.py` gains the guardian branch and `require_scope`. Expected sizes ≈ 250 / 350 / 90 lines.

### 4.3 Tests (Claude Code exclusive, TDD)

New `server/tests/test_me_scope.py`. Helpers `_room`, `_student`, `_teacher` move from `test_teacher_scope.py` into `conftest.py`; new `_guardian(db, client, username, child_ids)` inserts `guardians` + `student_guardians` and sets `users.guardian_id`.

Fixture world: rooms A, B, C; teacher T with home room A and one schedule slot in B; students a1, a2 (room A), b1 (B), c1 (C, `status = 'dismissed'`); guardian G linked to a1 + c1; guardian G2 linked to b1; one notification per user.

| Group | Tests (~26) |
| :--- | :--- |
| Boundary | manager 403 on `/me/profile`; teacher 403 on `/me/installments`; guardian 403 on `/me/rooms` and `POST /me/lesson-logs`; guardian with NULL `guardian_id` 403 |
| Teacher scope | `/me/students` = {a1, a2, b1}, never c1; `/me/rooms` = {A, B}; teacher with no room and no schedule → empty 200 on every list; `student_id=c1` filter → empty 200; teacher projection includes `guardian_phone`, excludes `national_id` |
| Guardian scope | `/me/students` = {a1, c1} (dismissed child visible), row has no `guardian_phone` / `difficulty_notes`; `/me/attendance?student_id=b1` → empty 200; `/me/lesson-logs` rows have no `notes` key; `/me/installments` only own children; G2 cannot see a1 submissions |
| `POST /me/lesson-logs` | insert in room-B slot → 200, `teacher_user_id = T`, audit row; second POST on same `(schedule_id, date)` updates, count unchanged; pre-seeded duplicate pair → newest row updated, count unchanged; slot in room C → 403; unknown schedule → 404; bad `status` → 422 |
| Notifications | `POST /me/notifications/{id}/read` own → 200 with `read_at`; another user's id → 404; `unread=true` filter |
| Pagination | `limit=0` → 422; `limit=2&offset=2` on 5 attendance rows → rows 3–4, `total = 5` |

Environment: embedded PG 16 via the scratchpad booter, `TEST_DATABASE_URL` (superuser, fixtures) + `DATABASE_URL` (`gheras_app`, API). Gate: full suite green (63 existing + new), `ruff` no new findings, then `[APPROVED]`.

### 4.4 Delegation & deploy

Sequential batches, single writer per file. The fleet never touches `scope.py` or any test file.

| Batch | Who | Files (≤ 4) | Content |
| :--- | :--- | :--- | :--- |
| 0 | Claude Code (TDD) | `scope.py`, `tests/conftest.py`, `tests/test_me_scope.py`, `tests/test_teacher_scope.py` (helpers moved out) | guardian branch + `require_scope`; fixtures; all tests written and observed RED |
| 1 | OpenCode Worker B (Muse Spark) | `routers/me.py`, `repositories/me_repo.py`, `main.py` | profile, students (both projections), rooms, schedule, notifications + read |
| 2 | Worker B | `me.py`, `me_repo.py` | attendance, evaluations, assignments, submissions, lesson-logs GET, skill-progress |
| 3 | Worker B | `me.py`, `me_repo.py` | installments, receipts, `POST /me/lesson-logs` |

Each batch prompt carries the closed endpoint table with exact SQL and ordering from the implementation plan. After each batch Claude Code audits the diff and runs the suite; the next batch does not start until the previous one is `[APPROVED]`. Cline Worker D is **not** run in parallel (batches 1–3 share two files; a merge conflict costs more than the parallelism saves). Worker A / Worker C have no work in this phase (no UI, no shell scaffolding).

Deploy (Ibrahim, production SSH): `git push` + `bash deploy/push.sh …` → `deploy.sh` runs `up -d --build api`, so the API rebuilds. **No migration, no dashboard cache bump** (`v=` unchanged — no `web/` file changes). Public check: `/api/v1/health` → 200 and `GET /api/v1/me/profile` without a token → 401 (proves the router is mounted). The mobile app (Phase 5 (c)) consumes this contract later; nothing on production depends on it shipping first.

---

## 5. Out of scope (Phase 5 (c)+ backlog)

- Android Compose app wiring to `/me/*` (Phase 5 (c)).
- Teacher-side homework camera upload (`POST /me/submissions` with files) — needs storage design.
- Guardian push notifications / WhatsApp hooks — needs a messaging provider decision.
- Partial unique index on `lesson_logs (schedule_id, date)` after a production dedupe pass.
- Mada payments (Grand Slam later package).
