# CURRENT_STATE.md — Autovemtech Fleet Master Handoff

> **Target Client**: `Clients/03_GHERAS_Center` (EduTrack Pro — Smart Educational Center)  
> **Master Orchestrator**: `Claude Code CLI` (Opus Max / Sonnet 5)  
> **Handoff Source**: `Antigravity IDE` (Interactive Cockpit & Visual Inspector)  
> **Timestamp**: 2026-09-16T12:55:00+03:00  
> **Last updated:** 2026-09-20 — Phase 3 closed: v=2.8 + migration 005 live on the VPS, Phase 3 **[APPROVED]** — Claude Code CLI  
> **VCS:** git at workspace root, branch `main`, HEAD `2778d82` — **level with `origin/main`, push pending: no**. Phase 1 = `fc071f6`, Phase 2 = `a28299b`, Phase 2.5 = `eabac22`, Phase 3 audit = `52ce581` + `6e2e434` + `469e280` (all [APPROVED]). **Working tree clean** (Ibrahim committed the §5d-approved tree at 08:22, superseding his earlier `keep`). Remote `origin` = https://github.com/ibrahimalkateb965-tech/EduTrackPro.git. Quarantine enforced by root `.gitignore` (Rule 8).  

---

## 1. Fleet Roles & Division of Labor (Strict Enforcement)

| Agent | Harness / Model | Role & Boundaries |
| :--- | :--- | :--- |
| **Claude Code CLI** | `claude` (Opus Max) | **MASTER ORCHESTRATOR & SOLE TESTING AUTHORITY**<br>• Drives the entire 8-hour autonomous sprint.<br>• Conducts architectural audits & Devil's Advocate reviews.<br>• **Exclusive Testing Monopoly**: Sole authority to run test suites and grant `[APPROVED]`.<br>• Delegates boilerplate to OpenCode to conserve Opus reasoning tokens. |
| **OpenCode CLI (Worker A)** | `opencode run -m opencode-go/glm-5.3-flash` | **Ultra-Fast Terminal & UI Scaffolding**<br>• Fast shell commands, scaffolding, Web/Compose layouts (NO testing). |
| **OpenCode CLI (Worker B)** | `opencode run -m opencode-go/muse-spark-1.3-contributor` | **Database & Data Architect**<br>• SQLite/Room schemas, DAOs, migrations, and data models (NO testing). |
| **Cline CLI (Worker C)** | `cline "[prompt]" --model glm-5.3-flash` | **Parallel UI Scaffolding**<br>• Runs concurrently with Worker A. Rapid terminal tasks, Act mode with Auto-approve. ($0.00) |
| **Cline CLI (Worker D)** | `cline "[prompt]" --model muse-spark-1.3-contributor` | **Parallel Data Architect**<br>• Redundancy for Worker B. Data logic, API routes, Act mode with Auto-approve. ($0.00) |
| **Codex CLI / Desktop** | `codex` (GPT-5.6 Soul) | **Algorithmic Logic**<br>• Camera homework grading logic, complex evaluation engines, and data parsers. |
| **Antigravity IDE** | Editor Cockpit (Gemini 3.8 Flash High) | **Interactive Human Cockpit & Arabic Inspector**<br>• Monitors execution and reads logs/diffs.<br>• Generates Arabic debriefs for the developer upon return.<br>• Does NOT orchestrate or run headless tasks. |

---

## 2. Active Mission: Gheras Center (EduTrack Pro Implementation & Design)

- **Approved Commercial & Technical Scope**: `@Clients/03_GHERAS_Center/06_Contracts_Invoices/GHERAS_GRAND_SLAM_OFFER.md` (Final 9-Page Grand Slam Offer [APPROVED]).
- **Source Template Asset**: `Clients/03_GHERAS_Center/تطبيق غراس.html` (Luna 5.6 MVP, 490 KB, 2,683 lines).
- **Brand Identity**: `#0f8b8d` (Teal), `#075f62` (Dark Teal), `#f57c00` (Orange), `#18343b` (Text), `#f4f8f8` (Bg).
- **Core Design Directive**:
  1. Complete, refine, verify, test, and secure the system created in `تطبيق غراس.html`.
  2. Embed Gheras Center official logo across all 11 printable documents (receipt vouchers, guardian cards, excellence certificates, student reports, schedules).
  3. Strict zero-leak policy: Never mention multi-tenant leasing or internal agency tooling to client deliverables.
  4. Context Protection: **NEVER** read `تطبيق غراس.html` in full (`mode='full'`); inspect chunked line ranges only.

