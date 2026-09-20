# Phase 5 (b) — Mobile Role Scoping (`/api/v1/me/*`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `teacher` and `guardian` accounts a row-scoped, read-mostly REST surface under `/api/v1/me` (15 routes) plus one teacher write (`POST /me/lesson-logs`), without touching the manager/supervisor CRUD routes.

**Architecture:** `scope.py` resolves a frozen `Scope(role, room_ids, student_ids, user_id)` per request (`require_scope` dependency); `repositories/me_repo.py` holds every SQL statement, each bounded by `= ANY(scope.…)` and paginated with `COUNT(*) OVER()`; `routers/me.py` is 15 thin handlers (auth → validate → repo → `row_to_json` envelope). `crud.py` stays untouched. Tests are written first (batch 0, Claude Code) and observed RED; batches 1–3 (OpenCode Worker B) turn them GREEN group by group.

**Tech Stack:** FastAPI ≥ 0.115, psycopg 3 (`dict_row`, `%(name)s` params), PostgreSQL 16 (migrations 001–006, no new migration), pytest 8 + `TestClient`, embedded PG via `pgserver` for tests, ruff via `uvx`.

**Spec:** `Clients/03_GHERAS_Center/edutrack_pro/docs/PHASE5_SPEC.md` (Sections 1–4 approved 2026-09-20). Read it before any task; this plan cites it as §N.

## Global Constraints

- **No schema migration.** Migrations stay 001–006. `lesson_logs (schedule_id, date)` uniqueness is SELECT-then-write (§3.2).
- **`crud.py`, `attendance.py`, `auth.py`, `errors.py`, `generic.py` are not modified** in this phase. `main.py` gains one `include_router` line only.
- **Every list query** appends `AND <fk> = ANY(%(student_ids)s)` or `room_id = ANY(%(room_ids)s)`; client ids only narrow, never widen (§2). Out-of-scope filter → empty 200, never 403 (§2, §4.1).
- **Envelope** `{"items", "total", "limit", "offset"}`; `limit` 1–500 default 100; bad `limit`/`offset` → 422 `validation_error` «قيم التصفح غير صحيحة» (§4.2). `total` from `COUNT(*) OVER() AS _total`, popped before serialisation.
- **ORDER BY** per table, always ending `, id` (§4.2): attendance/evaluations/lesson_logs/skill_progress `date DESC, id`; assignments `due_date DESC, id`; submissions `submitted_at DESC, id`; schedules `day, start_time, id`; students/rooms `name, id`; notifications `created_at DESC, id`; installments `due_date, id`; receipts `issued_on DESC, id`.
- **Errors** reuse `ApiError` (§4.1). Messages, verbatim: manager/supervisor on `/me/*` → 403 «هذه الواجهة مخصصة لتطبيق المعلم وولي الأمر»; guardian with `guardian_id NULL` → 403 «حساب ولي الأمر غير مرتبط بطالب — راجع إدارة المركز»; schedule room out of scope → 403 «الحلقة خارج نطاق صلاحيتك»; role-mismatch on a route → 403 default; not found / not own → 404 default.
- **Rule 9:** every row passes `row_to_json` (Decimal → rounded float).
- **Language:** code, commits, identifiers in English; user-facing messages in Arabic exactly as listed. Rule 50: Western numerals only.
- **Line endings:** LF. When editing via Python on Windows use `newline=""` (lesson 2026-09-20). Verify with `git diff --stat` + `file <path>` (no "CRLF").
- **Fleet rules (§4.4):** batches 1–3 = OpenCode Worker B (`opencode run -m opencode-go/muse-spark-1.3-contributor`), ≤ 4 files per batch, one `opencode run` at a time, fleet never touches `scope.py` or `tests/`. Only Claude Code runs pytest/ruff and issues `[APPROVED]`.
- **Test environment:** embedded PG booter must stay alive in the background while pytest runs; `TEST_DATABASE_URL` = superuser URI (fixtures), `DATABASE_URL` = `gheras_app` URI (API). See "Test harness" below.
- **Plan deviations from the spec (accepted here, note in the state file):** (1) `Scope` gains a 4th field `user_id: UUID` so repo functions can filter `teacher_user_id = me` / `notifications.user_id = me` with the spec's `(conn, scope, filters, limit, offset)` signature; `upsert_lesson_log` therefore takes `(conn, scope, body)` instead of `(conn, scope, user, body)`. (2) Empty-scope fast path is keyed on the set the query filters by (`room_ids` for rooms/teacher schedule/teacher lesson-logs; `student_ids` elsewhere) so a teacher whose room has no active students yet still sees the room and its schedule. (3) `fee_plans` has no `name` column; `/me/installments` joins `fp.total_amount AS plan_total, fp.count AS plan_count` instead. (4) Test count is 37 rather than ~26 because each spec bullet became its own test.

---

## Test harness (used by every "Run" step)

All commands run from `Clients/03_GHERAS_Center/edutrack_pro/server/` in Git Bash.

**Booter** (terminal 1, leave running; it drops + recreates `gheras_edutrack`, applies 001–006, writes the two URI files, prints `READY` to stderr):

```bash
BOOT="C:/Users/Kt/AppData/Local/Temp/claude/F--AI-PROJECTS-Autovemtech/872795f8-9f7d-418d-9b09-861aabb7cde0/scratchpad"
uv run --python 3.12 --with pgserver --with "psycopg[binary]" python "$BOOT/pg_boot.py" 2> "$BOOT/pg_boot.err" &
until grep -q READY "$BOOT/pg_boot.err"; do sleep 1; done; echo booted
```

**pytest** (terminal 2, same shell each time):

```bash
BOOT="C:/Users/Kt/AppData/Local/Temp/claude/F--AI-PROJECTS-Autovemtech/872795f8-9f7d-418d-9b09-861aabb7cde0/scratchpad"
export TEST_DATABASE_URL="$(cat "$BOOT/pg_uri_super.txt")"
export DATABASE_URL="$(cat "$BOOT/pg_uri_app.txt")"
.venv/Scripts/python.exe -m pytest tests -q            # full suite
.venv/Scripts/python.exe -m pytest tests/test_me_scope.py -q -k "b1"   # one batch
```

**ruff** (baseline at HEAD `3433cba` = 93 findings, all pre-existing `B008` FastAPI `Depends` idiom + `DTZ011 date.today()`; the gate is "no new finding", not "zero"):

```bash
uvx ruff check edutrack_api tests --statistics
```

**Stop the booter** when the session ends: `kill %1` (or `pg_ctl -D "$BOOT/pgdata" -m fast stop`).

**Test naming convention:** every test in `tests/test_me_scope.py` is prefixed `test_b0_`, `test_b1_`, `test_b2_` or `test_b3_` = the batch that turns it GREEN. `-k b1` selects a batch. Batch 0's own tests (`test_b0_*`, direct `resolve_scope` calls) go GREEN inside batch 0; `b1`–`b3` are observed RED at the end of batch 0 and stay RED until their batch lands.

---

## File structure

| File | Batch | Responsibility |
| :--- | :--- | :--- |
| `server/edutrack_api/scope.py` (modify, 55 → ~95 lines) | 0 | `Scope` (+ `user_id`), teacher branch (existing), guardian branch, `resolve_scope` role dispatch, `require_scope` dependency, the three Arabic message constants. |
| `server/tests/conftest.py` (modify, 85 → ~120 lines) | 0 | Shared helpers `_room`, `_student`, `_teacher` (moved in), new `_guardian`. |
| `server/tests/test_teacher_scope.py` (modify) | 0 | Drop the three local helpers, import them from `tests.conftest`. Behaviour unchanged. |
| `server/tests/test_me_scope.py` (create, ~450 lines) | 0 | `World` fixture + 37 tests in 4 batch groups (4/16/7/10). |
| `server/edutrack_api/repositories/me_repo.py` (create, ~330 lines) | 1, 2, 3 | All SQL for `/me/*`: `profile`, 12 `list_*`, `mark_notification_read`, `upsert_lesson_log`. Internal helpers `_run`, `_opt`, `_select`, `_student_where`. |
| `server/edutrack_api/routers/me.py` (create, ~230 lines) | 1, 2, 3 | 15 handlers; helpers `_page`, `_envelope`, `_only`. Pydantic `LessonLogIn`. |
| `server/edutrack_api/main.py` (modify, +2 lines) | 1 | `include_router(me.router)` before `crud.router`. |
| `docs/PHASE5_SPEC.md` (modify, status line) | 3 | Status → IMPLEMENTED + commit hash. |

---

## Task 0-A: `scope.py` — guardian branch, `user_id`, `require_scope` (Claude Code, TDD)

**Files:**
- Modify: `server/edutrack_api/scope.py`
- Modify: `server/tests/conftest.py`
- Modify: `server/tests/test_teacher_scope.py:17-38`
- Create: `server/tests/test_me_scope.py` (fixture + `test_b0_*` only in this task; `b1`–`b3` tests are added in Task 0-B)

**Interfaces:**
- Consumes: `edutrack_api.auth.current_user`, `edutrack_api.db.get_conn`, `edutrack_api.errors.ApiError`.
- Produces (used by every later task):
  - `Scope(role: str, room_ids: frozenset[UUID], student_ids: frozenset[UUID], user_id: UUID)` frozen dataclass with `assert_students(ids)`.
  - `resolve_scope(conn, user: dict) -> Scope` — teacher / guardian, else `ApiError(403)`.
  - `require_scope(user=Depends(current_user), conn=Depends(get_conn)) -> Scope` — FastAPI dependency.
  - Constants `MSG_NOT_MOBILE`, `MSG_GUARDIAN_UNLINKED` (str).
  - Test helpers in `tests.conftest`: `_room(client, manager, name) -> str`, `_student(client, manager, name, room_id) -> str`, `_teacher(db, client, username, room_id) -> tuple[str, dict]`, `_guardian(db, client, username, child_ids: list[str]) -> tuple[str, dict]`.

- [ ] **Step 1: Move the three helpers into `conftest.py` and add `_guardian`**

Append to `server/tests/conftest.py` (after the `supervisor` fixture):

