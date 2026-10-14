---
type: plan
owner: Ibrahim
created: 2026-10-13
status: in progress (Phase 0-1 done 2026-10-13)
source: "Simplified AI automation with Obsidian and Claude Code" (video transcript)
summary: Adopt the vault-as-memory + jobs-as-SOP pattern for the Autovem fleet, adapted to our 150k-token governance, multi-CLI fleet, and secrets rules.
---

# Autovem Brain — Obsidian Vault Adoption Plan

## 0. Executive Summary

The video's method is simple: one organized Obsidian vault is the agent's entire memory. Each folder has a master note that wiki-links to everything in it. A `Jobs/` folder holds the SOPs. CLAUDE.md points memory into the vault. Daily notes have an index at the top, and every agent shares the same vault.

**Autovem already has 70% of this plumbing:**
- `AGENTS.md` and `CLAUDE.md` act as the operating rules.
- `MEMORY_STORE.md` holds the ADRs.
- `CURRENT_STATE.md` is the handoff file.
- `.agents/skills/` holds the executors.
- `Clients/NN_*` has the 8-folder structure.

**What is missing is the part the video is actually about: consolidation and navigation.** Today's memory is monolithic, duplicated, ungoverned for size, and partly unsafe:

| File | Size | Problem |
| :--- | :--- | :--- |
| `.agents/MEMORY_STORE.md` | 280 KB | One file. Estimated 60k-100k tokens if loaded whole, which is most of Claude Code's 150k cap. It mixes ADRs, lessons, milestones, client data, and secrets. |
| `CURRENT_STATE.md` | 68 KB | Its header says "Target Client: GHERAS", but it is now the de-facto log for every client (latest entry: Shajan GTM v23). |
| `AGENTS.md` | 35 KB | Every agent loads it every session through `@AGENTS.md`. About 40% of it is EduTrack-only engineering rules (Rules 9, 10, 52, 53, 54) that load even for a Google Ads task. |
| `.agents/ACTIVE_CONTEXT_INJECTION.md` | 36 KB | Overlaps with the files above. |
| `PROJECT_CONTEXT.md` (root and `.agents/`) | 9 KB + 6 KB | Two diverging copies. |
| Claude Project docs (claude.ai) | 9 docs | Stale snapshots from 2026-10-08, including a full `MEMORY_STORE.md`. |
| `مكتبة الأوامر.xlsx` | 92 KB | Prompt library. Agents can't read it cheaply. |

**Target state:** a single `Brain/` vault with about 40-60 focused notes. Every agent boots by reading a home note of 1.5k tokens or less, then descends only into what the task needs. That replaces the current 20k+ token boot and the monolith reads.

---

## 1. Idea Inventory (from the transcript)

| # | Idea | Stated mechanism |
| :--- | :--- | :--- |
| I-1 | Vault as the agent's only memory | Move Claude's memory files into Obsidian. They become visible, editable, and never lost. |
| I-2 | Consolidate memory | 107 messy memory files became about 17 well-named ones, so the agent scans less and is "sharper". |
| I-3 | Master note per folder | The note has the same name as its folder and wiki-links every note inside, so one entry point leads to full context. |
| I-4 | Business and product folders | The product master note links every feature note, giving the agent full product context. |
| I-5 | `Jobs/` folder = SOPs | The job index note lists steps 1-8. Each step links to deeper notes and to the product note. |
| I-6 | One-line invocation | "Create a campaign for offer X" is enough. The agent reads the job note and executes. |
| I-7 | CLAUDE.md = how we operate. Memory pointer → vault | Redirect the default memory file to the vault. New knowledge is written into the vault, not into memory files. |
| I-8 | Anti-bloat rule | Append to existing notes. Create a new note only when strictly necessary. |
| I-9 | Agent-written daily notes | Written at session end, end of day, or after big milestones. They include date, time, and what was done. |
| I-10 | Index at the top of each daily note | The agent scans indexes first and dives into detail only on a hit. |
| I-11 | Git sync | The vault is backed up to GitHub, so everything the agent knows is versioned. |
| I-12 | Shared vault = multi-agent communication | Agents with the same instructions read each other's daily logs and avoid conflicting work. |
| I-13 | "You don't need loops or frameworks" | Vault + SOPs + API credentials + MCPs is enough for a working autonomous agent. |

