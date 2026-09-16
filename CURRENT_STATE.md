# CURRENT_STATE.md — Autovemtech Fleet Master Handoff

> **Target Client**: `Clients/03_GHERAS_Center` (EduTrack Pro — Smart Educational Center)  
> **Master Orchestrator**: `Claude Code CLI` (Opus Max / Sonnet 5)  
> **Handoff Source**: `Antigravity IDE` (Interactive Cockpit & Visual Inspector)  
> **Timestamp**: 2026-09-16T12:55:00+03:00  
> **Last updated:** 2026-09-16 18:25 — Phase 1 close-out session — Claude Code CLI  
> **VCS:** workspace is NOT a git repository (no commit hash; truth = files on disk under `Clients/03_GHERAS_Center/edutrack_pro/`)  

---

## 1. Fleet Roles & Division of Labor (Strict Enforcement)

| Agent | Harness / Model | Role & Boundaries |
| :--- | :--- | :--- |
| **Claude Code CLI** | `claude` (Opus Max) | **MASTER ORCHESTRATOR & SOLE TESTING AUTHORITY**<br>• Drives the entire 8-hour autonomous sprint.<br>• Conducts architectural audits & Devil's Advocate reviews.<br>• **Exclusive Testing Monopoly**: Sole authority to run test suites and grant `[APPROVED]`.<br>• Delegates boilerplate to OpenCode to conserve Opus reasoning tokens. |
| **OpenCode CLI (Worker A)** | `opencode run -m opencode-go/glm-5.3-flash` | **Ultra-Fast Terminal & UI Scaffolding**<br>• Fast shell commands, scaffolding, Web/Compose layouts (NO testing). |
| **OpenCode CLI (Worker B)** | `opencode run -m opencode-go/muse-spark-1.3-contributor` | **Database & Data Architect**<br>• SQLite/Room schemas, DAOs, migrations, and data models (NO testing). |
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

## 5. THE ONE THING TO DO NEXT (frozen 2026-09-16 18:25)

Phase 1 is **[APPROVED]** and closed. The next session must start with Ibrahim choosing one of:

- **(a)** Phase 2 — build the `/api/v1` REST server (FastAPI + PostgreSQL) per `edutrack_pro/docs/PHASE1_SPEC.md §4`, including the `gheras_simple_v1` JSON importer, so the dashboard and print templates go live (Day-10 promise).
- **(b)** Phase 2 — Android app module: Compose UI wiring the Room layer + `homework-core`, Room/KSP compile, teacher + guardian screens (Tier 2 deliverable).
- **(c)** Client-facing checkpoint — generate the Milestone m1 preview pack for Gheras (print-template PDFs with sample data + dashboard screenshots) and a signed-off acceptance note before more build work.

Pre-conditions for any choice: opt-in Muse Spark on the OpenCode workspace (or keep `glm-5.3`), free ≥ 4 GB RAM before running the fleet, one `opencode run` at a time with ≤ 4 files per batch.