```python
# ---- Phase 5 helpers (shared by test_teacher_scope.py and test_me_scope.py) ----


def _room(client, manager: dict, name: str) -> str:
    res = client.post("/api/v1/rooms", json={"name": name, "group_name": "الصباح"}, headers=manager)
    assert res.status_code == 200, res.text
    return res.json()["id"]


def _student(client, manager: dict, name: str, room_id: str | None) -> str:
    res = client.post(
        "/api/v1/students",
        json={"name": name, "guardian_phone": "0500000000", "gender": "بنين", "room_id": room_id},
        headers=manager,
    )
    assert res.status_code == 200, res.text
    return res.json()["id"]


def _teacher(db, client, username: str, room_id: str | None) -> tuple[str, dict]:
    uid = make_user(db, username, "teacher")
    if room_id:
        db.execute("UPDATE users SET room_id = %s WHERE id = %s", (room_id, uid))
        db.commit()
    return str(uid), login(client, username)


def _guardian(db, client, username: str, child_ids: list[str]) -> tuple[str, dict]:
    """guardians row + student_guardians links + a guardian user pointing at it."""
    phone = f"05{uuid.uuid4().int % 10**8:08d}"  # uq_guardians_phone
    gid = db.execute(
        "INSERT INTO guardians (name, phone, relation) VALUES (%s, %s, 'الأب') RETURNING id",
        (username, phone),
    ).fetchone()["id"]
    for cid in child_ids:
        db.execute("INSERT INTO student_guardians (student_id, guardian_id) VALUES (%s, %s)", (cid, gid))
    uid = make_user(db, username, "guardian")  # commits
    db.execute("UPDATE users SET guardian_id = %s WHERE id = %s", (gid, uid))
    db.commit()
    return str(uid), login(client, username)
```

In `server/tests/test_teacher_scope.py` delete lines 17–38 (the local `_room`, `_student`, `_teacher`) and change the import line to:

```python
from tests.conftest import _room, _student, _teacher
```

(`login` and `make_user` were only used by the moved `_teacher`; nothing else in the file references them, so they leave the import.)

- [ ] **Step 2: Run the existing scope suite to prove the move is behaviour-neutral**

Run: `.venv/Scripts/python.exe -m pytest tests/test_teacher_scope.py -q`
Expected: `10 passed`.

- [ ] **Step 3: Write the failing `b0` tests (new file)**

Create `server/tests/test_me_scope.py`:

```python
"""Phase 5 (b): /api/v1/me/* row scoping for teacher and guardian (PHASE5_SPEC §1–§4).

Test names carry the batch that turns them green: b0 = scope.py (Claude Code),
b1 = profile/students/rooms/schedule/notifications, b2 = attendance/evaluations/
assignments/submissions/lesson-logs GET/skill-progress, b3 = installments/receipts/
POST lesson-logs.

World: rooms A, B, C; teacher T (home A, one slot in B); students a1, a2 (A),
b1 (B), c1 (C, dismissed); guardian G → {a1, c1}; guardian G2 → {b1}.
"""

from __future__ import annotations

import datetime as dt
import uuid
from dataclasses import dataclass

import pytest

from edutrack_api.errors import ApiError
from edutrack_api.scope import MSG_GUARDIAN_UNLINKED, MSG_NOT_MOBILE, resolve_scope
from tests.conftest import _guardian, _room, _student, _teacher, login, make_user

TODAY = dt.date.today()
ME = "/api/v1/me"


def _iso(d: dt.date) -> str:
    return d.isoformat()


def _slot(db, room_id: str, teacher_id: str | None, day: str = "الأحد", start: str = "08:00", end: str = "09:00", subject: str = "القرآن") -> str:
    row = db.execute(
        "INSERT INTO schedules (room_id, teacher_user_id, day, start_time, end_time, subject) "
        "VALUES (%s, %s, %s, %s, %s, %s) RETURNING id",
        (room_id, teacher_id, day, start, end, subject),
    ).fetchone()
    db.commit()
    return str(row["id"])


def _notify(db, user_id: str, title: str) -> str:
    row = db.execute(
        "INSERT INTO notifications (user_id, kind, title) VALUES (%s, 'info', %s) RETURNING id",
        (user_id, title),
    ).fetchone()
    db.commit()
    return str(row["id"])


def _user_row(db, user_id: str) -> dict:
    return dict(db.execute("SELECT id, username, role, staff_id, guardian_id, room_id FROM users WHERE id = %s", (user_id,)).fetchone())


@dataclass
class World:
    room_a: str
    room_b: str
    room_c: str
    a1: str
    a2: str
    b1: str
    c1: str
    teacher_id: str
    teacher: dict
    sched_a: str  # room A, no teacher, الاثنين
    sched_b: str  # room B, teacher T, الأحد
    sched_c: str  # room C, no teacher, الثلاثاء
    guardian_id: str
    guardian: dict
    guardian2_id: str
    guardian2: dict
    manager_id: str


@pytest.fixture()
def world(client, db, manager) -> World:
    room_a = _room(client, manager, "أ")
    room_b = _room(client, manager, "ب")
    room_c = _room(client, manager, "ج")
    a1 = _student(client, manager, "طالب أ1", room_a)
    a2 = _student(client, manager, "طالب أ2", room_a)
    b1 = _student(client, manager, "طالب ب", room_b)
    c1 = _student(client, manager, "طالب ج", room_c)
    db.execute("UPDATE students SET status = 'dismissed' WHERE id = %s", (c1,))
    db.commit()
    teacher_id, teacher = _teacher(db, client, "t_main", room_a)
    sched_a = _slot(db, room_a, None, day="الاثنين")
    sched_b = _slot(db, room_b, teacher_id)
    sched_c = _slot(db, room_c, None, day="الثلاثاء")
    guardian_id, guardian = _guardian(db, client, "g_main", [a1, c1])
    guardian2_id, guardian2 = _guardian(db, client, "g_two", [b1])
    manager_id = str(db.execute("SELECT id FROM users WHERE username = 'manager1'").fetchone()["id"])
    return World(room_a, room_b, room_c, a1, a2, b1, c1, teacher_id, teacher, sched_a, sched_b, sched_c,
                 guardian_id, guardian, guardian2_id, guardian2, manager_id)


# =============================================================================
# b0 — scope.py (direct calls, no HTTP)
# =============================================================================


def test_b0_resolve_scope_guardian_lists_children_including_dismissed(db, world):
    scope = resolve_scope(db, _user_row(db, world.guardian_id))
    assert scope.role == "guardian"
    assert scope.room_ids == frozenset()
    assert {str(s) for s in scope.student_ids} == {world.a1, world.c1}
    assert str(scope.user_id) == world.guardian_id


def test_b0_resolve_scope_guardian_unlinked_is_403(db, world):
    uid = make_user(db, "g_unlinked", "guardian")
    with pytest.raises(ApiError) as exc:
        resolve_scope(db, _user_row(db, str(uid)))
    assert exc.value.status == 403
    assert exc.value.message == MSG_GUARDIAN_UNLINKED


def test_b0_resolve_scope_rejects_dashboard_roles(db, world):
    with pytest.raises(ApiError) as exc:
        resolve_scope(db, _user_row(db, world.manager_id))
    assert exc.value.status == 403
    assert exc.value.message == MSG_NOT_MOBILE


def test_b0_teacher_scope_carries_user_id_and_both_rooms(db, world):
    scope = resolve_scope(db, _user_row(db, world.teacher_id))
    assert scope.role == "teacher"
    assert str(scope.user_id) == world.teacher_id
    assert {str(r) for r in scope.room_ids} == {world.room_a, world.room_b}
    assert {str(s) for s in scope.student_ids} == {world.a1, world.a2, world.b1}
```

- [ ] **Step 4: Run the b0 tests, observe RED**

Run: `.venv/Scripts/python.exe -m pytest tests/test_me_scope.py -q -k b0`
Expected: collection error `ImportError: cannot import name 'MSG_GUARDIAN_UNLINKED' from 'edutrack_api.scope'` (4 errors). That is the RED.

- [ ] **Step 5: Implement `scope.py`**

Replace the whole of `server/edutrack_api/scope.py` with:

```python
"""Row scope for mobile roles (PHASE5_SPEC §1).

teacher  → room_ids = users.room_id ∪ schedules.room_id (teacher_user_id = me);
           student_ids = active, non-deleted students in those rooms.
guardian → room_ids = ∅; student_ids = children via student_guardians
           (dismissed/archived children stay visible for history; soft-deleted do not).
Empty scope is legal (teacher with no room and no schedules): reads return
nothing, writes are refused. A guardian whose users.guardian_id is NULL is a
misconfigured account → 403. Any other role → 403 (the dashboard uses the CRUD routes).
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from uuid import UUID

from fastapi import Depends

from edutrack_api.auth import current_user
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError

MSG_NOT_MOBILE = "هذه الواجهة مخصصة لتطبيق المعلم وولي الأمر"
MSG_GUARDIAN_UNLINKED = "حساب ولي الأمر غير مرتبط بطالب — راجع إدارة المركز"
MSG_STUDENT_OUT_OF_SCOPE = "الطالب خارج نطاق صلاحيتك"


@dataclass(frozen=True)
class Scope:
    role: str
    room_ids: frozenset[UUID]
    student_ids: frozenset[UUID]
    user_id: UUID

    def assert_students(self, ids: Iterable[object]) -> None:
        for raw in ids:
            try:
                sid = UUID(str(raw))
            except ValueError:
                sid = None
            if sid not in self.student_ids:
                raise ApiError(403, "forbidden", MSG_STUDENT_OUT_OF_SCOPE)


def _teacher_scope(conn, user: dict) -> Scope:
    room_ids: set[UUID] = set()
    if user.get("room_id"):
        room_ids.add(user["room_id"])
    for row in conn.execute(
        "SELECT room_id FROM schedules WHERE teacher_user_id = %s AND deleted_at IS NULL",
        (user["id"],),
    ).fetchall():
        room_ids.add(row["room_id"])

    student_ids: set[UUID] = set()
    if room_ids:
        for row in conn.execute(
            "SELECT id FROM students WHERE room_id = ANY(%s) AND status = 'active' AND deleted_at IS NULL",
            (list(room_ids),),
        ).fetchall():
            student_ids.add(row["id"])

    return Scope(role="teacher", room_ids=frozenset(room_ids), student_ids=frozenset(student_ids), user_id=user["id"])


def _guardian_scope(conn, user: dict) -> Scope:
    if not user.get("guardian_id"):
        raise ApiError(403, "forbidden", MSG_GUARDIAN_UNLINKED)
    rows = conn.execute(
        "SELECT sg.student_id FROM student_guardians sg "
        "JOIN students s ON s.id = sg.student_id AND s.deleted_at IS NULL "
        "WHERE sg.guardian_id = %s AND sg.deleted_at IS NULL",
        (user["guardian_id"],),
    ).fetchall()
    return Scope(
        role="guardian",
        room_ids=frozenset(),
        student_ids=frozenset(r["student_id"] for r in rows),
        user_id=user["id"],
    )


def resolve_scope(conn, user: dict) -> Scope:
    if user["role"] == "teacher":
        return _teacher_scope(conn, user)
    if user["role"] == "guardian":
        return _guardian_scope(conn, user)
    raise ApiError(403, "forbidden", MSG_NOT_MOBILE)


def require_scope(user: dict = Depends(current_user), conn=Depends(get_conn)) -> Scope:
    """FastAPI dependency for /api/v1/me/*: teacher or guardian only, scope resolved once per request."""
    return resolve_scope(conn, user)
```