### Planned Implementation Milestones:
1. **Milestone 1 — Data Architecture & Schema (OpenCode Muse Spark)**:
   - Extract entities (students, guardians, attendance, fees/installments, expenses, staff, rooms, assignments) via chunked inspection.
   - Generate production PostgreSQL schema (server) + Room/SQLite DAOs (mobile).
2. **Milestone 2 — Web Dashboard & 11 Printable Documents (OpenCode GLM 5.3 Flash)**:
   - Build responsive web dashboard (management only) adhering to brand palette.
   - Design high-fidelity RTL templates for the 11 printable documents with logo placeholders.
3. **Milestone 3 — Camera Homework Engine & Payments (Codex)**:
   - Camera image capture, compression, and grading algorithms.
   - Payment gateway integration logic (Mada / Apple Pay).
4. **Milestone 4 — Quality Audit & Test Sign-off (Claude Code EXCLUSIVE)**:
   - Audit all diffs, execute test suites, confirm zero lint/syntax errors, and grant formal `[APPROVED]`.

---

## 3. Autonomous Execution Directives for Claude Code

1. **Uninterrupted Autonomy**: When running with `--dangerously-skip-permissions`, execute milestones sequentially without waiting for user confirmation.
2. **Decision Protocol**: If an ambiguous technical trade-off arises, select the most scalable, production-grade pattern and document the rationale in `.agents/MEMORY_STORE.md`.
3. **Context Governance (Hook 27 & 25)**:
   - Maintain context within the Golden Sweet Spot (30k - 300k tokens).
   - If nearing context limits, execute `git commit`, log current milestone status below, and run `/compact`.

---

## 4. Phase 1 Execution Log (Claude Code — closed 2026-09-16T18:05+03:00)

| Milestone | Worker (actual) | Status | Verification (Claude Code exclusive) |
| :--- | :--- | :--- | :--- |
| Spec | Claude Code | ✅ | `edutrack_pro/docs/PHASE1_SPEC.md` — 34 MVP `data.*` collections → 43 tables; chunked reads only |
| M1 Data Architecture | OpenCode `glm-5.3` (Muse Spark blocked — ADR 22.5) | ✅ **[APPROVED]** | `001_schema.sql` parses (pglast, 149 stmts), 43/43 tables, pgcrypto/sequence/view/trigger/REVOKE; `sqlite/schema.sql` executes (15 tables); 15 Room entities = SQLite columns 1:1; 116/116 DAO queries compile. Claude patch: `updated_at` trigger loop scoped to tables having the column. |
| M2a Print Templates (11) | OpenCode `glm-5.3` in 3 batches | ✅ **[APPROVED]** | Exactly 11 templates; RTL/logo/palette/placeholders/well-formed HTML all pass; headless-Chrome renders of receipt + certificate visually verified. Claude patches: logo asset trimmed (45% white margin) + transparent bg; `.doc-logo` 128×72. |
| M2b Web Dashboard | Codex (4 files, then usage cap) + OpenCode `glm-5.3` (11 files, 4 batches) | ✅ **[APPROVED]** | 16 files; `node --check` all modules; import graph 0 problems; every view exports `render`; students form 23/23 fields; manager/supervisor gate + 401 handler present; no CDN; headless render of login gate verified. Claude patches: sidebar «القاعات» (MVP wording); shell chrome hidden while logged out (`body.auth-locked`). |
| M3 Homework Engine | Codex | ✅ **[APPROVED]** | `gradle clean test` (JDK 17, Gradle 9.4.1): **13/13 pass**. Claude patch: `EvaluationLevel` → English identifiers + Arabic `label`. Android `ImageCompressor.kt` static review only. |
| M4 Final Audit | Claude Code | ✅ **[APPROVED]** | `audit_phase1.py`: **28/28** checks; whole-tree scan: 0 forbidden words (tenant/SaaS/agency/Autovem/lease), 0 Eastern digits, 0 BOM. |

### Phase 1 verdict: **[APPROVED]** — 80 deliverable files under `Clients/03_GHERAS_Center/edutrack_pro/`.

