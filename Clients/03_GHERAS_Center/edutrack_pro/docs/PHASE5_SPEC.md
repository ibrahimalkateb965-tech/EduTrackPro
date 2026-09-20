# EduTrack Pro — Phase 5 (b) Specification: Mobile Role Scoping (`teacher` / `guardian`)

- **Owner**: Autovem Master Architect (Claude Code CLI)
- **Client**: Gheras Center (`Clients/03_GHERAS_Center`)
- **Status**: **[DESIGN IN PROGRESS — Sections 1–2 APPROVED by Ibrahim 2026-09-20, Sections 3–4 PENDING]**
- **Baseline**: `af6afff` on `main` (Phase 4 closed in production, v=3.0, migrations 001–006)
- **Process**: superpowers brainstorming, architectural path. Next steps after Section 4 approval: spec self-review → Ibrahim reviews this file → `superpowers:writing-plans` → delegate routers to OpenCode Worker B (Muse Spark), tests by Claude Code only.

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

## 3. Guardian projection, teacher writes & guards — **[PENDING — not yet presented]**

To cover (draft intent, not approved):
- Guardian `students` projection allow-list: `id, name, birth_date, nationality, gender, room_id, room_name, group_name, status, has_difficulties` — **excluded**: `national_id`, `difficulty_notes`, `child_notes`, `father_*`, `mother_*`, `guardian_phone`, `pickup_*`, `previous_*`, `education_notes`, `branch_id`, timestamps.
- Teacher `students` rows: full row minus `national_id`? (question for Ibrahim).
- `POST /me/lesson-logs` validation (`status ∈ {'تمت','مؤجلة','ملغاة'}` per `chk_lesson_logs_status`, `date` ISO, `schedule_id` must exist and be in scope), audit action. **Note:** `lesson_logs` has **no** unique constraint on `(schedule_id, date)` (`001_schema.sql:503-521`) — upsert must be SELECT-then-INSERT/UPDATE like `save_daily_evaluations`, or add `uq_lesson_logs_schedule_date` in a 007 migration (decision for Section 3).
- Exact guard placement in `attendance.py` (3 routes + alias) and 403 Arabic messages.

## 4. Errors, pagination, testing & delegation — **[PENDING — not yet presented]**

To cover (draft intent, not approved):
- Error codes reuse `ApiError` conventions (`401 unauthorized`, `403 forbidden`, `404 not_found`, `422 validation_error`), Arabic messages.
- Tests (Claude Code exclusive): new `tests/test_me_scope.py` — fixtures `teacher` (room A + schedule in room B), `guardian` (2 children, 1 dismissed), negative cases (cross-room student, guardian probing another child's id, manager on `/me/*`, teacher on `/me/installments`), regression tests for the 3 patched `attendance.py` routes; embedded PG 16 via `pgserver`, two DB URLs (`TEST_DATABASE_URL` superuser, `DATABASE_URL` `gheras_app`).
- Delegation: `scope.py` + `attendance.py` guards = Claude Code (security boundary); `routers/me.py` reads = OpenCode Worker B (Muse Spark) in ≤ 4-file batches with a closed endpoint table; `POST /me/lesson-logs` = Worker B second batch; tests = Claude Code only.
- Deploy: API restart required (`deploy/push.sh` + `docker compose restart api`), no migration, no cache bump.