- [ ] **Step 6: Run b0 + the old scope suite, observe GREEN**

Run: `.venv/Scripts/python.exe -m pytest tests/test_me_scope.py tests/test_teacher_scope.py -q -k "b0 or teacher_scope"`
Expected: `14 passed` (4 new + 10 existing). `attendance.py` still works because it only calls `resolve_scope(conn, user)` for teachers and reads `.student_ids`.

- [ ] **Step 7: Commit**

```bash
git add server/edutrack_api/scope.py server/tests/conftest.py server/tests/test_teacher_scope.py server/tests/test_me_scope.py
git commit -m "feat(scope): guardian branch, require_scope dependency, shared test helpers (Phase 5 batch 0a)

Claude-Session: https://claude.ai/code/session_013pQQTqdgMWLQsTMvwHfZHZ"
```

---

## Task 0-B: Write every `/me/*` test and observe RED (Claude Code)

**Files:**
- Modify: `server/tests/test_me_scope.py` (append)

**Interfaces:**
- Consumes: `World` fixture, helpers from Task 0-A.
- Produces: the acceptance suite for Tasks 1–3. Route paths and JSON shapes asserted here are the contract Worker B implements against.

- [ ] **Step 1: Append the b1 tests (profile, students, rooms, schedule, notifications, boundary)**

```python
# =============================================================================
# b1 — profile, students, rooms, schedule, notifications, boundary
# =============================================================================


def test_b1_unauthenticated_profile_is_401(client):
    res = client.get(f"{ME}/profile")
    assert res.status_code == 401, res.text


def test_b1_manager_is_rejected_on_me_routes(client, manager):
    res = client.get(f"{ME}/profile", headers=manager)
    assert res.status_code == 403, res.text
    assert res.json()["error"] == {"code": "forbidden", "message": MSG_NOT_MOBILE}


def test_b1_guardian_without_link_is_403(client, db):
    make_user(db, "g_orphan", "guardian")

    res = client.get(f"{ME}/profile", headers=login(client, "g_orphan"))
    assert res.status_code == 403, res.text
    assert res.json()["error"]["message"] == MSG_GUARDIAN_UNLINKED


def test_b1_guardian_cannot_list_rooms(client, world):
    res = client.get(f"{ME}/rooms", headers=world.guardian)
    assert res.status_code == 403, res.text
    assert res.json()["error"]["code"] == "forbidden"


def test_b1_teacher_profile_has_staff_name_scope_and_center(client, db, manager, world):
    staff = client.post("/api/v1/staff", json={"name": "المعلم أحمد", "role_title": "معلم", "base_salary": 3000}, headers=manager).json()
    db.execute("UPDATE users SET staff_id = %s WHERE id = %s", (staff["id"], world.teacher_id))
    db.commit()

    res = client.get(f"{ME}/profile", headers=world.teacher)

    assert res.status_code == 200, res.text
    body = res.json()
    assert body["user"] == {"id": world.teacher_id, "username": "t_main", "role": "teacher", "name": "المعلم أحمد"}
    assert set(body["scope"]["room_ids"]) == {world.room_a, world.room_b}
    assert set(body["scope"]["student_ids"]) == {world.a1, world.a2, world.b1}
    assert isinstance(body["center"]["name"], str) and body["center"]["name"]
    assert isinstance(body["center"]["phone"], str)


def test_b1_guardian_profile_uses_guardian_name(client, world):
    res = client.get(f"{ME}/profile", headers=world.guardian)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["user"]["name"] == "g_main"
    assert body["user"]["role"] == "guardian"
    assert body["scope"]["room_ids"] == []
    assert set(body["scope"]["student_ids"]) == {world.a1, world.c1}


def test_b1_teacher_students_are_home_room_union_scheduled_rooms(client, world):
    res = client.get(f"{ME}/students", headers=world.teacher)
    assert res.status_code == 200, res.text
    body = res.json()
    assert {r["id"] for r in body["items"]} == {world.a1, world.a2, world.b1}
    assert body["total"] == 3 and body["limit"] == 100 and body["offset"] == 0
    names = [r["name"] for r in body["items"]]
    assert names == sorted(names)  # ORDER BY name, id


def test_b1_teacher_student_projection_keeps_contact_hides_identity(client, world):
    row = client.get(f"{ME}/students", headers=world.teacher).json()["items"][0]
    for key in ("id", "name", "room_id", "room_name", "status", "has_difficulties", "guardian_phone", "guardian_relation", "difficulty_notes", "child_notes"):
        assert key in row, key
    for key in ("national_id", "father_phone", "mother_phone", "pickup_phone", "created_at"):
        assert key not in row, key
    assert row["room_name"] in {"أ", "ب"}


def test_b1_guardian_students_include_dismissed_child_and_drop_private_columns(client, world):
    res = client.get(f"{ME}/students", headers=world.guardian)
    assert res.status_code == 200, res.text
    items = res.json()["items"]
    assert {r["id"] for r in items} == {world.a1, world.c1}
    assert {r["status"] for r in items} == {"active", "dismissed"}
    for row in items:
        for key in ("guardian_phone", "guardian_relation", "difficulty_notes", "child_notes", "national_id"):
            assert key not in row, key


def test_b1_out_of_scope_student_filter_is_empty_200(client, world):
    res = client.get(f"{ME}/students?student_id={world.c1}", headers=world.teacher)
    assert res.status_code == 200, res.text
    assert res.json()["items"] == [] and res.json()["total"] == 0


def test_b1_teacher_rooms_are_home_and_scheduled(client, world):
    res = client.get(f"{ME}/rooms", headers=world.teacher)
    assert res.status_code == 200, res.text
    assert {r["id"] for r in res.json()["items"]} == {world.room_a, world.room_b}


def test_b1_teacher_without_room_or_schedule_sees_empty_lists(client, db, world):
    _, empty = _teacher(db, client, "t_empty", None)
    for path in ("students", "rooms", "schedule", "notifications"):
        res = client.get(f"{ME}/{path}", headers=empty)
        assert res.status_code == 200, (path, res.text)
        assert res.json()["items"] == [] and res.json()["total"] == 0, path


def test_b1_teacher_schedule_covers_both_rooms_with_names(client, world):
    res = client.get(f"{ME}/schedule", headers=world.teacher)
    assert res.status_code == 200, res.text
    items = res.json()["items"]
    assert {r["id"] for r in items} == {world.sched_a, world.sched_b}
    by_id = {r["id"]: r for r in items}
    assert by_id[world.sched_b]["room_name"] == "ب"
    assert by_id[world.sched_b]["teacher_name"] == "t_main"  # username fallback, no staff link
    assert by_id[world.sched_a]["teacher_name"] is None
    day_only = client.get(f"{ME}/schedule?day=الأحد", headers=world.teacher).json()
    assert [r["id"] for r in day_only["items"]] == [world.sched_b]


def test_b1_guardian_schedule_is_children_rooms(client, world):
    res = client.get(f"{ME}/schedule", headers=world.guardian)
    assert res.status_code == 200, res.text
    assert {r["id"] for r in res.json()["items"]} == {world.sched_a, world.sched_c}
    other = client.get(f"{ME}/schedule", headers=world.guardian2).json()
    assert [r["id"] for r in other["items"]] == [world.sched_b]


def test_b1_notifications_are_own_rows_only_and_mark_read(client, db, world):
    n_t = _notify(db, world.teacher_id, "تنبيه للمعلم")
    n_g = _notify(db, world.guardian_id, "تنبيه لولي الأمر")

    listed = client.get(f"{ME}/notifications", headers=world.teacher).json()
    assert [r["id"] for r in listed["items"]] == [n_t]
    assert listed["items"][0]["read_at"] is None

    read = client.post(f"{ME}/notifications/{n_t}/read", headers=world.teacher)
    assert read.status_code == 200, read.text
    assert read.json()["id"] == n_t and read.json()["read_at"] is not None

    unread = client.get(f"{ME}/notifications?unread=true", headers=world.teacher).json()
    assert unread["total"] == 0
    seen = client.get(f"{ME}/notifications?unread=false", headers=world.teacher).json()
    assert seen["total"] == 1

    foreign = client.post(f"{ME}/notifications/{n_g}/read", headers=world.teacher)
    assert foreign.status_code == 404, foreign.text
    assert db.execute("SELECT read_at FROM notifications WHERE id = %s", (n_g,)).fetchone()["read_at"] is None


def test_b1_bad_pagination_is_422(client, world):
    res = client.get(f"{ME}/students?limit=0", headers=world.teacher)
    assert res.status_code == 422, res.text
    assert res.json()["error"] == {"code": "validation_error", "message": "قيم التصفح غير صحيحة"}
    assert client.get(f"{ME}/students?offset=-1", headers=world.teacher).status_code == 422
    assert client.get(f"{ME}/students?limit=501", headers=world.teacher).status_code == 422
```