---

## 2. Claim Check

| Claim | Verdict | Note |
| :--- | :--- | :--- |
| "$100k/month MRR, all with AI" | **Unverifiable** | A screenshot proves nothing about causation. The method should be judged on its mechanics, not on the income claim. |
| "107 → 17 memory files makes the agent smarter" | **Plausible, directionally right** | Fewer, denser, well-named files cut retrieval noise and tokens. This matches our own 150k-wall telemetry (MEM-073). |
| "Reading the master note makes the agent read every note in the folder" | **Correct, and harmful at our scale** | Full link traversal is unbounded context loading. It breaks Hook 27 and the 150k Claude cap. **Adapted in §4.2.** |
| "Agents communicate through daily notes" | **Partly true** | It works for asynchronous awareness. It is not a lock: parallel writers (OpenCode ∥ Cline) can collide on one file. **Adapted in §4.4.** |
| "You don't need anything else" | **Overstated** | He still lists API credentials and MCPs. We keep the test monopoly, `[APPROVED]` gates, and the Google Ads guardrails. The vault replaces memory sprawl, not verification. |

---

## 3. Decision Matrix

| # | Idea | Decision | Rationale |
| :--- | :--- | :--- | :--- |
| I-1 | Vault as single memory home | **Approved** | It removes the 7-location memory sprawl listed in §0. |
| I-2 | Consolidate memory | **Approved** | Biggest single win. `MEMORY_STORE.md` (280 KB) is the bottleneck. |
| I-3 | Master note per folder | **Approved, adapted** | Each link carries a one-line summary so the agent chooses rather than chases (§4.2). |
| I-4 | Product folders | **Approved** | For EduTrack Pro, Custody Pro, Flowchart Studio, and WMS (Islam). It also moves EduTrack-only rules out of `AGENTS.md`. |
| I-5 | `Jobs/` SOP folder | **Approved** | Jobs describe *what and why*; existing `.agents/skills/` stay as *how*. A job links to its skill and never duplicates it. |
| I-6 | One-line invocation | **Approved** | Extends the existing Instant Task Router and `/report`, `/onboard` shortcuts into job notes. |
| I-7 | Memory pointer in CLAUDE.md / AGENTS.md | **Approved** | `AGENTS.md` is already read by Codex, OpenCode, Cline, Cursor, and Antigravity, so one pointer moves the whole fleet. |
| I-8 | Append-first anti-bloat | **Approved, hardened** | Adds size caps, a split rule, and a mandatory frontmatter summary (§4.3). |
| I-9 | Agent-written daily notes | **Approved** | It replaces the "Last updated" churn in `CURRENT_STATE.md`. |
| I-10 | Daily note index header | **Approved** | One line per event, so `grep` over indexes is enough for history questions. |
| I-11 | Git sync | **Approved** | `.agents/` is currently gitignored, so `MEMORY_STORE.md` is **not versioned anywhere**. `Brain/` gets its own private repo. |
| I-12 | Shared vault for multi-agent context | **Approved, adapted** | Single-writer rule plus an append-only protocol (§4.4). |
| I-13 | "No frameworks needed" | **Rejected as stated** | Verification (Claude test monopoly), Rule 50/51/55, and the Google Ads invariants stay non-negotiable. |
| — | Obsidian Dataview for auto-indexes | **Rejected** | Agents read raw Markdown and would see query code, not results. Master notes must contain literal links. |
| — | Obsidian MCP server | **Deferred** | Agents already read and write the files directly. Revisit only if remote, cloud-session vault access becomes a need. |
| — | Obsidian Git auto-commit plugin | **Deferred** | Start with explicit commits at session end. Automate after the 30-day pilot. |
| — | Productize as a client service ("AI Ops Brain") | **Deferred** | Pillar 2 offer. Only after the internal pilot proves the metrics in §6. No prices until confirmed (Rule 50). |

