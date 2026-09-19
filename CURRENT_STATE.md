# CURRENT_STATE.md — Autovemtech Fleet Master Handoff

> **Target Client**: `Clients/03_GHERAS_Center` (EduTrack Pro — Smart Educational Center)  
> **Master Orchestrator**: `Claude Code CLI` (Opus Max / Sonnet 5)  
> **Handoff Source**: `Antigravity IDE` (Interactive Cockpit & Visual Inspector)  
> **Timestamp**: 2026-09-16T12:55:00+03:00  
> **Last updated:** 2026-09-17 — Phase 2.5 deployment-package session — Claude Code CLI  
> **VCS:** git initialized 2026-09-16 at workspace root, branch `main`. Phase 1 = `fc071f6`, Phase 2 = `a28299b` (both [APPROVED], pushed). Phase 2.5 deployment package = `eabac22` [APPROVED] — **push pending: yes** — Ibrahim runs `git push`. Remote `origin` = https://github.com/ibrahimalkateb965-tech/EduTrackPro.git. Quarantine enforced by root `.gitignore` (Rule 8).  

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

## 6. THE ONE THING TO DO NEXT (frozen 2026-09-17 01:15)

Phase 2.5 deployment package is **[APPROVED]** and committed on `main` as `eabac22`. **First action next session: `git status -sb` must show `[ahead 0]`; if not, Ibrahim runs `git push`.**

Then, once Ibrahim has cleared the three VPS blockers above (SSH key, DNS A-record, port 443), run from Git Bash:

```bash
cd "Clients/03_GHERAS_Center/edutrack_pro"
export EDUTRACK_ADMIN_PASSWORD='<strong password>'     # optional — otherwise generated and printed once
bash deploy/push.sh root@187.55.226.225 <public-hostname> -i ~/.ssh/vps_secure_key
```

`deploy.sh` ends with a summary; success = health JSON, `dashboard: HTTP 200`, `server/ blocked: HTTP 404`, `admin login: HTTP 200`. Record the hostname and the admin password location (never the password itself) here afterwards. After that, choose:

- **(a)** Phase 3 — Android app module (Compose UI over Room + `homework-core`, teacher/guardian API scoping in `routers/crud.py`).
- **(c)** Client checkpoint — dashboard screenshots against the live API + the 11 templates + Arabic acceptance note.

Pre-conditions: free ≥ 4 GB RAM before running the fleet (never with Docker Desktop up), one `opencode run` at a time with ≤ 4 files per batch, Codex via `-s workspace-write`.