- [ ] **Step 2: Append the b2 tests (attendance, evaluations, assignments, submissions, lesson-logs GET, skill-progress, pagination)**

```python
# =============================================================================
# b2 — attendance, evaluations, assignments, submissions, lesson-logs GET, skill-progress
# =============================================================================


def _attend(client, manager, student_id: str, day: dt.date, status: str = "حاضر") -> None:
    res = client.post("/api/v1/attendance/students", json=[{"student_id": student_id, "date": _iso(day), "status": status}], headers=manager)
    assert res.status_code == 200, res.text


def _assignment(db, title: str, teacher_id: str | None, student_ids: list[str], due: dt.date = TODAY) -> str:
    aid = db.execute(
        "INSERT INTO assignments (title, due_date, teacher_user_id) VALUES (%s, %s, %s) RETURNING id",
        (title, due, teacher_id),
    ).fetchone()["id"]
    for sid in student_ids:
        db.execute("INSERT INTO assignment_students (assignment_id, student_id) VALUES (%s, %s)", (aid, sid))
    db.commit()
    return str(aid)


def _submission(db, assignment_id: str, student_id: str, storage_key: str | None = None) -> str:
    sid = db.execute(
        "INSERT INTO submissions (assignment_id, student_id) VALUES (%s, %s) RETURNING id",
        (assignment_id, student_id),
    ).fetchone()["id"]
    if storage_key:
        db.execute(
            "INSERT INTO submission_files (submission_id, storage_key, width, height) VALUES (%s, %s, 800, 600)",
            (sid, storage_key),
        )
    db.commit()
    return str(sid)


def _lesson_log(db, schedule_id: str, day: dt.date, status: str = "تمت", notes: str | None = None, teacher_id: str | None = None) -> str:
    row = db.execute(
        "INSERT INTO lesson_logs (schedule_id, date, status, notes, teacher_user_id) VALUES (%s, %s, %s, %s, %s) RETURNING id",
        (schedule_id, day, status, notes, teacher_id),
    ).fetchone()
    db.commit()
    return str(row["id"])


def _skill(db, student_id: str, subject: str = "القرآن") -> None:
    db.execute(
        "INSERT INTO skill_progress (student_id, subject, skill, level, date) VALUES (%s, %s, 'حفظ', 'متقن', %s)",
        (student_id, subject, TODAY),
    )
    db.commit()


def test_b2_attendance_is_scoped_and_filterable(client, manager, world):
    _attend(client, manager, world.a1, TODAY)
    _attend(client, manager, world.b1, TODAY)
    _attend(client, manager, world.c1, TODAY)

    teacher_view = client.get(f"{ME}/attendance", headers=world.teacher).json()
    assert {r["student_id"] for r in teacher_view["items"]} == {world.a1, world.b1}
    assert all("student_name" in r for r in teacher_view["items"])

    guardian_view = client.get(f"{ME}/attendance", headers=world.guardian).json()
    assert {r["student_id"] for r in guardian_view["items"]} == {world.a1, world.c1}

    probe = client.get(f"{ME}/attendance?student_id={world.b1}", headers=world.guardian)
    assert probe.status_code == 200 and probe.json() == {"items": [], "total": 0, "limit": 100, "offset": 0}

    window = client.get(f"{ME}/attendance?date_from={_iso(TODAY + dt.timedelta(days=1))}", headers=world.teacher).json()
    assert window["total"] == 0


def test_b2_attendance_pagination_is_stable(client, manager, world):
    for i in range(5):
        _attend(client, manager, world.a1, TODAY - dt.timedelta(days=i))

    page = client.get(f"{ME}/attendance?student_id={world.a1}&limit=2&offset=2", headers=world.teacher)
    assert page.status_code == 200, page.text
    body = page.json()
    assert body["total"] == 5 and body["limit"] == 2 and body["offset"] == 2
    assert [r["date"] for r in body["items"]] == [_iso(TODAY - dt.timedelta(days=2)), _iso(TODAY - dt.timedelta(days=3))]


def test_b2_evaluations_are_scoped_with_type_filter(client, manager, world):
    payload = [
        {"student_id": world.a1, "date": _iso(TODAY), "subject": "القرآن", "value": 9},
        {"student_id": world.b1, "date": _iso(TODAY), "subject": "القرآن", "value": 7},
    ]
    assert client.post("/api/v1/evaluations/daily", json=payload, headers=manager).status_code == 200

    mine = client.get(f"{ME}/evaluations", headers=world.guardian).json()
    assert [r["student_id"] for r in mine["items"]] == [world.a1]
    assert mine["items"][0]["value"] == 9.0  # Rule 9: float, not "9.00"
    assert client.get(f"{ME}/evaluations?eval_type=daily", headers=world.guardian).json()["total"] == 1
    assert client.get(f"{ME}/evaluations?eval_type=monthly", headers=world.guardian).json()["total"] == 0
    assert client.get(f"{ME}/evaluations", headers=world.teacher).json()["total"] == 2


def test_b2_assignments_teacher_sees_own_or_linked_guardian_sees_children_only(client, db, world):
    other_id = str(make_user(db, "t_other", "teacher"))
    asg1 = _assignment(db, "واجب 1", world.teacher_id, [world.a1, world.b1])
    asg2 = _assignment(db, "واجب 2", other_id, [world.c1])
    asg3 = _assignment(db, "واجب 3", world.teacher_id, [])  # own, no links

    teacher_view = client.get(f"{ME}/assignments", headers=world.teacher).json()
    assert {r["id"] for r in teacher_view["items"]} == {asg1, asg3}
    by_id = {r["id"]: r for r in teacher_view["items"]}
    assert sorted(by_id[asg1]["student_ids"]) == sorted([world.a1, world.b1])
    assert by_id[asg3]["student_ids"] == []

    g_view = client.get(f"{ME}/assignments", headers=world.guardian).json()
    assert {r["id"] for r in g_view["items"]} == {asg1, asg2}
    g_by_id = {r["id"]: r for r in g_view["items"]}
    assert g_by_id[asg1]["student_ids"] == [world.a1]  # b1 hidden
    assert g_by_id[asg2]["student_ids"] == [world.c1]

    g2_view = client.get(f"{ME}/assignments", headers=world.guardian2).json()
    assert [r["id"] for r in g2_view["items"]] == [asg1]
    assert g2_view["items"][0]["student_ids"] == [world.b1]

    assert client.get(f"{ME}/assignments?student_id={world.c1}", headers=world.teacher).json()["total"] == 0
    assert client.get(f"{ME}/assignments?student_id={world.b1}", headers=world.teacher).json()["total"] == 1


def test_b2_submissions_never_leak_another_childs_files(client, db, world):
    asg = _assignment(db, "واجب", world.teacher_id, [world.a1, world.b1])
    sub_a1 = _submission(db, asg, world.a1, storage_key="sub/a1.jpg")
    sub_b1 = _submission(db, asg, world.b1)

    g_view = client.get(f"{ME}/submissions", headers=world.guardian).json()
    assert [r["id"] for r in g_view["items"]] == [sub_a1]
    row = g_view["items"][0]
    assert row["assignment_title"] == "واجب" and row["student_name"] == "طالب أ1"
    assert [f["storage_key"] for f in row["files"]] == ["sub/a1.jpg"]
    assert set(row["files"][0]) == {"id", "storage_key", "width", "height"}

    g2_view = client.get(f"{ME}/submissions", headers=world.guardian2).json()
    assert [r["id"] for r in g2_view["items"]] == [sub_b1]
    assert g2_view["items"][0]["files"] == []

    teacher_view = client.get(f"{ME}/submissions?assignment_id={asg}", headers=world.teacher).json()
    assert {r["id"] for r in teacher_view["items"]} == {sub_a1, sub_b1}


def test_b2_lesson_logs_teacher_sees_notes_guardian_does_not(client, db, world):
    log_a = _lesson_log(db, world.sched_a, TODAY, notes="ملاحظة داخلية")
    log_c = _lesson_log(db, world.sched_c, TODAY, notes="ملاحظة أخرى")

    teacher_view = client.get(f"{ME}/lesson-logs", headers=world.teacher).json()
    assert [r["id"] for r in teacher_view["items"]] == [log_a]  # room A in scope, room C not
    assert teacher_view["items"][0]["notes"] == "ملاحظة داخلية"
    assert teacher_view["items"][0]["room_name"] == "أ"

    g_view = client.get(f"{ME}/lesson-logs", headers=world.guardian).json()
    assert {r["id"] for r in g_view["items"]} == {log_a, log_c}  # a1 in A, c1 in C
    for row in g_view["items"]:
        assert "notes" not in row
        assert "covered" in row and "homework" in row

    assert client.get(f"{ME}/lesson-logs?schedule_id={world.sched_c}", headers=world.teacher).json()["total"] == 0
    assert client.get(f"{ME}/lesson-logs?schedule_id={world.sched_c}", headers=world.guardian).json()["total"] == 1


def test_b2_skill_progress_is_scoped_with_subject_filter(client, db, world):
    _skill(db, world.a1, "القرآن")
    _skill(db, world.b1, "القرآن")

    g_view = client.get(f"{ME}/skill-progress", headers=world.guardian).json()
    assert [r["student_id"] for r in g_view["items"]] == [world.a1]
    assert g_view["items"][0]["student_name"] == "طالب أ1"
    assert client.get(f"{ME}/skill-progress?subject=لغتي", headers=world.guardian).json()["total"] == 0
    assert client.get(f"{ME}/skill-progress", headers=world.teacher).json()["total"] == 2
```

- [ ] **Step 3: Append the b3 tests (installments, receipts, POST lesson-logs, remaining boundary)**