---

## 4. Target Architecture

### 4.1 Vault location and layout

**Location (decided 2026-10-13):** `F:\AI PROJECTS\Autovemtech\Brain\` on Windows — the live workspace copy. Brain opens in Obsidian as its own vault. Do **not** open the workspace root as a vault: `node_modules/`, APKs, videos, and 1 MB+ HTML bundles would be indexed.

```
Brain/
├── 00_Home.md                     ← boot note (≤ 1.5k tokens): map of everything + rules pointer
├── 01_Agency/
│   ├── 01_Agency.md               ← master: identity, 3 pillars, infra pointers (no secrets)
│   ├── Rules.md                   ← Rule 1, 50, 51, 51+, 55 + Google Ads invariants (canonical text)
│   ├── Pricing_Standards.md       ← 399 SAR managed subscription, 1-page A4 quote rules, VAT clause
│   ├── Brand_Identity.md          ← links New_System_Design/ tokens; "Autovem" never transliterated
│   └── Infrastructure.md          ← VPS, MCC, DNet/SaudiNIC + Hostinger hybrid (pointers only)
├── 02_Clients/
│   ├── 02_Clients.md              ← master: one line per client + status
│   ├── 01_Shajan_Containers/
│   │   ├── 01_Shajan_Containers.md   ← master: profile, goals, links to Clients/01_* deliverables
│   │   ├── Standing_Rules.md         ← no PMax/Display, budget only on client order, report-only recs
│   │   ├── Decisions.md              ← dated one-liners (from MEMORY_STORE Decisions 2, 4, 58…)
│   │   └── Account_State.md          ← current campaigns, CPA target, GTM version
│   ├── 02_YAZ_Air_Conditioning/ …
│   └── … (one folder per active client, same 4-note pattern)
├── 03_Jobs/
│   ├── 03_Jobs.md                 ← master: job catalog with trigger phrases
│   ├── Google_Ads_Daily_Health_Check/
│   ├── Monthly_Client_Report_3_Page/
│   ├── Client_Onboarding/
│   ├── Quotation_1_Page_A4/
│   ├── Domain_And_Business_Email_Setup/
│   ├── Landing_Page_CRO/
│   ├── Fleet_Delegation_Order/
│   ├── VPS_Deploy_And_Smoke_Test/
│   └── Android_Build_And_Release/
├── 04_Products/
│   ├── 04_Products.md
│   ├── EduTrack_Pro/              ← master + Architecture + Engineering_Rules (Rules 9, 10, 52-54) + Changelog
│   ├── Custody_Pro/
│   ├── Flowchart_Studio/
│   └── WMS_Islam_Fouda/
├── 05_Fleet/
│   ├── 05_Fleet.md                ← roles, lanes, failover chain, test monopoly
│   ├── Context_Governance.md      ← Hook 25/27, 150k Claude cap
│   └── Lessons/                   ← one note per topic: psycopg3.md, windows_shell.md, opencode_cli.md,
│                                     adb_physical_devices.md, pgserver_testing.md, caddy_cache.md …
├── 06_Daily/
│   └── 2026-10-13.md              ← one note per day, index header + appended session blocks
├── 07_Archive/
│   └── MEMORY_STORE_2026-10-13.md ← frozen original, never loaded by default
└── _templates/
    ├── daily.md   ├── job.md   ├── client.md   └── decision_line.md