### Not in Phase 1 scope (Phase 2 backlog)
1. REST API server implementing `PHASE1_SPEC.md §4` (dashboard + print templates are wired to `/api/v1` but no server exists yet).
2. Mustache rendering service for the 11 templates (field inventory in `web/print/NOTES.md`).
3. Android app module (Compose UI) wiring the Room layer + `homework-core`; Room compile with KSP not yet run.
4. Grants for the application DB role (audit_log is `REVOKE ... FROM PUBLIC`; explicit `GRANT INSERT/SELECT` needed).
5. MVP JSON backup importer (`gheras_simple_v1` → PostgreSQL) — the Day-10 data-migration promise depends on it.

### Blockers requiring the developer (Arabic debrief for Antigravity IDE)
1. **Muse Spark 1.3 Contributor** يحتاج موافقة صريحة على مشاركة البيانات من لوحة OpenCode Workspace قبل استخدامه في المسار B. استُخدم `opencode-go/glm-5.3` بديلاً في كل مهام OpenCode بنجاح.
2. **`glm-5.3-flash`** تعلّق مرتين بدون مخرجات — يُستبعد من مهام الملفات المتعددة حتى إشعار آخر.
3. **حصة Codex** نفدت أثناء الجلسة (تتجدد 7:26 مساءً).
4. **ضغط الذاكرة** (16 GB، ~3 GB حرة): يُنصح بإغلاق Chrome/ChatGPT أثناء تشغيل الأسطول. Gradle مثبّت على Xmx400m في `homework-core/gradle.properties`.
5. **إضافة Claude in Chrome** غير متصلة — التحقق البصري تم عبر Chrome headless.

### Environment notes
- Fleet logs: `.agents/logs/gheras_*.log`. Audit harness: session scratchpad `audit_phase1.py` (re-creatable from CURRENT_STATE criteria above).
- Fleet rules learned this sprint: ADR 22 items 6–10 in `.agents/MEMORY_STORE.md`.

---

## 5. Phase 2 Execution Log (Claude Code — closed 2026-09-16, ~23:55 AST)

Scope chosen by Ibrahim: **(a)** REST API server (FastAPI + PostgreSQL) + `gheras_simple_v1` importer. Binding spec: `edutrack_pro/docs/PHASE2_SPEC.md` (sections 0-9). Worker directive: A = `glm-5.3-flash`, B = `muse-spark-1.3-contributor` (opt-in confirmed), C = Codex (`-s workspace-write`; the bypass flag is blocked by the auto-mode classifier).