```python
# =============================================================================
# b3 — installments, receipts, POST lesson-logs
# =============================================================================


def _fee_plan(db, student_id: str, total: int = 1000, count: int = 2) -> str:
    fp = db.execute(
        "INSERT INTO fee_plans (student_id, total_amount, count, start_date, interval_days) VALUES (%s, %s, %s, %s, 30) RETURNING id",
        (student_id, total, count, TODAY),
    ).fetchone()["id"]
    for seq in range(1, count + 1):
        db.execute(
            "INSERT INTO installments (fee_plan_id, seq_no, due_date, amount) VALUES (%s, %s, %s, %s)",
            (fp, seq, TODAY + dt.timedelta(days=30 * (seq - 1)), total / count),
        )
    db.commit()
    return str(fp)


def _receipt(db, student_id: str, amount: int = 500) -> str:
    pid = db.execute(
        "INSERT INTO payments (student_id, amount, method, paid_on) VALUES (%s, %s, 'كاش', %s) RETURNING id",
        (student_id, amount, TODAY),
    ).fetchone()["id"]
    rid = db.execute("INSERT INTO receipts (payment_id) VALUES (%s) RETURNING id", (pid,)).fetchone()["id"]
    db.commit()
    return str(rid)


def test_b3_teacher_is_rejected_on_finance_routes(client, world):
    for path in ("installments", "receipts"):
        res = client.get(f"{ME}/{path}", headers=world.teacher)
        assert res.status_code == 403, (path, res.text)
        assert res.json()["error"]["code"] == "forbidden"


def test_b3_guardian_cannot_post_lesson_log(client, world):
    res = client.post(f"{ME}/lesson-logs", json={"schedule_id": world.sched_a, "date": _iso(TODAY), "status": "تمت"}, headers=world.guardian)
    assert res.status_code == 403, res.text


def test_b3_installments_only_own_children(client, db, world):
    _fee_plan(db, world.a1)
    _fee_plan(db, world.b1)

    res = client.get(f"{ME}/installments", headers=world.guardian)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["total"] == 2 and {r["student_id"] for r in body["items"]} == {world.a1}
    assert [r["seq_no"] for r in body["items"]] == [1, 2]  # ORDER BY due_date, id
    assert body["items"][0]["amount"] == 500.0 and body["items"][0]["plan_total"] == 1000.0
    assert body["items"][0]["student_name"] == "طالب أ1"
    assert client.get(f"{ME}/installments?status=paid", headers=world.guardian).json()["total"] == 0
    assert client.get(f"{ME}/installments?student_id={world.b1}", headers=world.guardian).json()["total"] == 0


def test_b3_receipts_only_own_children(client, db, world):
    r_a1 = _receipt(db, world.a1, 500)
    r_b1 = _receipt(db, world.b1, 300)

    g_view = client.get(f"{ME}/receipts", headers=world.guardian).json()
    assert [r["id"] for r in g_view["items"]] == [r_a1]
    assert g_view["items"][0]["amount"] == 500.0 and g_view["items"][0]["student_id"] == world.a1
    assert g_view["items"][0]["method"] == "كاش"
    g2_view = client.get(f"{ME}/receipts", headers=world.guardian2).json()
    assert [r["id"] for r in g2_view["items"]] == [r_b1]


def _log_body(schedule_id: str, status: str = "تمت", **extra) -> dict:
    return {"schedule_id": schedule_id, "date": _iso(TODAY), "status": status, **extra}


def test_b3_post_lesson_log_inserts_in_scheduled_room_and_audits(client, db, world):
    res = client.post(f"{ME}/lesson-logs", json=_log_body(world.sched_b, covered="الفاتحة", homework="حفظ", notes="داخلي"), headers=world.teacher)
    assert res.status_code == 200, res.text
    row = res.json()
    assert row["schedule_id"] == world.sched_b and row["teacher_user_id"] == world.teacher_id
    assert row["status"] == "تمت" and row["covered"] == "الفاتحة" and row["notes"] == "داخلي"
    audit = db.execute("SELECT * FROM audit_log WHERE entity = 'lesson_logs' AND entity_id = %s", (row["id"],)).fetchone()
    assert audit is not None and str(audit["actor_user_id"]) == world.teacher_id
    assert audit["details_json"]["status"] == "تمت"

    # Home-room slot owned by nobody is also writable (D2: room-based)
    home = client.post(f"{ME}/lesson-logs", json=_log_body(world.sched_a), headers=world.teacher)
    assert home.status_code == 200, home.text


def test_b3_post_lesson_log_updates_existing_pair(client, db, world):
    first = client.post(f"{ME}/lesson-logs", json=_log_body(world.sched_b, status="مؤجلة"), headers=world.teacher).json()
    second = client.post(f"{ME}/lesson-logs", json=_log_body(world.sched_b, status="تمت", covered="جزء عم"), headers=world.teacher)
    assert second.status_code == 200, second.text
    assert second.json()["id"] == first["id"]
    assert second.json()["status"] == "تمت" and second.json()["covered"] == "جزء عم"
    n = db.execute("SELECT count(*) AS n FROM lesson_logs WHERE schedule_id = %s AND date = %s", (world.sched_b, TODAY)).fetchone()["n"]
    assert n == 1


def test_b3_post_lesson_log_with_preexisting_duplicates_updates_newest(client, db, world):
    older = db.execute(
        "INSERT INTO lesson_logs (schedule_id, date, status, updated_at) VALUES (%s, %s, 'مؤجلة', now() - interval '1 day') RETURNING id",
        (world.sched_b, TODAY),
    ).fetchone()["id"]
    newer = db.execute(
        "INSERT INTO lesson_logs (schedule_id, date, status) VALUES (%s, %s, 'مؤجلة') RETURNING id",
        (world.sched_b, TODAY),
    ).fetchone()["id"]
    db.commit()

    res = client.post(f"{ME}/lesson-logs", json=_log_body(world.sched_b, status="ملغاة"), headers=world.teacher)

    assert res.status_code == 200, res.text
    assert res.json()["id"] == str(newer)
    assert db.execute("SELECT status FROM lesson_logs WHERE id = %s", (older,)).fetchone()["status"] == "مؤجلة"
    n = db.execute("SELECT count(*) AS n FROM lesson_logs WHERE schedule_id = %s AND date = %s", (world.sched_b, TODAY)).fetchone()["n"]
    assert n == 2


def test_b3_post_lesson_log_outside_rooms_is_403(client, db, world):
    res = client.post(f"{ME}/lesson-logs", json=_log_body(world.sched_c), headers=world.teacher)
    assert res.status_code == 403, res.text
    assert res.json()["error"] == {"code": "forbidden", "message": "الحلقة خارج نطاق صلاحيتك"}
    assert db.execute("SELECT count(*) AS n FROM lesson_logs").fetchone()["n"] == 0


def test_b3_post_lesson_log_unknown_schedule_is_404(client, world):
    res = client.post(f"{ME}/lesson-logs", json=_log_body(str(uuid.uuid4())), headers=world.teacher)
    assert res.status_code == 404, res.text
    assert res.json()["error"]["code"] == "not_found"


def test_b3_post_lesson_log_bad_body_is_422(client, world):
    bad_status = client.post(f"{ME}/lesson-logs", json=_log_body(world.sched_b, status="حضر"), headers=world.teacher)
    assert bad_status.status_code == 422, bad_status.text
    assert bad_status.json()["error"]["code"] == "validation_error"
    bad_date = client.post(f"{ME}/lesson-logs", json={"schedule_id": world.sched_b, "date": "20-09-2026", "status": "تمت"}, headers=world.teacher)
    assert bad_date.status_code == 422, bad_date.text
    missing = client.post(f"{ME}/lesson-logs", json={"date": _iso(TODAY), "status": "تمت"}, headers=world.teacher)
    assert missing.status_code == 422, missing.text
```

- [ ] **Step 4: Run the whole new file, observe RED for b1–b3 and GREEN for b0**

Run: `.venv/Scripts/python.exe -m pytest tests/test_me_scope.py -q`
Expected: `4 passed, 33 failed` where every failure in `b1`–`b3` is `assert 404 == 200` / `404 == 403` / `404 == 422` (router not mounted). `test_b1_unauthenticated_profile_is_401` fails with `404 == 401`. No `ImportError`, no fixture error. If a *fixture* fails (e.g. a constraint violation while seeding), fix the helper now — the fleet cannot touch tests later.

- [ ] **Step 5: Run the full suite to confirm nothing pre-existing regressed**

Run: `.venv/Scripts/python.exe -m pytest tests -q --deselect tests/test_me_scope.py`
Expected: `63 passed`.

- [ ] **Step 6: Commit**

```bash
git add server/tests/test_me_scope.py
git commit -m "test(me): acceptance suite for /api/v1/me/* (Phase 5 batch 0b, RED by design)

Claude-Session: https://claude.ai/code/session_013pQQTqdgMWLQsTMvwHfZHZ"
```

---

## Task 1: Batch 1 — profile, students, rooms, schedule, notifications (OpenCode Worker B)

**Files:**
- Create: `server/edutrack_api/repositories/me_repo.py`
- Create: `server/edutrack_api/routers/me.py`
- Modify: `server/edutrack_api/main.py:14,56`

**Interfaces:**
- Consumes: `Scope`, `require_scope` from Task 0-A; `current_user`, `get_conn`, `ApiError`, `row_to_json`, `load_settings`.
- Produces:
  - `me_repo.Rows = tuple[list[dict], int]`
  - `me_repo._run(conn, sql, params, limit, offset) -> Rows`, `me_repo._opt(where, params, filters, key, clause)`, `me_repo._select(cols, from_, where, order_by) -> str`, `me_repo._student_where(scope, alias) -> tuple[list[str], dict]`, `me_repo._CHILD_ROOMS` — reused by Tasks 2 and 3.
  - `me_repo.profile(conn, scope, user) -> dict`, `list_students`, `list_rooms`, `list_schedule`, `list_notifications` all `(conn, scope, filters: dict, limit: int, offset: int) -> Rows`; `mark_notification_read(conn, scope, notification_id: UUID) -> dict | None`.
  - `me.router` (`APIRouter(prefix="/me")`), helpers `me._page(limit, offset)`, `me._envelope(rows, total, limit, offset) -> dict`, `me._only(scope, role)`.

- [ ] **Step 1: Create `me_repo.py` with the shared helpers and batch-1 functions**