```

**Client deliverables stay where they are** (`Clients/NN_*/0X_*`). Vault client notes **link to them by path** and never copy files, credentials, or reports. `ACCESS_CREDENTIALS.md` and `workers data/` are referenced by name only, never ingested.

### 4.2 Navigation protocol (replaces "read every linked note")

Every master note lists children as:

```markdown
- [[Standing_Rules]]: budget/PMax/Display guardrails; read before ANY account change
- [[Account_State]]: live campaigns, CPA target, GTM version; read for reports/optimizations
- [[Decisions]]: dated decision log; read only when asked "why/when"
```

Agent boot sequence (written into `AGENTS.md`):
1. Read `Brain/00_Home.md`.
2. Read the master note of the target: a client, job, or product.
3. Follow **only** the links whose summary matches the task. **Max link depth: 2.**
4. Never load `07_Archive/`. For history, search `06_Daily/` **index headers only** (`grep "^- "`), then open the hit.
5. Budget: boot context of 8k tokens or less before the first action on a routine task.

### 4.3 Anti-bloat and hygiene rules (canonical, go into `AGENTS.md`)

1. **Search before create.** Append to the existing subject note. Create a new note only when no note covers the subject.
2. **One subject per note.** At about 300 lines or 25 KB, split into a child note and link it from the parent with a summary line.
3. **Mandatory frontmatter:** `type`, `owner`, `updated`, `summary` (one line). The summary is what master notes quote.
4. **Master note = folder name**, and it lists every child with a one-line summary.
5. **Decisions are dated one-liners** in the subject's `Decisions.md`: `- 2026-10-13: <decision> (why: …) [agent]`. Long rationale goes in a linked note.
6. **Lessons go to `05_Fleet/Lessons/<topic>.md`.** Never put them in daily notes alone.
7. **Zero secrets, zero personal IDs** (tokens, passwords, Iqama or passport numbers, account numbers). Link to the `.env` key name or a file path instead. This extends `AGENTS.md` §5.2 and Rule 51 to the vault.
8. **Language:** follow the existing policy. Notes are in English. Arabic client-facing text is wrapped per Rule 1. Numerals are Western (0-9) per Rule 50.

### 4.4 Daily notes and multi-agent writing protocol

- One file per day: `06_Daily/YYYY-MM-DD.md`, created from `_templates/daily.md`.
- **Index header** at the top, one line per event:
  `- 14:20 [Claude Code] [Shajan] GTM v23 verified live → [[01_Shajan_Containers]]`
- **Writers:** only orchestrators write to the vault: Claude Code CLI and the Antigravity IDE cockpit. Workers (OpenCode A/B, Cline C/D) write task reports to `fleet_orders/…/task-N-report.md`, and the orchestrator distills them. This prevents parallel-write collisions.
- **Append-only:** the daily note is never rewritten. Each session appends one block at the end, plus its index lines at the top.
- **Triggers:** session end, any `[APPROVED]` verdict, any production deploy, any client-facing deliverable, and before `/strategic-clear` (Hook 25). The daily note replaces the resumption-prompt drift documented in the "stale baseline" lesson.
- Per-client operational logs (`Change_Log.md`, `Daily_HealthCheck_Log.md`) **stay** in the client folders. The daily index line links to them; it does not duplicate them.

### 4.5 Entry-point rewiring

| File | Change |
| :--- | :--- |
| `AGENTS.md` | Slim from 35 KB to about 8 KB: identity, routing table, fleet roles, non-negotiables, and the **Brain pointer plus §4.2-4.4 protocols**. Product-specific rules move to `Brain/04_Products/EduTrack_Pro/Engineering_Rules.md`. |
| `CLAUDE.md` | Add: "Long-term memory lives in `Brain/`. Do not create or update Claude auto-memory files; write durable knowledge to the vault per AGENTS.md §Memory." Point Key Pointers at `Brain/00_Home.md`. |
| Claude Code auto-memory (`~/.claude/projects/<slug>/memory/`) | Inventory the files, merge them into the vault, then leave a single `MEMORY.md` containing only the pointer to `Brain/00_Home.md`. |
| `.agents/MEMORY_STORE.md` | Freeze it to `Brain/07_Archive/` and replace the original with a 10-line pointer stub, so old prompts that reference it still resolve. |
| `CURRENT_STATE.md` | Keep it as **"what's in flight right now"** only, capped at about 150 lines. History moves to product changelogs and daily notes. |
| `PROJECT_CONTEXT.md` ×2, `ACTIVE_CONTEXT_INJECTION.md` | Merge into `00_Home.md` and `01_Agency.md`, then stub them. |
| `مكتبة الأوامر.xlsx` | Prompts that encode a repeatable workflow become job notes. The workbook stays as the human archive. |
| Antigravity / Gemini global config (`~/.gemini/…`) | Same pointer line as `CLAUDE.md`. |
| Claude Project "Autovem" docs | Replace the stale snapshots with `00_Home.md`, `01_Agency/Rules.md`, and `05_Fleet.md`. **Remove the full `MEMORY_STORE.md`** (see Phase 0). |

### 4.6 Job note template (`_templates/job.md`)

```markdown
---
type: job
trigger: ["تقرير أداء", "monthly report", "/report"]
skill: .agents/skills/campaign-runner
updated: YYYY-MM-DD
summary: <one line>
---
# <Job Name>
## Inputs
- Client: [[<client master>]] (read Standing_Rules first)
## Steps
1. <step> → [[<sub-note if non-trivial>]]
2. …
## Guards
- [[Rules]]: Rule 50 numerals, Rule 51 no cross-client bleed
- <job-specific invariants>
## Outputs
- Path: `Clients/<NN_client>/05_Analytics_Reports/<file>`
## Verification
- <what proves it's done: page count, numeral scan, link check, [APPROVED]>
## Log
- Append index line to today's [[06_Daily]] note
```

---

## 5. Execution Phases

### Phase -1: Workspace location — **decided: Windows F:** (2026-10-13)

The F: copy is the live one. The WSL copy (`/home/t/projects/Autovemtech`, Decision 71) is stale since 2026-09-30: do not build the vault there and do not run fleet sessions against it. If WSL tooling is wanted later, agents inside WSL reach the vault at `/mnt/f/AI PROJECTS/Autovemtech/Brain/`.

### Phase 0: Security purge and baseline (Day 1, about 1 hour). **Blocking**

Before any content moves into a vault that will sync to GitHub:

| # | Action | Owner |
| :--- | :--- | :--- |
| 0.1 | `git tag pre-brain-2026-10-13` on the root repo. Copy `.agents/MEMORY_STORE.md` to a local, unsynced backup. | Claude Code |
| 0.2 | Scan `MEMORY_STORE.md`, `CURRENT_STATE.md`, and `AGENTS.md` for secrets and personal IDs. Already identified: a **Google Ads developer token value** (MEM-2026-09-10-053), a **production admin password** (Decision 24, item 5), and **3 workers' Iqama/passport/permit numbers** (Decision 5). | Claude Code |
| 0.3 | Redact these in the archive copy before it enters `Brain/`. Replace each with `[REDACTED → .env:<KEY_NAME>]`. | Claude Code |
| 0.4 | **Rotate** the EduTrack admin password if it is still in use. Consider resetting the Google Ads developer token in API Center. Both values were uploaded to the claude.ai Project as part of `MEMORY_STORE.md`. | **Ibrahim** |
| 0.5 | Replace `MEMORY_STORE.md` in the Claude Project docs with a redacted or slim version. | Ibrahim, or Claude with approval |

**Exit gate:** a `grep` for the known secret patterns over `Brain/` returns 0 hits.

### Phase 1: Scaffold the vault (Day 1-2, about 2 hours)

1. Create the `Brain/` tree from §4.1 and the 4 templates in `_templates/`.
2. In Obsidian, use "Open folder as vault" on `Brain/`. Set: new notes in the current folder, attachments in `_attachments/`, wiki-links ON, and "Automatically update internal links" ON.
3. Run `git init` inside `Brain/`, then create a **private** GitHub repo `autovem-brain`. Add `Brain/` to the root `.gitignore` (the root repo scope is EduTrack only). Add a pre-commit hook that rejects commits matching secret patterns.
4. Write `00_Home.md` and `01_Agency/Rules.md` first. These two notes alone already shrink every session boot.

**Exit gate:** Obsidian graph view shows `00_Home` connected to every top-level master note.

### Phase 2: Migrate and consolidate memory (Day 2-4)

Split `MEMORY_STORE.md` by subject, not by date:

| Source section | Destination |
| :--- | :--- |
| Decisions 1, 3, 8-13, 15-19, 71, 72 (Rule 55), global standards | `01_Agency/Rules.md`, `Pricing_Standards.md`, `Infrastructure.md` |
| Decisions 2, 4, 5 (redacted), 58 | `02_Clients/01_Shajan_Containers/Decisions.md` |
| Decisions 6, 14, 77 | `02_Clients/02_YAZ_Air_Conditioning/Decisions.md` |
| Decision 60 | `02_Clients/08_Emaar_Al_Farah_Est/Decisions.md` |
| Decisions 22-25, 53-57, 62-68, 70, 73-76 and EduTrack milestones | `04_Products/EduTrack_Pro/Changelog.md` + `Engineering_Rules.md` |
| Custody Pro milestones | `04_Products/Custody_Pro/Changelog.md` |
| Decisions 61, 69 | `04_Products/WMS_Islam_Fouda/Changelog.md` |
| All `[lesson]` blocks | `05_Fleet/Lessons/<topic>.md`, grouped (psycopg3, Windows shell, OpenCode, pgserver, ADB, cache-busting, RBAC scoping…) |
| Decisions 20, 59, MEM-058/061/069/073/077 | `05_Fleet/05_Fleet.md` + `Context_Governance.md` |
| Decision 21 (Visa) | `01_Agency/Ventures_Visa_Concierge.md`: pointer to `Visa/` only (quarantined folder, no content copy) |

**Fleet execution (per our own rules):**
- Mechanical splitting goes to **OpenCode Worker A (`glm-5.3-flash`)** in batches of **4 or fewer destination files**, with a closed read list of explicit line ranges from the archive and the instruction "append-only, no edits outside listed files, write task-N-report.md".
- **Claude Code** verifies each batch with a coverage check: every `### القرار N` and `## [lesson]` heading in the archive maps to exactly one destination note (orphan count = 0, duplicate count = 0). It also runs a numeral scan (Rule 50) and a secret-pattern scan.
- Antigravity IDE reviews the graph view visually.