| Batch | Worker (actual) | Files | Status | Verification (Claude Code exclusive) |
| :--- | :--- | :--- | :--- | :--- |
| B1 | OpenCode Muse Spark | `db.py`, `serializers.py`, `repositories/generic.py` (+`__init__`) | ✅ | Claude patch: `ILIKE '%%'` placeholder escaping (psycopg 3). |
| C1 | Codex | `auth.py`, `audit.py`, `routers/auth.py`, `routers/crud.py`, `services/finance.py` | ✅ | Claude patches: sync endpoints (psycopg is blocking), plain `password` stripped from audit details, `InvalidHashError` handled for the seeded placeholder hash, stale `installment_id` in payment response, advance-deduction query (`FOR UPDATE` + `GROUP BY` is illegal; current month's run was double-counted), new `transfer_between_accounts` hook for the dashboard transfer form. |
| C2 | Codex | `services/words.py`, `routers/attendance.py`, `routers/reports.py`, `routers/print.py` | ✅ | Claude patches: typed `NULL` params (`%s::uuid IS NULL`), `to_char(%s::date)`, `Jsonb()` for `monthly_reports`, ambiguous `status` column, `top_absent` join, reserved alias `group`, Riyadh-aware "today", counted-noun agreement in `arabic_amount_words`. |
| A1 | OpenCode glm-5.3-flash | `pyproject.toml`, `.env.example`, `docker-compose.yml`, `README.md` | ✅ | Claude patch: compose `api` service points `DATABASE_URL` at the `db` container. Flash answered in < 60 s with 4 files (Small-Batch Rule holds). |
| A2 | OpenCode glm-5.3-flash | `__init__.py`, `config.py`, `errors.py`, `main.py` | ✅ | No patches. |
| B2 | OpenCode Muse Spark | `importer/` (3 files, 75 KB), `db/postgres/003_phase2.sql` | ✅ | Claude patch: derived guardian / fee-plan rows reported under `students.guardians` / `students.fee_plans`. |
| Tests | Claude Code | `server/tests/` (conftest + 18 tests) | ✅ **18/18** | Real PostgreSQL 16 (embedded `pgserver`, `pgcrypto` line skipped — `gen_random_uuid()` is core), 001+002+003 applied, 003 applied twice (idempotent), `pyflakes` clean, 33 files scanned: 0 forbidden words, 0 Eastern digits, 0 BOM. |

### Phase 2 verdict: **[APPROVED]** — 37 new files (`server/` 34, `003_phase2.sql`, `PHASE2_SPEC.md`, tests). Dashboard form fields verified 1:1 against introspected columns (students 23/23).

### Governance fixes this session
- `.gitignore`: `docs/` → `/docs/` (the unanchored rule silently excluded `edutrack_pro/docs/PHASE1_SPEC.md`; root personal `docs/` stays ignored).
- CURRENT_STATE header now reflects HEAD `dc06d28` and push done.

### Not in Phase 2 scope (Phase 3 backlog)
1. Mobile scoping for `teacher` / `guardian` roles (currently 403 on all web CRUD; one `TODO Phase 3` marker in `routers/crud.py`).
2. Mustache rendering service for the 11 templates (the `print/*` endpoints return the field JSON; rendering to PDF is still manual).
3. Android app module (Compose UI) wiring the Room layer + `homework-core`; Room/KSP compile.
4. Rate limiting / refresh tokens; `revoked_tokens` purge job.
5. `docker-compose.yml` smoke-run on the VPS (Docker daemon was down locally; compose file is unexecuted).

### Blockers requiring the developer
1. Docker Desktop was not running (RAM ~3.6 GB free) — production compose untested; tests used embedded Postgres instead.
2. `codex exec --dangerously-bypass-approvals-and-sandbox` is blocked by the Claude auto-mode classifier; `-s workspace-write --skip-git-repo-check` works.
3. `git push` remains Ibrahim's action.

---

## 5b. Phase 2.5 — Clean VPS Deployment Package (Claude Code — closed 2026-09-17, ~01:15 AST)

**Scope clarification from the client (2026-09-16):** no legacy data migration. EduTrack Pro starts as a **100% clean fresh instance**; the `gheras_simple_v1` importer stays in the codebase but is **not** part of the deployment path.

Delivered under `Clients/03_GHERAS_Center/edutrack_pro/` (written by Claude Code directly — deploy scripts are security-sensitive and would have needed a full audit anyway):

| File | Purpose |
| :--- | :--- |
| `deploy/deploy.sh` | Idempotent VPS-side deploy: secrets → db → migration check (001→003, ≥ 43 tables, admin seed row) → `gheras_app` password → API build → non-interactive admin seed → host-Caddy site install + `caddy validate` + reload (bundled `--profile edge` fallback) → HTTPS smoke (health, dashboard 200, `server/` 404, admin login 200). |
| `deploy/push.sh` | Developer-side: `tar` the `assets db server web deploy` tree to `/opt/edutrack` over SSH, then run `deploy.sh`. |
| `deploy/docker-compose.prod.yml` | `db` (no published port, **TCP** healthcheck so init scripts finish before "healthy"), `api` (loopback `127.0.0.1:8000`, healthcheck on `/api/v1/health`), optional `caddy` profile. |
| `deploy/Caddyfile.gheras` | Site block template: `/api/*` → uvicorn; static allow-list `/web/*` + `/assets/*`; `*.md`, `db/`, `server/`, `deploy/` → 404; security headers; `/` → `/web/dashboard/`. |
| `deploy/DEPLOY.md`, `deploy/.env.prod.example` | Runbook (pre-reqs, deploy, day-2 ops) and documented env shape. |
| `server/Dockerfile`, `server/.dockerignore`, `server/uv.lock` | `python:3.12-slim` + `uv sync --frozen --no-dev`, non-root user. The old compose command needed a lockfile that did not exist — now generated and committed. |
| `server/docker-compose.yml` | Dev compose now builds from the Dockerfile (`--reload`, source bind-mount). |
| `server/edutrack_api/importer/__main__.py` | `--set-admin-password` reads `EDUTRACK_ADMIN_PASSWORD` when set (deploy.sh / CI); TTY prompts unchanged. |
| `server/tests/test_importer.py` | +1 test: placeholder hash → 401, short password rejected, env-driven set → argon2id hash → login 200. |

### Verification (Claude Code exclusive)
- `pytest`: **19/19** with the API connecting as the restricted **`gheras_app`** role (Phase 2 tests had only run as superuser) — grants from `003` are sufficient for every endpoint, `audit_log` insert and `revoked_tokens` included.
- `pyflakes` clean; new files: 0 BOM, 0 Eastern digits, 0 forbidden words, LF only.
- `docker compose config` valid for `db` + `api` + `caddy` (edge profile).
- Caddy 2.10 `validate` on the rendered site block: **Valid configuration**.
- Local end-to-end with real Caddy + real uvicorn + embedded PostgreSQL 16: `/` → 302 `/web/dashboard/`; dashboard, JS, CSS, logo, print template + `print.css` → 200 with correct MIME; `NOTES.md`, `server/pyproject.toml`, `db/…sql`, `deploy/deploy.sh`, `/web/../server/…` → **404**; security headers present. Clean seed flow: placeholder hash → login 401 → `EDUTRACK_ADMIN_PASSWORD=… --set-admin-password` → login 200 → all 19 dashboard endpoints 200 (`attendance/students` is POST-only → 405 on GET, correct) → logout 204 → token revoked (401). Headless-Chrome render of the login gate through Caddy verified (logo + RTL + brand palette).
- **Not executed:** the actual VPS run. Docker Desktop locally starves the machine (1.0 GB free with it up) — the compose stack was validated, not run.

### Blockers requiring the developer (Arabic debrief for Antigravity IDE)
1. **مفتاح SSH غير مسجل على الخادم**: المفتاحان المحليان (`vps_secure_key` و `id_ed25519`) مرفوضان (`Permission denied (publickey)`) على `187.55.226.225`. يلزم إضافة المفتاح العام عبر لوحة Hostinger (VPS → SSH keys) أو الطرفية داخل المتصفح.
2. **سجل DNS للاسم العام غير موجود**: `autovem.tech` يشير إلى الخادم لكن `gheras.autovem.tech` بلا سجل A. اختيار الاسم النهائي قرار إبراهيم (يفضّل نطاق العميل الخاص إن وُجد؛ `deploy.sh` يقبل أي اسم).
3. **المنفذ 443 لا يستجيب من الخارج** رغم أن Caddy المضيف يرد على 80 بتحويل 308 إلى HTTPS — يلزم فتح 443 في جدار حماية Hostinger قبل إصدار الشهادة.
4. `git push` يبقى إجراء إبراهيم.

---

## 5c. Phase 3 — Dashboard hardening & live VPS (2026-09-17 → 2026-09-20)

Commits after the Phase 2.5 freeze (`git log eabac22..1ff5520`, subjects verbatim; verification is recorded only where the commit subject or this file says so):

| Commit | Subject |
| :--- | :--- |
| `d11095e` | chore(state): record Phase 2.5 commit eabac22 and pending push in CURRENT_STATE.md |
| `98b8c50` | fix(deploy): interpolate GHERAS_APP_PASSWORD directly in psql role alter statement |
| `2c74f4b` | fix(deploy): use chown -R on /var/log/caddy for caddy log permissions |
| `bcf4d21` | feat(gheras): Dashboard Phase 3 integration and UI fixes **[APPROVED]** |
| `e6039b3` | feat(gheras): activate dashboard buttons, granular supervisor permissions, and audit fixes |
| `9b6ea54` | fix(gheras): fix male name gender heuristic for Hamzah, route tab navigation, and add gender filter to attendance |
| `fd63736` | feat(gheras): prominent gender selection in student form with english separation tabs |
| `4f5f6c8` | fix(cache): append cache-busting v=1.1 to script imports |
| `a82c071` | fix(rbac): restrict supervisor permissions strictly across backend and frontend |
| `280ff16` | feat(edutrack): add JSON data import endpoint and user account deletion with live VPS deployment |
| `bb7e069` | feat(gheras): consolidate financial management into unified tab and add customizable quick access shortcuts |
| `1ff5520` | feat(gheras): add Enterprise Arabic DatePicker and fix student edit save button |

VPS facts proven by the tree: `deploy/DEPLOY.md` documents the live host `187.55.226.225` with key `~/.ssh/edutrack_deploy_key`, the base64 hot-fix pattern for single JS/HTML files, and the cache-version bump rule (`index.html` + `app.js`). Migration `db/postgres/004_phase3.sql` exists and applies cleanly after 001–003 on embedded PG 16 (verified 2026-09-20, 44 tables).

### Session 2026-09-20 — teacher real-name linking (Claude Code + OpenCode Worker B)

Delegate order (Hook 22): staff member `م/آية هويدي` (staff `20cf6983-c352-4f6f-86f5-611f5f725020`) must appear by her Arabic name in schedule selection and print.

| Step | Worker | Status | Verification (Claude Code exclusive) |
| :--- | :--- | :--- | :--- |
| `web/dashboard/js/views/rooms.js` — `Promise.all` fetch of `staff` + `users?role=teacher`; `teacherName()` resolves `user.staff_id → staff.name (role_title)`; `staff` reset on cleanup | OpenCode Muse Spark | ✅ uncommitted | `node --check` OK; worker touched only the 2 allowed files, ran no commands |
| `server/edutrack_api/routers/print.py` — `/print/schedule` joins `users` + `staff`, `COALESCE(st.name, u.username) AS teacher`, cell = `المادة — المعلم` | OpenCode Muse Spark | ✅ uncommitted | `py_compile` OK; `pytest tests/test_print.py` **14/14** on embedded PG 16 (001–004); direct probe: staff-linked user → Arabic name, unlinked → username, NULL → subject only |
| Cache bump `?v=2.7 → ?v=2.8` in `index.html` + `app.js` | Claude Code | ✅ uncommitted | `node --check app.js` OK |
| VPS: `UPDATE users SET deleted_at = NULL, is_active = true WHERE staff_id = '20cf6983-c352-4f6f-86f5-611f5f725020'` via `docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec -T db psql -U postgres -d gheras_edutrack` | Ibrahim | ⏳ **not run** | Auto-mode classifier blocks production SSH for Claude ("Production Reads") |
| VPS deploy `bash deploy/push.sh root@187.55.226.225 gheras.autovem.tech -i ~/.ssh/edutrack_deploy_key` | Ibrahim | ⏳ **not run** | `print.py` lives in the `api` container → full push + rebuild, not a base64 hot-fix |

Code verdict for the two delegated files: **[APPROVED]**. Deployment and DB reactivation are Ibrahim's actions.

### Uncommitted work kept by Ibrahim's explicit decision — `keep` (2026-09-20 07:31)

Ibrahim chose `keep` in `/strategic-clear`: the following survive `/clear` **uncommitted / untracked** and must be reviewed and committed (or discarded) by the next session before any new feature work. **Nothing below is in any commit.**

Tracked, modified (13 files, +767/−156, all under `Clients/03_GHERAS_Center/edutrack_pro/`):
- This session (audited, tests green): `web/dashboard/js/views/rooms.js` (teacher-name part only), `server/edutrack_api/routers/print.py` (`schedule` endpoint only), `web/dashboard/index.html`, `web/dashboard/js/app.js` (v=2.8).
- Earlier sessions, **not audited by Claude Code**: `rooms.js` (+500 lines: Saturday, multi-day period creation, schedule table), `server/edutrack_api/routers/crud.py` (+1), `web/dashboard/js/views/attendance.js`, `finance.js`, `home.js`, `reports.js`, `settings.js` (+140), `web/print/print.css` (+32), `web/print/templates/guardian_card.html` (card_no → academic_year, + national_id row; edited by another session on 2026-09-20 07:24 while OpenCode was running), `web/print/templates/schedule.html` (Saturday column).

Untracked, not ignored:
- `Clients/03_GHERAS_Center/edutrack_pro/web/dashboard/js/views/communication.js` — real project file, **unreviewed**.
- Root `changelog` and `models` — **0-byte stray files** created 2026-09-18 (shell artefacts); recommend deleting.
- Root `edutrack_update.tar.gz` — 14 MB deploy tarball from 2026-09-17; **must never be committed**; recommend deleting or adding `*.tar.gz` to `.gitignore`.

### Blockers / known defects requiring the developer
1. **Saturday is rejected by the database.** The uncommitted `rooms.js` / `print.py` / `schedule.html` add `السبت` to schedules, but `chk_schedules_day` (`db/postgres/001_schema.sql:185`) allows only Sun–Thu; any Saturday period insert fails with a check-constraint violation (reproduced on embedded PG 2026-09-20). Fix = new `db/postgres/005_saturday.sql`: `ALTER TABLE schedules DROP CONSTRAINT chk_schedules_day, ADD CONSTRAINT chk_schedules_day CHECK (day IN ('السبت','الأحد','الاثنين','الثلاثاء','الأربعاء','الخميس'));` + apply on the VPS + extend the `deploy.sh` migration check.
2. `git push` — `[ahead 3]` — Ibrahim's action.
3. Production SSH/DB commands cannot be run by Claude Code in auto mode (classifier: "Production Reads"); hand the exact command to Ibrahim (`!` prefix in the session).
4. The teacher-account reactivation SQL and the v=2.8 deploy (above) are still pending on the VPS.

---

## 5d. Session 2026-09-20 ~08:40 — Commit-hygiene audit (a) + Saturday migration (b) — Claude Code + OpenCode Worker B

Executed Ibrahim's combined (a)+(b) order. Tree hygiene: `models`, `changelog` (0-byte) and `edutrack_update.tar.gz` (14 MB) deleted; `*.tar.gz` added to root `.gitignore`.

| Item | Worker | Verification (Claude Code exclusive) |
| :--- | :--- | :--- |
| `db/postgres/005_saturday.sql` — `DROP CONSTRAINT IF EXISTS chk_schedules_day` + re-add with `السبت`; `deploy/deploy.sh` pre-flight list, empty-DB loop and incremental loop bumped to 005 | OpenCode Muse Spark | Embedded PG 16: 001→005 applied, **005 applied twice = no-op**, constraint exists exactly once, 44 tables; Saturday insert accepted, Friday still rejected. Claude patch: removed copied `Money: numeric` header line. |
| `deploy/deploy.sh` admin gate | Claude Code | `username='admin'` check replaced by `role='manager' AND is_active AND deleted_at IS NULL >= 1` — the new settings UI lets a manager delete the seeded `admin`, which would have made every later deploy die at the old gate. `bash -n` OK. |
| `rooms.js` (+536 audit) | Claude Code | Time-picker/multi-day/delete reviewed. Patches: unused `DAY_OPTIONS` removed; multi-day submit → `Promise.allSettled`, table reloads and failed days stay selected (retry no longer re-posts successful days). |
| `settings.js` (+140 audit) | Claude Code | Manager create/delete UI. Backend confirmed: `users` writes manager-only, self-delete blocked → ≥1 manager always survives. No patch. |
| `attendance.js`, `students.js` | Claude Code | Hard-coded `import('./communication.js?v=2.6')` (stale vs v=2.8, second module instance) → `${new URL(import.meta.url).search}` so the app.js cache version propagates. |
| `communication.js` (656 lines, new) | Claude Code | `messages` payload matches schema (channel/status CHECKs). Patches: `api.get('student_attendance')` (not a CRUD resource → silent 404, absences always 0) → `reports/attendance?from=&to=` last 90 days via shared `loadAttendance()`; phone fallback adds `father_phone`/`mother_phone`. **Mandatory commit**: HEAD `app.js` already routes `#/communication`. |
| `finance.js`, `reports.js`, `home.js`, `print.css`, `guardian_card.html`, `schedule.html`, `crud.py` (+`message-templates`), `print.py` | Claude Code | Reviewed, no patch. `print.py` supplies `sat`, `academic_year`, `national_id` (`SELECT s.*`). |
| Tests | Claude Code | `node --check` 14/14 modules; `py_compile` OK; **pytest 46/46** (fixtures as superuser, API as `gheras_app`). Two failures were pre-existing at HEAD: `test_role_matrix_for_supervisor` and `test_supervisor_staff_salary_masking_and_write_guard` still asserted supervisor writes to `rooms`/`staff` = 200, contradicting the strict RBAC commit `a82c071`; assertions aligned to 403. Hygiene: 17 changed files, 0 BOM, 0 CRLF, 0 Eastern digits, 0 forbidden words (only pre-existing `gheras.autovem.tech` hostname in deploy.sh usage comment). |

### Verdict: **[APPROVED]** for the whole working tree (20 modified + 2 new files).

### Notes, not blockers
- `guardian_card` `academic_year` is hard-coded `1447-1448 هـ` in `print.py:116` — no settings table exists; needs a manual bump each Hijri year (or a settings table later).
- `communication.js` `getGuardianName()` reads `guardian_name`, which is not a students column → always falls back to «ولي الأمر المحترم». Cosmetic.
- All views fetch `students` / `messages` with the default `limit=100`; pre-existing pattern across the dashboard, will truncate beyond 100 rows.
- `tests/test_supervisor_permissions.py:7` unused `make_user` import — pre-existing pyflakes note.

## 5e. Session 2026-09-20 — Phase 3 production deploy (Ibrahim executed; Claude Code verified from the public side)

Ibrahim ran the §6 one-shot after `git push` (7 commits, `main` now level with `origin/main`). Pasted results + Claude's own public HTTPS checks:

| Check | Source | Result |
| :--- | :--- | :--- |
| `push.sh` → `deploy.sh` | Ibrahim's paste | migrations through 005 applied; `edutrack-api:2` rebuilt and healthy; Caddy reloaded; dashboard 200, health 200, `server/` 404 |
| `chk_schedules_day` on prod DB | Ibrahim's paste | `CHECK ((day = ANY (ARRAY['السبت', 'الأحد', 'الاثنين', 'الثلاثاء', 'الأربعاء', 'الخميس'])))` — 005 confirmed live |
| Teacher reactivation (`staff_id 20cf6983-…`) | Ibrahim's paste | **`UPDATE 2`** (expected 1) — two `users` rows share that `staff_id`; both now active. Open item below. |
| `/api/v1/health`, `/server/pyproject.toml`, dashboard cache tag | Claude Code `curl` from the workstation | 200 / 404 / `v=2.8` ×2 |

### Phase 3 verdict: **[APPROVED]** — code (§5c/§5d) and production state agree; v=2.8 with teacher-name join and Saturday schedules is what the client sees.

### Open items (not blockers)
1. Duplicate user rows for `staff_id 20cf6983-…` — inspect with `SELECT id, username, role, is_active, deleted_at, created_at FROM users WHERE staff_id = '20cf6983-c352-4f6f-86f5-611f5f725020' ORDER BY created_at;` and soft-delete the stray one (`UPDATE users SET deleted_at = now(), is_active = false WHERE id = '<stray>'`). Consider a partial unique index `users(staff_id) WHERE deleted_at IS NULL` in migration 006.
2. `deploy.sh` smoke line `admin login: HTTP 200` was not in the paste; the script ends only after that step, so it is inferred, not seen.
3. No automated PostgreSQL backup exists on the VPS yet (day-2 ops gap now that real client data is live).

---

## 6. THE ONE THING TO DO NEXT (frozen 2026-09-20, post-deploy)

HEAD is `2778d82` on `main` + this state file (uncommitted until Ibrahim commits). Production = v=2.8, migrations 001–005. Ibrahim picks Phase 4:

- **(a)** Nightly `pg_dump` backup — `deploy/backup.sh` (`docker compose exec -T db pg_dump -Fc` → `/opt/edutrack/backups/`, 14-day rotation) + `deploy/edutrack-backup.timer`/`.service` installed by `deploy.sh`; restore drill documented in `DEPLOY.md`. Worker A writes the shell, Claude validates with `bash -n` + a restore into embedded PG.
- **(b)** Settings-driven `academic_year` — `settings` table + `006` migration (OpenCode Worker B), `print.py` reads it instead of the hard-coded `1447-1448 هـ`, a settings-view field; Claude tests on embedded PG. Bundle the `users(staff_id)` partial unique index into 006.
- **(c)** Pagination past `limit=100` — `fetchAll` helper in `api.js` (offset loop, ≤ 500 per page) and switch the views; Claude verifies with `node --check` + a seeded 150-student run.
- **(d)** Duplicate-user cleanup only (10 minutes) — Ibrahim pastes the SELECT above, Claude names the stray row, Ibrahim runs the soft-delete.

Pre-conditions unchanged: free ≥ 4 GB RAM before running the fleet (never with Docker Desktop up), one `opencode run` at a time with ≤ 4 files per batch, Codex via `-s workspace-write`, embedded PG booter must stay alive in the background while pytest runs (`TEST_DATABASE_URL` = superuser URI for fixtures, `DATABASE_URL` = `gheras_app` URI for the API). Production SSH/DB stays Ibrahim's action (auto-mode classifier).