```python
"""SQL for /api/v1/me/* (PHASE5_SPEC §2–§4).

Every list function is bounded by the caller's Scope: client-supplied ids can only
narrow the result. All lists return (rows, total) with a deterministic
ORDER BY … , id so offset paging never skips or repeats (spec §4.2).
"""

from __future__ import annotations

from uuid import UUID

from edutrack_api.scope import Scope
from edutrack_api.services.settings import load_settings

Rows = tuple[list[dict], int]

# Rooms of the guardian's children (students.room_id may be NULL).
_CHILD_ROOMS = "(SELECT s.room_id FROM students s WHERE s.id = ANY(%(student_ids)s) AND s.room_id IS NOT NULL)"

_STUDENT_BASE_COLS = (
    "s.id, s.name, s.birth_date, s.nationality, s.gender, s.room_id, r.name AS room_name, "
    "s.group_name, s.status, s.has_difficulties"
)
_STUDENT_TEACHER_COLS = _STUDENT_BASE_COLS + ", s.difficulty_notes, s.child_notes, s.guardian_phone, s.guardian_relation"

_SCHEDULE_FROM = (
    "schedules sc JOIN rooms r ON r.id = sc.room_id "
    "LEFT JOIN users u ON u.id = sc.teacher_user_id LEFT JOIN staff st ON st.id = u.staff_id"
)


# ---- helpers ---------------------------------------------------------------


def _select(cols: str, from_: str, where: list[str], order_by: str) -> str:
    return (
        f"SELECT {cols}, COUNT(*) OVER() AS _total FROM {from_} "
        f"WHERE {' AND '.join(where)} ORDER BY {order_by} LIMIT %(limit)s OFFSET %(offset)s"
    )


def _run(conn, sql: str, params: dict, limit: int, offset: int) -> Rows:
    rows = conn.execute(sql, {**params, "limit": limit, "offset": offset}).fetchall()
    if not rows:
        return [], 0
    total = rows[0]["_total"]
    for row in rows:
        row.pop("_total", None)
    return rows, total


def _opt(where: list[str], params: dict, filters: dict, key: str, clause: str) -> None:
    """Append `clause` (which references %(key)s) when filters[key] is present."""
    value = filters.get(key)
    if value is not None and value != "":
        where.append(clause)
        params[key] = value


def _student_where(scope: Scope, alias: str) -> tuple[list[str], dict]:
    return (
        [f"{alias}.deleted_at IS NULL", f"{alias}.student_id = ANY(%(student_ids)s)"],
        {"student_ids": list(scope.student_ids)},
    )


# ---- batch 1: profile, students, rooms, schedule, notifications -------------


def profile(conn, scope: Scope, user: dict) -> dict:
    name = None
    if scope.role == "teacher" and user.get("staff_id"):
        row = conn.execute("SELECT name FROM staff WHERE id = %s AND deleted_at IS NULL", (user["staff_id"],)).fetchone()
        name = row["name"] if row else None
    elif scope.role == "guardian":
        row = conn.execute("SELECT name FROM guardians WHERE id = %s AND deleted_at IS NULL", (user["guardian_id"],)).fetchone()
        name = row["name"] if row else None
    settings = load_settings(conn)
    return {
        "user": {"id": user["id"], "username": user["username"], "role": scope.role, "name": name or user["username"]},
        "scope": {"room_ids": sorted(scope.room_ids, key=str), "student_ids": sorted(scope.student_ids, key=str)},
        "center": {"name": settings["center_name"], "phone": settings["center_phone"]},
    }


def list_students(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    cols = _STUDENT_TEACHER_COLS if scope.role == "teacher" else _STUDENT_BASE_COLS
    where = ["s.deleted_at IS NULL", "s.id = ANY(%(student_ids)s)"]
    params: dict = {"student_ids": list(scope.student_ids)}
    _opt(where, params, filters, "student_id", "s.id = %(student_id)s")
    _opt(where, params, filters, "status", "s.status = %(status)s")
    return _run(conn, _select(cols, "students s LEFT JOIN rooms r ON r.id = s.room_id", where, "s.name, s.id"), params, limit, offset)


def list_rooms(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.room_ids:
        return [], 0
    where = ["r.deleted_at IS NULL", "r.id = ANY(%(room_ids)s)"]
    return _run(conn, _select("r.*", "rooms r", where, "r.name, r.id"), {"room_ids": list(scope.room_ids)}, limit, offset)


def list_schedule(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    where = ["sc.deleted_at IS NULL"]
    if scope.role == "teacher":
        if not scope.room_ids:
            return [], 0
        where.append("sc.room_id = ANY(%(room_ids)s)")
        params: dict = {"room_ids": list(scope.room_ids)}
    else:
        if not scope.student_ids:
            return [], 0
        where.append(f"sc.room_id IN {_CHILD_ROOMS}")
        params = {"student_ids": list(scope.student_ids)}
    _opt(where, params, filters, "day", "sc.day = %(day)s")
    cols = "sc.*, r.name AS room_name, COALESCE(st.name, u.username) AS teacher_name"
    return _run(conn, _select(cols, _SCHEDULE_FROM, where, "sc.day, sc.start_time, sc.id"), params, limit, offset)


def list_notifications(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    where = ["n.deleted_at IS NULL", "n.user_id = %(user_id)s"]
    params: dict = {"user_id": scope.user_id}
    unread = filters.get("unread")
    if unread is True:
        where.append("n.read_at IS NULL")
    elif unread is False:
        where.append("n.read_at IS NOT NULL")
    return _run(conn, _select("n.*", "notifications n", where, "n.created_at DESC, n.id"), params, limit, offset)


def mark_notification_read(conn, scope: Scope, notification_id: UUID) -> dict | None:
    return conn.execute(
        "UPDATE notifications SET read_at = COALESCE(read_at, now()), updated_at = now() "
        "WHERE id = %s AND user_id = %s AND deleted_at IS NULL RETURNING *",
        (notification_id, scope.user_id),
    ).fetchone()
```

- [ ] **Step 2: Create `routers/me.py` with helpers and the batch-1 handlers**

```python
"""/api/v1/me — row-scoped surface for the teacher and guardian mobile apps (PHASE5_SPEC §2).

Handlers are thin: dependency (scope + user) → query-param validation → repo → row_to_json.
Manager/supervisor are rejected by require_scope (403); role mismatches per route → 403.
"""

from __future__ import annotations

import datetime as dt
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from edutrack_api.auth import current_user
from edutrack_api.db import get_conn
from edutrack_api.errors import ApiError
from edutrack_api.repositories import me_repo
from edutrack_api.scope import Scope, require_scope
from edutrack_api.serializers import row_to_json

router = APIRouter(prefix="/me")
_SCOPE = Depends(require_scope)
_USER = Depends(current_user)
_CONN = Depends(get_conn)


def _page(limit: int, offset: int) -> None:
    if not 1 <= limit <= 500 or offset < 0:
        raise ApiError(422, "validation_error", "قيم التصفح غير صحيحة")


def _envelope(rows: list[dict], total: int, limit: int, offset: int) -> dict:
    return {"items": [row_to_json(r) for r in rows], "total": total, "limit": limit, "offset": offset}


def _only(scope: Scope, role: str) -> None:
    if scope.role != role:
        raise ApiError(403, "forbidden")


# ---- batch 1 ---------------------------------------------------------------


@router.get("/profile")
def get_profile(scope: Scope = _SCOPE, user: dict = _USER, conn=_CONN) -> dict:
    return row_to_json(me_repo.profile(conn, scope, user))


@router.get("/students")
def list_students(
    limit: int = 100,
    offset: int = 0,
    student_id: UUID | None = None,
    status: str | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _page(limit, offset)
    rows, total = me_repo.list_students(conn, scope, {"student_id": student_id, "status": status}, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/rooms")
def list_rooms(limit: int = 100, offset: int = 0, scope: Scope = _SCOPE, conn=_CONN) -> dict:
    _only(scope, "teacher")
    _page(limit, offset)
    rows, total = me_repo.list_rooms(conn, scope, {}, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/schedule")
def list_schedule(limit: int = 100, offset: int = 0, day: str | None = None, scope: Scope = _SCOPE, conn=_CONN) -> dict:
    _page(limit, offset)
    rows, total = me_repo.list_schedule(conn, scope, {"day": day}, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/notifications")
def list_notifications(limit: int = 100, offset: int = 0, unread: bool | None = None, scope: Scope = _SCOPE, conn=_CONN) -> dict:
    _page(limit, offset)
    rows, total = me_repo.list_notifications(conn, scope, {"unread": unread}, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.post("/notifications/{notification_id}/read")
def read_notification(notification_id: UUID, scope: Scope = _SCOPE, conn=_CONN) -> dict:
    row = me_repo.mark_notification_read(conn, scope, notification_id)
    if row is None:
        raise ApiError(404, "not_found")
    return row_to_json(row)
```

(`dt`, `Literal`, `BaseModel` are imported now so batch 3 only appends; ruff will flag them `F401` until batch 3 — that is expected and is the one "new" finding tolerated between batches 1 and 3. If you prefer zero noise, add `# noqa: F401` to those two import lines and remove the comment in batch 3.)

- [ ] **Step 3: Mount the router in `main.py`**

Change line 14 to:

```python
from edutrack_api.routers import attendance, auth, crud, importer, me, print, reports
```

Insert before `api.include_router(crud.router)` (line 56):

```python
    api.include_router(me.router)
```

- [ ] **Step 4 (Claude Code): audit the diff, run batch-1 tests + regression**

Run: `git diff --stat && file server/edutrack_api/routers/me.py server/edutrack_api/repositories/me_repo.py` (no CRLF)
Run: `.venv/Scripts/python.exe -m pytest tests/test_me_scope.py -q -k "b0 or b1"`
Expected: `20 passed` (4 b0 + 16 b1).
Run: `.venv/Scripts/python.exe -m pytest tests -q --deselect tests/test_me_scope.py`
Expected: `63 passed`.
Run: `uvx ruff check edutrack_api tests --statistics` → same 93 baseline classes (+ the tolerated `F401` on `me.py` if `noqa` was not used).

- [ ] **Step 5: Commit**

```bash
git add server/edutrack_api/repositories/me_repo.py server/edutrack_api/routers/me.py server/edutrack_api/main.py
git commit -m "feat(me): mount /api/v1/me — profile, students, rooms, schedule, notifications (Phase 5 batch 1)

Claude-Session: https://claude.ai/code/session_013pQQTqdgMWLQsTMvwHfZHZ"
```