**Target:** about 40-60 notes total, none over 25 KB, plus `07_Archive/` holding the frozen original.

### Phase 3: Jobs library (Day 4-6)

Build jobs in order of how often they run, using the §4.6 template. Each job links its existing skill and never rewrites it.

| Priority | Job | Sources to distill |
| :--- | :--- | :--- |
| 1 | Google_Ads_Daily_Health_Check | Shajan `Monitoring_Routine.md`, `Daily_HealthCheck_Log.md`, standing rules |
| 2 | Monthly_Client_Report_3_Page | MEM-055 print-ready standard, `arabic-docx-builder`, `/report` |
| 3 | Client_Onboarding | `_Agency_Templates/`, `/onboard`, Decision 1 |
| 4 | Quotation_1_Page_A4 | Decisions 8-11, 60, Rule 55 |
| 5 | Domain_And_Business_Email_Setup | Decisions 12-14, 77 |
| 6 | Fleet_Delegation_Order | Small-batch rule, closed-read-list pattern, `fleet_orders/` format |
| 7 | VPS_Deploy_And_Smoke_Test | EduTrack deploy lessons, curl smoke checks |
| 8 | Android_Build_And_Release | ADB invariants (Rule 52), APK publish path |

**Exit gate:** for jobs 1-3, a cold session given only a one-line trigger ("تقرير أداء شجن لشهر أكتوبر") reaches the correct output path and guards without extra explanation from Ibrahim.