---

## Task 2: Batch 2 — attendance, evaluations, assignments, submissions, lesson-logs GET, skill-progress (OpenCode Worker B)

**Files:**
- Modify: `server/edutrack_api/repositories/me_repo.py` (append)
- Modify: `server/edutrack_api/routers/me.py` (append)

**Interfaces:**
- Consumes: `_run`, `_opt`, `_select`, `_student_where`, `_CHILD_ROOMS`, `Rows` from Task 1; `_page`, `_envelope`, `_SCOPE`, `_CONN` from Task 1.
- Produces: `list_attendance`, `list_evaluations`, `list_assignments`, `list_submissions`, `list_lesson_logs`, `list_skill_progress` — all `(conn, scope, filters, limit, offset) -> Rows`.

- [ ] **Step 1: Append to `me_repo.py`**

```python
# ---- batch 2: attendance, evaluations, assignments, submissions, lesson-logs, skill-progress ----

_LESSON_LOG_COLS = (
    "l.id, l.branch_id, l.schedule_id, l.date, l.status, l.covered, l.homework, l.teacher_user_id, "
    "l.created_at, l.updated_at, sc.room_id, sc.day, sc.start_time, sc.end_time, sc.subject, r.name AS room_name"
)
_LESSON_FROM = "lesson_logs l JOIN schedules sc ON sc.id = l.schedule_id JOIN rooms r ON r.id = sc.room_id"

_SUBMISSION_FILES = (
    "(SELECT COALESCE(json_agg(json_build_object('id', f.id, 'storage_key', f.storage_key, "
    "'width', f.width, 'height', f.height) ORDER BY f.created_at, f.id), '[]'::json) "
    "FROM submission_files f WHERE f.submission_id = sb.id AND f.deleted_at IS NULL) AS files"
)

# student ids linked to an assignment, intersected with the caller's scope
_ASSIGNMENT_STUDENTS = (
    "(SELECT COALESCE(array_agg(x.student_id ORDER BY x.student_id), '{}') FROM assignment_students x "
    "WHERE x.assignment_id = a.id AND x.deleted_at IS NULL AND x.student_id = ANY(%(student_ids)s)) AS student_ids"
)


def list_attendance(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    where, params = _student_where(scope, "a")
    _opt(where, params, filters, "student_id", "a.student_id = %(student_id)s")
    _opt(where, params, filters, "date_from", "a.date >= %(date_from)s")
    _opt(where, params, filters, "date_to", "a.date <= %(date_to)s")
    sql = _select("a.*, s.name AS student_name", "student_attendance a JOIN students s ON s.id = a.student_id", where, "a.date DESC, a.id")
    return _run(conn, sql, params, limit, offset)


def list_evaluations(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    where, params = _student_where(scope, "e")
    _opt(where, params, filters, "student_id", "e.student_id = %(student_id)s")
    _opt(where, params, filters, "eval_type", "e.eval_type = %(eval_type)s")
    _opt(where, params, filters, "date_from", "e.date >= %(date_from)s")
    _opt(where, params, filters, "date_to", "e.date <= %(date_to)s")
    sql = _select("e.*, s.name AS student_name", "evaluations e JOIN students s ON s.id = e.student_id", where, "e.date DESC, e.id")
    return _run(conn, sql, params, limit, offset)


def list_assignments(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    where = ["a.deleted_at IS NULL"]
    params: dict = {"student_ids": list(scope.student_ids)}
    narrow = ""
    if filters.get("student_id") is not None:
        narrow = " AND x.student_id = %(student_id)s"
        params["student_id"] = filters["student_id"]
    linked = (
        "EXISTS (SELECT 1 FROM assignment_students x WHERE x.assignment_id = a.id AND x.deleted_at IS NULL "
        f"AND x.student_id = ANY(%(student_ids)s){narrow})"
    )
    if scope.role == "teacher" and not narrow:
        where.append(f"(a.teacher_user_id = %(user_id)s OR {linked})")
        params["user_id"] = scope.user_id
    else:
        where.append(linked)
    _opt(where, params, filters, "due_from", "a.due_date >= %(due_from)s")
    _opt(where, params, filters, "due_to", "a.due_date <= %(due_to)s")
    sql = _select(f"a.*, {_ASSIGNMENT_STUDENTS}", "assignments a", where, "a.due_date DESC, a.id")
    return _run(conn, sql, params, limit, offset)


def list_submissions(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    where, params = _student_where(scope, "sb")
    _opt(where, params, filters, "assignment_id", "sb.assignment_id = %(assignment_id)s")
    _opt(where, params, filters, "student_id", "sb.student_id = %(student_id)s")
    sql = _select(
        f"sb.*, a.title AS assignment_title, s.name AS student_name, {_SUBMISSION_FILES}",
        "submissions sb JOIN assignments a ON a.id = sb.assignment_id JOIN students s ON s.id = sb.student_id",
        where,
        "sb.submitted_at DESC, sb.id",
    )
    return _run(conn, sql, params, limit, offset)


def list_lesson_logs(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    where = ["l.deleted_at IS NULL"]
    if scope.role == "teacher":
        if not scope.room_ids:
            return [], 0
        where.append("(l.teacher_user_id = %(user_id)s OR sc.room_id = ANY(%(room_ids)s))")
        params: dict = {"user_id": scope.user_id, "room_ids": list(scope.room_ids)}
        cols = _LESSON_LOG_COLS + ", l.notes"  # internal notes: teacher only (§3.4)
    else:
        if not scope.student_ids:
            return [], 0
        where.append(f"sc.room_id IN {_CHILD_ROOMS}")
        params = {"student_ids": list(scope.student_ids)}
        cols = _LESSON_LOG_COLS
    _opt(where, params, filters, "schedule_id", "l.schedule_id = %(schedule_id)s")
    _opt(where, params, filters, "date_from", "l.date >= %(date_from)s")
    _opt(where, params, filters, "date_to", "l.date <= %(date_to)s")
    return _run(conn, _select(cols, _LESSON_FROM, where, "l.date DESC, l.id"), params, limit, offset)


def list_skill_progress(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    where, params = _student_where(scope, "sp")
    _opt(where, params, filters, "student_id", "sp.student_id = %(student_id)s")
    _opt(where, params, filters, "subject", "sp.subject = %(subject)s")
    sql = _select("sp.*, s.name AS student_name", "skill_progress sp JOIN students s ON s.id = sp.student_id", where, "sp.date DESC, sp.id")
    return _run(conn, sql, params, limit, offset)
```

- [ ] **Step 2: Append to `routers/me.py`**

```python
# ---- batch 2 ---------------------------------------------------------------


@router.get("/attendance")
def list_attendance(
    limit: int = 100,
    offset: int = 0,
    student_id: UUID | None = None,
    date_from: dt.date | None = None,
    date_to: dt.date | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _page(limit, offset)
    filters = {"student_id": student_id, "date_from": date_from, "date_to": date_to}
    rows, total = me_repo.list_attendance(conn, scope, filters, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/evaluations")
def list_evaluations(
    limit: int = 100,
    offset: int = 0,
    student_id: UUID | None = None,
    eval_type: str | None = None,
    date_from: dt.date | None = None,
    date_to: dt.date | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _page(limit, offset)
    filters = {"student_id": student_id, "eval_type": eval_type, "date_from": date_from, "date_to": date_to}
    rows, total = me_repo.list_evaluations(conn, scope, filters, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/assignments")
def list_assignments(
    limit: int = 100,
    offset: int = 0,
    student_id: UUID | None = None,
    due_from: dt.date | None = None,
    due_to: dt.date | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _page(limit, offset)
    filters = {"student_id": student_id, "due_from": due_from, "due_to": due_to}
    rows, total = me_repo.list_assignments(conn, scope, filters, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/submissions")
def list_submissions(
    limit: int = 100,
    offset: int = 0,
    assignment_id: UUID | None = None,
    student_id: UUID | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _page(limit, offset)
    filters = {"assignment_id": assignment_id, "student_id": student_id}
    rows, total = me_repo.list_submissions(conn, scope, filters, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/lesson-logs")
def list_lesson_logs(
    limit: int = 100,
    offset: int = 0,
    schedule_id: UUID | None = None,
    date_from: dt.date | None = None,
    date_to: dt.date | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _page(limit, offset)
    filters = {"schedule_id": schedule_id, "date_from": date_from, "date_to": date_to}
    rows, total = me_repo.list_lesson_logs(conn, scope, filters, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/skill-progress")
def list_skill_progress(
    limit: int = 100,
    offset: int = 0,
    student_id: UUID | None = None,
    subject: str | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _page(limit, offset)
    rows, total = me_repo.list_skill_progress(conn, scope, {"student_id": student_id, "subject": subject}, limit, offset)
    return _envelope(rows, total, limit, offset)
```

- [ ] **Step 3 (Claude Code): audit + run**

Run: `.venv/Scripts/python.exe -m pytest tests/test_me_scope.py -q -k "b0 or b1 or b2"`
Expected: `27 passed` (4 b0 + 16 b1 + 7 b2).
Run: `.venv/Scripts/python.exe -m pytest tests -q --deselect tests/test_me_scope.py` → `63 passed`.
Run: `uvx ruff check edutrack_api tests --statistics` → no new class.
Audit points: every `list_*` starts with the empty-scope guard; every WHERE contains `= ANY(%(student_ids)s)` or `= ANY(%(room_ids)s)` / `IN _CHILD_ROOMS`; guardian lesson-log column list has no `l.notes`; no f-string interpolates a client value (only the fixed `narrow` clause with a `%(student_id)s` placeholder).

- [ ] **Step 4: Commit**

```bash
git add server/edutrack_api/repositories/me_repo.py server/edutrack_api/routers/me.py
git commit -m "feat(me): attendance, evaluations, assignments, submissions, lesson-logs, skill-progress reads (Phase 5 batch 2)

Claude-Session: https://claude.ai/code/session_013pQQTqdgMWLQsTMvwHfZHZ"
```

---

## Task 3: Batch 3 — installments, receipts, `POST /me/lesson-logs` (OpenCode Worker B)

**Files:**
- Modify: `server/edutrack_api/repositories/me_repo.py` (append + one import)
- Modify: `server/edutrack_api/routers/me.py` (append)