### Phase 4: Rewire entry points and turn on daily notes (Day 6-7)

1. Apply the §4.5 table: slim `AGENTS.md`, add pointer lines to `CLAUDE.md` and the Gemini config, stub `MEMORY_STORE.md`, `PROJECT_CONTEXT.md`, and `ACTIVE_CONTEXT_INJECTION.md`, and trim `CURRENT_STATE.md`.
2. Add the daily-note step to `.claude/commands/strategic-clear.md` (Hook 25): append a session block plus index lines before clearing.
3. Run one full day with daily notes from both Claude Code and Antigravity, then check that both agents found each other's entries.

**Exit gate:** a fresh session's boot (before the first task action) is 8k tokens or less, measured on the statusline.

### Phase 5: 30-day pilot and measurement (Weeks 2-5)

| Metric | Baseline (measure on Day 1) | Target at Day 30 |
| :--- | :--- | :--- |
| Boot context before first action (Claude Code) | current `ctx` reading after `@AGENTS.md` load | 8k tokens or less |
| Times Ibrahim re-explains known context per week | count manually for week 0 | −70% |
| Largest single memory file | 280 KB | 25 KB or less |
| Duplicate context files at root and `.agents/` | 5 | 0 (stubs only) |
| Sessions ending with a daily-note entry | 0% | 90% or more |
| Secret-pattern hits in `Brain/` | n/a | 0 (pre-commit enforced) |

**Go / no-go at Day 30:** if 4 of 6 targets are met, lock the pattern in `AGENTS.md` and open the Deferred items (Obsidian Git automation, client productization).

### Phase 6: Productize (Deferred)

If the pilot passes, package the setup as a Pillar 2 offer for clients who already run AI agents: vault scaffold, jobs library, memory migration, and daily-note protocol. Scope, deliverables, and pricing are TBD and need Ibrahim's confirmation (Rule 50: no invented prices; Rule 55: no framework names in contracts).

---

## 6. Risks and Mitigations

| Risk | Mitigation |
| :--- | :--- |
| Secrets or PII leak into a GitHub-synced vault | Phase 0 is blocking. The pre-commit secret scan and rule §4.3.7 are permanent. |
| Two diverging copies of the workspace (F: and WSL) | **Decided 2026-10-13: F: is canonical.** The WSL copy is stale; mark it read-only or archive it so no agent works against it. |
| Vault re-bloats over time | Size caps, the split rule, and a monthly 15-minute "Brain hygiene" job: Claude Code lists notes over 25 KB and orphan notes. |
| Parallel writers corrupt the daily note | Single-writer rule (§4.4). Workers only write `task-N-report.md`. |
| Old prompts and skills reference `MEMORY_STORE.md` | Pointer stubs stay in place at the old paths. |
| Jobs duplicate skills and drift | A job holds the business SOP plus guards and links its skill. A skill change never requires a job edit unless inputs or outputs change. |
| Antigravity format-on-save rewrites vault notes (as with the `v=` cache-bust regression) | Exclude `Brain/` from format-on-save in Antigravity IDE settings. |

---

## 7. Immediate Next Actions

1. **Done 2026-10-13:** vault location (F:), Phase 0 redacted archive, Phase 1 scaffold.
2. **Ibrahim:** rotate the EduTrack admin password and decide on the developer-token reset (Phase 0.4).
3. **Claude Code:** Phase 2 (split `MEMORY_STORE.md` into vault notes) via OpenCode batches under the coverage gate.
4. **Day 30:** go/no-go review against the §5 Phase 5 metrics table.