**Interfaces:**
- Consumes: helpers from Task 1; `write_audit`, `ApiError`.
- Produces: `list_installments`, `list_receipts` `(conn, scope, filters, limit, offset) -> Rows`; `upsert_lesson_log(conn, scope, body: dict) -> dict` where `body` has keys `schedule_id: UUID, date: dt.date, status: str, covered|homework|notes: str | None`; constant `MSG_ROOM_OUT_OF_SCOPE`; pydantic `LessonLogIn` in `me.py`.

- [ ] **Step 1: Append to `me_repo.py`**

Add to the imports at the top of the file:

```python
from edutrack_api.audit import write_audit
from edutrack_api.errors import ApiError
```

Append:

```python
# ---- batch 3: installments, receipts, POST lesson-logs ----------------------

MSG_ROOM_OUT_OF_SCOPE = "الحلقة خارج نطاق صلاحيتك"


def list_installments(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    where = ["i.deleted_at IS NULL", "fp.deleted_at IS NULL", "fp.student_id = ANY(%(student_ids)s)"]
    params: dict = {"student_ids": list(scope.student_ids)}
    _opt(where, params, filters, "student_id", "fp.student_id = %(student_id)s")
    _opt(where, params, filters, "status", "i.status = %(status)s")
    cols = "i.*, fp.student_id, s.name AS student_name, fp.total_amount AS plan_total, fp.count AS plan_count"
    from_ = "installments i JOIN fee_plans fp ON fp.id = i.fee_plan_id JOIN students s ON s.id = fp.student_id"
    return _run(conn, _select(cols, from_, where, "i.due_date, i.id"), params, limit, offset)


def list_receipts(conn, scope: Scope, filters: dict, limit: int, offset: int) -> Rows:
    if not scope.student_ids:
        return [], 0
    where = ["rc.deleted_at IS NULL", "p.deleted_at IS NULL", "p.student_id = ANY(%(student_ids)s)"]
    params: dict = {"student_ids": list(scope.student_ids)}
    _opt(where, params, filters, "student_id", "p.student_id = %(student_id)s")
    cols = "rc.*, p.student_id, p.installment_id, p.amount, p.method, p.paid_on, s.name AS student_name"
    from_ = "receipts rc JOIN payments p ON p.id = rc.payment_id JOIN students s ON s.id = p.student_id"
    return _run(conn, _select(cols, from_, where, "rc.issued_on DESC, rc.id"), params, limit, offset)


def upsert_lesson_log(conn, scope: Scope, body: dict) -> dict:
    """SELECT-then-write on (schedule_id, date); no unique index exists (§3.2).

    Pre-existing duplicates are tolerated: the most recently updated open row wins.
    """
    sched = conn.execute(
        "SELECT room_id, branch_id FROM schedules WHERE id = %s AND deleted_at IS NULL",
        (body["schedule_id"],),
    ).fetchone()
    if sched is None:
        raise ApiError(404, "not_found")
    if sched["room_id"] not in scope.room_ids:
        raise ApiError(403, "forbidden", MSG_ROOM_OUT_OF_SCOPE)

    existing = conn.execute(
        "SELECT id FROM lesson_logs WHERE schedule_id = %s AND date = %s AND deleted_at IS NULL "
        "ORDER BY updated_at DESC, id LIMIT 1",
        (body["schedule_id"], body["date"]),
    ).fetchone()
    params = {
        "schedule_id": body["schedule_id"],
        "date": body["date"],
        "status": body["status"],
        "covered": body.get("covered"),
        "homework": body.get("homework"),
        "notes": body.get("notes"),
        "user_id": scope.user_id,
        "branch_id": sched["branch_id"],
    }
    if existing:
        row = conn.execute(
            "UPDATE lesson_logs SET status = %(status)s, covered = %(covered)s, homework = %(homework)s, "
            "notes = %(notes)s, teacher_user_id = %(user_id)s, updated_at = now() "
            "WHERE id = %(id)s RETURNING *",
            {**params, "id": existing["id"]},
        ).fetchone()
    else:
        row = conn.execute(
            "INSERT INTO lesson_logs (branch_id, schedule_id, date, status, covered, homework, notes, teacher_user_id) "
            "VALUES (%(branch_id)s, %(schedule_id)s, %(date)s, %(status)s, %(covered)s, %(homework)s, %(notes)s, %(user_id)s) "
            "RETURNING *",
            params,
        ).fetchone()
    write_audit(
        conn,
        scope.user_id,
        "update",
        "lesson_logs",
        row["id"],
        {"schedule_id": str(body["schedule_id"]), "date": body["date"].isoformat(), "status": body["status"]},
    )
    return row
```

- [ ] **Step 2: Append to `routers/me.py`**

```python
# ---- batch 3 ---------------------------------------------------------------


class LessonLogIn(BaseModel):
    schedule_id: UUID
    date: dt.date
    status: Literal["تمت", "مؤجلة", "ملغاة"]  # chk_lesson_logs_status
    covered: str | None = None
    homework: str | None = None
    notes: str | None = None


@router.get("/installments")
def list_installments(
    limit: int = 100,
    offset: int = 0,
    student_id: UUID | None = None,
    status: str | None = None,
    scope: Scope = _SCOPE,
    conn=_CONN,
) -> dict:
    _only(scope, "guardian")
    _page(limit, offset)
    rows, total = me_repo.list_installments(conn, scope, {"student_id": student_id, "status": status}, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.get("/receipts")
def list_receipts(limit: int = 100, offset: int = 0, student_id: UUID | None = None, scope: Scope = _SCOPE, conn=_CONN) -> dict:
    _only(scope, "guardian")
    _page(limit, offset)
    rows, total = me_repo.list_receipts(conn, scope, {"student_id": student_id}, limit, offset)
    return _envelope(rows, total, limit, offset)


@router.post("/lesson-logs")
def post_lesson_log(body: LessonLogIn, scope: Scope = _SCOPE, conn=_CONN) -> dict:
    _only(scope, "teacher")
    return row_to_json(me_repo.upsert_lesson_log(conn, scope, body.model_dump()))
```

If `# noqa: F401` was added to the `dt` / `Literal, BaseModel` imports in Task 1, remove those comments now.

- [ ] **Step 3 (Claude Code): audit + full gate**

Run: `.venv/Scripts/python.exe -m pytest tests -q`
Expected: `100 passed` (63 existing + 37 in `test_me_scope.py`), ~60 s on embedded PG 16.
Run: `uvx ruff check edutrack_api tests --statistics` → only the pre-existing `B008` / `DTZ011` classes; `F401` gone.
Run: `git diff --stat HEAD~2 -- server/ && file server/edutrack_api/routers/me.py server/edutrack_api/repositories/me_repo.py` → LF only.
Audit points: `_only(scope, "guardian")` on both finance routes and `_only(scope, "teacher")` on the POST; `upsert_lesson_log` checks 404 before 403 (anti-probing: an unknown id and a foreign id are indistinguishable only if both were 404 — the spec chose 403 for out-of-room so the teacher gets an actionable message; the 404 for unknown is the default text); audit row written **after** the write inside the same transaction (`get_conn` commits on success, rolls back on exception).

- [ ] **Step 4: Commit + spec status line**

Edit `docs/PHASE5_SPEC.md` line 5 (`**Status**`) to:

```markdown
- **Status**: **[IMPLEMENTED — batches 0–3 [APPROVED] 2026-09-2N, commit `<hash>`; deploy pending]**
```

```bash
git add server/edutrack_api/repositories/me_repo.py server/edutrack_api/routers/me.py docs/PHASE5_SPEC.md
git commit -m "feat(me): installments, receipts, POST /me/lesson-logs upsert; Phase 5 (b) complete (batch 3)

Claude-Session: https://claude.ai/code/session_013pQQTqdgMWLQsTMvwHfZHZ"
```

---

## Task 4: Deploy hand-off (Ibrahim; Claude Code verifies publicly)

**Files:** none in the repo.

- [ ] **Step 1 (Ibrahim):** `git push` (clears every commit ahead of `origin/main`, including hotfix `2a63c50` if still unpushed).
- [ ] **Step 2 (Ibrahim):** `bash deploy/push.sh root@187.55.226.225 gheras.autovem.tech -i ~/.ssh/edutrack_deploy_key` — `deploy.sh` runs `up -d --build api`; no migration, no `v=` bump (no `web/` change). ~1 min of 502 on `/api/*`.
- [ ] **Step 3 (Ibrahim pastes back, Claude re-checks):**

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://gheras.autovem.tech/api/v1/health          # expect 200
curl -s -o /dev/null -w "%{http_code}\n" https://gheras.autovem.tech/api/v1/me/profile      # expect 401 → router mounted
```

- [ ] **Step 4 (Claude Code):** log the session in `CURRENT_STATE.md` §5n (commit hashes, test count, plan deviations 1–4 above) and mark B-5.1/B-5.2 as live if the hotfix shipped in the same push.

---

## Worker B dispatch prompt (Tasks 1–3)

Use verbatim, substituting `<N>` and the file list; the plan itself is the payload. One `opencode run` at a time; wait for Claude Code's `[APPROVED]` before the next batch.

```
opencode run -m opencode-go/muse-spark-1.3-contributor "You are implementing Task <N> of Clients/03_GHERAS_Center/edutrack_pro/docs/superpowers/plans/2026-09-20-phase5b-mobile-role-scoping.md. Read that Task in full first; the code blocks there are the exact content to produce. Touch ONLY these files: <files>. Write Python with LF line endings and UTF-8 (no BOM). Do not modify scope.py, crud.py, attendance.py, or anything under tests/. Do not run pytest, ruff, or any test command — Claude Code runs the gate. When done, print `git diff --stat` and stop."
```

Files per batch: Task 1 → `server/edutrack_api/repositories/me_repo.py server/edutrack_api/routers/me.py server/edutrack_api/main.py`; Task 2 → `server/edutrack_api/repositories/me_repo.py server/edutrack_api/routers/me.py`; Task 3 → same two files.

Pre-flight before dispatching: ≥ 4 GB free RAM, Docker Desktop not running, booter alive, `git status` clean at the previous batch's commit.
