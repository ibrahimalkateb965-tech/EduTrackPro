# AGENTS.md — Autovemtech Master Architecture & Fleet Directive

Welcome to **Autovemtech** (Autovem) — a hybrid technology and growth agency.
This document is the canonical entry point for all AI agents, coding assistants, and CLI harnesses (including **Codex Harness / ChatGPT Desktop**, **Claude Code CLI**, **Antigravity IDE**, **OpenCode CLI**, and **Cursor**).

---

## 1. Agency Identity & Core Infrastructure

- **Entity**: Autovemtech (Brand: `Autovem` — do not transliterate into Arabic in official headings).
- **Core Mission**: Delivering end-to-end digital growth, bespoke AI systems, and cross-platform mobile/web software.
- **Official Domain**: `www.autovemtech.com` | **Official Inquiries**: `ibrahimalkateb965@gmail.com`.
- **Infrastructure Registry** *(Pointers only — zero secrets in this file; credentials live in `.env`)*:
  - **Google Ads MCC (Manager Account)**: `576-627-3068` (`AutovemTech Agency`).
  - **Cloud Infrastructure**: Hostinger VPS (`srv1810150.hstgr.cloud` — IP: `187.55.226.225`, KVM 1: 50 GB NVMe, 4 GB RAM).
  - **Hybrid Domain & Business Email Architecture**:
    - **Sovereign TLDs (`.sa` / `.com.sa`)**: Registered & managed via local accredited registrars (**DNet / SaudiNIC**).
    - **Enterprise Business Email**: Managed via Hostinger Business Mail Cluster (MX1/MX2, SPF, DKIM CNAME Rotation, DMARC `p=quarantine`).
    - **Web & Application Hosting**: Directed to VPS via A-Records managed by Caddy & Docker containers.

---

## 2. The Three Operational Pillars

```
                     ┌──────────────────────────────────────────────┐
                     │          AUTOVEMTECH AGENCY ROOT             │
                     └──────────────────────┬───────────────────────┘
                                            │
         ┌──────────────────────────────────┼──────────────────────────────────┐
         ▼                                  ▼                                  ▼
┌──────────────────┐               ┌──────────────────┐               ┌──────────────────┐
│     PILLAR 1     │               │     PILLAR 2     │               │     PILLAR 3     │
│ Digital Marketing│               │   AI Solutions   │               │ App Engineering  │
│   & Growth Ops   │               │ & Agent Systems  │               │ (Mobile & Web)   │
└────────┬─────────┘               └────────┬─────────┘               └────────┬─────────┘
         │                                  │                                  │
  Clients/                          .agents/                          Android (Kotlin/Compose)
  ├── 01_Shajan_Containers          tools/                            iOS (Remote Playbook)
  ├── 02_YAZ_Air_Conditioning       scripts/                          Web (Modern Vanilla/Next)
  ├── 03_GHERAS_Center              Flowchart Studio                  Play Console & Store Ops
  ├── 04_Apex_Services
  └── _Agency_Templates
```

### Pillar 1: Digital Marketing & Client Growth Operations
- **Root Directory**: `Clients/`
- **Standard 8-Folder Structure per Client**:
  1. `00_Client_Profile`: Client identity, geographic targeting, and access credentials.
  2. `01_Market_Research`: Market analysis, competitor audits, and keyword intent clustering.
  3. `02_Google_Ads_Campaigns`: Campaign architecture (Search, Call-Only, PMax), budgets, and negative keyword lists.
  4. `03_Ad_Creatives_Copy`: High-converting Responsive Search Ads (RSA), call ads, and assets.
  5. `04_Landing_Pages_CRO`: Mobile-first CRO, GTM/GA4 dataLayer events, WhatsApp/call conversion hooks.
  6. `05_Analytics_Reports`: Weekly KPI dashboards, monthly audits, and print-ready executive summaries.
  7. `06_Contracts_Invoices`: Scope of Work (SOW), managed subscriptions, and payment logs.
  8. `07_Old_Data_Archive`: Historical audits, past account benchmarks, and migration logs.
- **Client Onboarding Rule**: Always duplicate `Clients/_Agency_Templates/` to create a new client folder (`Clients/NN_ClientName`).

### Pillar 2: AI Solutions & Autonomous Agentic Systems
- **Root Directories**: `.agents/`, `tools/`, `scripts/`
- **Capabilities**:
  - Autonomous multi-agent pipelines (Fleet Orchestrator, Sub-agents under `.agents/agents/` with legacy fallback to `.agents/Sub_Agent/`).
  - Production-grade **FastMCP** server construction and tool exposure.
  - **n8n** automation workflows, webhook processors, and LLM router architectures.
  - Visualization & System Architecture: **Autovem Flowchart Studio** (`tools/flowchart_studio/`) for 4K architectural diagramming with WCAG AA compliance.
- **Memory & Decisions**: Live in the **Autovem Brain** vault (`Brain/`). Every agent boots from `Brain/00_Home.md` and follows its navigation rules (one master note, max depth 2). `.agents/MEMORY_STORE.md` is frozen history since 2026-10-13: do not load at boot, do not append.
  - **Brain Vault Pilot Governance Standard ([C-107]):**
    1. *Mandatory Session Logging:* Every orchestrator (`Claude Code`, `Antigravity IDE`, `agcli`, `OpenCode`) must log one `PILOT` line under `## Pilot` in `Brain/06_Daily/YYYY-MM-DD.md` at session close: `- PILOT HH:MM [Agent] job=<job-or-none> boot_files=<n> boot_kb=<n> hit=<1|0> misses=<n> note=<short text>`.
    2. *Arabic Note Sizing Standard:* Evaluate note bloat strictly by line count ($\le 300$ lines), not by KB (which UTF-8 Arabic 2-byte encoding inflates). Decompose notes > 300 lines into master index ($\le 100$ lines) + topic sub-notes ($\le 250$ lines).
    3. *Wiki-Link Sanitization:* Write ANSI escape codes as `\x1b[` or `ESC[`, never `^[[`, to prevent regex link checkers from reporting false-positive broken links.

### Pillar 3: Application Engineering (Mobile & Web)
- **Android Engineering**: Kotlin, Jetpack Compose, Material 3, Room DB, WorkManager, Offline-first sync, and Google Play Console compliance (D-U-N-S, Organization account standards).
- **iOS Remote Engineering**: Zero-Mac CI/CD pipeline governed strictly by [REMOTE_IOS_DEV_PLAYBOOK.md](file:///f:/AI%20PROJECTS/Autovemtech/REMOTE_IOS_DEV_PLAYBOOK.md).
  - Fast inner loop: Linux container (`flutter analyze`, test, web preview).
  - Slow outer loop: GitHub Actions `macos-15` runner for `xcodebuild`, code signing, and TestFlight deployment.
  - Strict VoiceOver accessibility standards.
- **Web Engineering**: High-performance modern web interfaces, semantic HTML5, zero-bloat vanilla CSS, responsive layouts, and print-ready media styling (`@media print`).

---

## 3. Instant Task Router (Shorthand Intent Mapping)

When the user provides a brief or conversational prompt, immediately resolve and route the task using this lookup table:

| User Trigger / Keyword | Target Directory / Document | Standard Operating Action |
| :--- | :--- | :--- |
| **"فحص يومي" / "health check"** | `Brain/03_Jobs/Google_Ads_Daily_Health_Check.md` | Report-only daily check; never apply recommendations or change budget. |
| **"عميل جديد" / "new client"** | `Clients/_Agency_Templates` → `Clients/NN_Name` (SOP: `Brain/03_Jobs/Client_Onboarding.md`) | Clone template folder, initialize `00_Client_Profile/CLIENT_OVERVIEW.md`. |
| **"شجن" / "حاويات" / "Shajan"** | `Clients/01_Shajan_Containers/` | Check `02_Google_Ads_Campaigns` or `05_Analytics_Reports`. Focus: Search + Call-Only, Riyadh waste/dumpsters. |
| **"ياز" / "تكييف" / "YAZ"** | `Clients/02_YAZ_Air_Conditioning/` | Check campaigns or email credentials. Enforce negative list for automotive AC bleed; isolate emergency maintenance from project installs. |
| **"غراس" / "Gheras"** | `Clients/03_GHERAS_Center/` | Educational center campaigns, landing page lead-gen, local community targeting. |
| **"تقرير أداء" / "Google Ads report"** | `Clients/<client>/05_Analytics_Reports/` | Build 3-page print-ready report (DOCX via `arabic-docx-builder` + PDF export + `print.html`). Apply Rule 50 (0-9 numerals). |
| **"صفحة هبوط" / "CRO" / "landing page"** | `Clients/<client>/04_Landing_Pages_CRO/` | Mobile-first CRO, WhatsApp/Call sticky buttons, GTM click-to-call triggers. |
| **"فاتورة" / "عرض سعر" / "quote"** | `Clients/<client>/06_Contracts_Invoices/` | Managed subscription standard (399 SAR/yr for domain+mail). Strict 1-Page A4, Rule 50 zero-pricing. |
| **"نطاق" / "بريد مهني" / "DNS"** | `Brain/03_Jobs/Domain_And_Business_Email_Setup.md` | Hybrid architecture: DNet/SaudiNIC for `.sa` + Hostinger Enterprise Mail (MX/SPF/DKIM/DMARC). |
| **"Google Ads API" / "OAuth" / "token"** | `GOOGLE_ADS_API_RESUMPTION.md`, `tools/google_ads_mcp/` | ✅ **Active & Connected (API v22)**. Managed via FastMCP server (`tools/google_ads_mcp/server.py`). Run `get_api_status` or use `authenticate_google_ads.py` for token refresh. |
| **"Android" / "APK" / "Play Console"** | Play Console PDFs at root + mobile specs | Review Organization D-U-N-S rules and demo account requirements before submission. |
| **"iOS" / "TestFlight" / "build ipa"** | `REMOTE_IOS_DEV_PLAYBOOK.md` | Execute fast loop on Linux container; push to GitHub Actions `macos-15` for `.ipa`. |
| **"مخطط" / "flowchart" / "architecture"** | `tools/flowchart_studio/` | Open studio via `open_flowchart_studio.bat`. Use valid Mermaid syntax and Dark Neon Candy palette. |
| **"وكيل جديد" / "MCP" / "n8n"** | `.agents/skills/`, `tools/google_ads_mcp/` | Follow YAML frontmatter standard; ensure zero-leak stdio for FastMCP (`google-ads` server registered). |
| **"لوكا" / "Loka" / "الخطاف 32" / "برمجة جراحية"** | `.agents/skills/loka-orchestrator/` (Hook 32) | تفعيل حوكمة Loka، التوجيه الذاتي (ACE/V10/V11/V12)، قفل SPEC.md، وتنفيذ التعديل الجراحي الأدنى. |

---

## 4. Multi-CLI Fleet Matrix & Division of Labor

Autovemtech operates an autonomous multi-CLI fleet governed by [fleet_config.json](file:///f:/AI%20PROJECTS/Autovemtech/fleet_config.json):

```
┌────────────────────────────────────────────────────────────────────────┐
│              CLAUDE CODE CLI (Opus Max / Sonnet 5)                     │
│   Master Orchestrator • Staff Architect • Sole Test & Audit Authority  │
└──────────────────┬──────────────────────────────────┬──────────────────┘
                   │                                  │
                   ▼                                  ▼
┌──────────────────────────────────────┐  ┌─────────────────────────────────────────────────────────────┐
│     CODEX CLI / CHATGPT DESKTOP      │  │                 OPENCODE CLI (PAID GO EXECUTOR)             │
│           (GPT-6.1 Soul)             │  │ ┌─────────────────────────────────────────────────────────┐ │
│ Algorithmic Logic • Complex Refactor │  │ │ Worker A: GLM 5.3 Flash (opencode-go/glm-5.3-flash)     │ │
│ Deep Optimizations • Data Parsers    │  │ │ Fast Terminal, Builds, Scaffolding, DB & Backend Logic  │ │
└──────────────────────────────────────┘  │ └─────────────────────────────────────────────────────────┘ │
                   │                      └─────────────────────────────────────────────────────────────┘
                   │                      ┌─────────────────────────────────────────────────────────────┐
                   │                      │                     CLINE CLI (WORKER B)                    │
                   │                      │ ┌─────────────────────────────────────────────────────────┐ │
                   │                      │ │ Worker B: GLM 5.3 Flash (medium)                        │ │
                   │                      │ │ Parallel UI Scaffolding, Terminal & API Integration     │ │
                   │                      │ └─────────────────────────────────────────────────────────┘ │
                   │                      └──────────────────────────────┬──────────────────────────────┘
                   │                                                     │
                   └──────────────────┬──────────────────────────────────┘
                                      ▼
┌────────────────────────────────────────────────────────────────────────┐
│               ANTIGRAVITY CLI - agcli (Gemini 3.8 Flash High)          │
│       Terminal Executor: UI Architecture, Compose/Web & Domain Logic    │
└────────────────────────────────────────────────────────────────────────┘
                                      ▲
                                      │ (Refined prompts & plans)
┌────────────────────────────────────────────────────────────────────────┐
│                     ANTIGRAVITY IDE (Workbench & Cockpit)              │
│  Interactive Control Room • Direct Collaboration with User • Guidance  │
│  Visual Inspection • Prompt Optimization BEFORE Dispatch to Fleet CLIs │
└────────────────────────────────────────────────────────────────────────┘
```

### Fleet Roles & Separation of Concerns

| Agent Entity | Tool / Harness | Model / Invocation | Core Operational Role |
| :--- | :--- | :--- | :--- |
| **Claude Code CLI** | `claude` | Opus Max / Sonnet 5 (Frontier Coding Tier) | Master Orchestrator, Staff Architect, **Sole Testing Authority**, Quality Gatekeeper. |
| **Codex CLI / Desktop** | `codex` / ChatGPT Desktop | GPT-6.1 Soul / gpt-6.1-soul | Algorithmic logic, complex math engines, heavy refactoring, data pipelines. |
| **OpenCode CLI (Worker A)** | `opencode` (Flash) | `-m opencode-go/glm-5.3-flash` (or newest code tier) | **Primary Paid Fast Executor**: Shell commands, CLI build tasks, database migrations, DAOs, repositories, backend endpoints (NO TESTING; **MAX 1 instance of GLM concurrently**). |
| **Cline CLI (Worker B)** | `cline` (Flash) | `glm-5.3-flash (medium)` | **Parallel UI & Scaffolding**: Runs concurrently with Worker A. Rapid terminal tasks, defaults to Act mode with Auto-approve. ($0.00) |
| **Antigravity CLI (`agcli`)** | `agcli` | Gemini 3.8 Flash High (or newest Gemini code tier) | **Autonomous Terminal Executor**: Headless generation of UI components, Jetpack Compose, Web views, and domain logic. |
| **Antigravity IDE** | Editor Workbench | Gemini 3.8 Flash High | **Interactive Human-AI Cockpit**: High-level strategy, consultation, visual inspection, monitoring real-time outputs, and manually refining/optimizing commands with the user *before* routing them to the CLI fleet. **Never target Antigravity IDE with automated CLI delegate prompts.** |

### Dual-Harness Concurrency Invariants
> [!IMPORTANT]
> **OpenCode & Cline Parallel Execution Rule**: Worker A (`opencode` with `opencode-go/glm-5.3-flash`) and Worker B (`cline` with `glm-5.3-flash`) operate in separate harnesses.
> However, **NEVER spawn more than ONE instance of `opencode-go/glm-5.3-flash` simultaneously** in OpenCode CLI to prevent provider concurrency lockouts (HTTP 429).
> Worker B in Cline operates in parallel without collision, providing rapid execution redundancy.

### Mandatory Testing & Verification Monopoly & Hybrid Web QA Pipeline ([C-96])
> [!CAUTION]
> **Exclusive Testing & Sign-off Rule**: `Claude Code CLI` is the **ONLY** agent authorized to grant final architectural approval (`[APPROVED]`).
> Neither Codex, OpenCode, Antigravity CLI (`agcli`), nor Antigravity IDE may unilaterally sign off on feature quality gates without Claude's sign-off.
>
> **Hybrid Web QA Delegation Standard ([C-96])**: To prevent token quota exhaustion on repetitive UI sweeps, **`Antigravity CLI (agcli)`** equipped with **`webapp-qa-tester`** (Playwright / Chrome DevTools) executes granular browser interactions (button clicks, form fills, DOM assertions, and screenshot verifications) locally ($0 cost). They compile a verified 100% green execution matrix and submit it to `Claude Code CLI` for compact review (<5,000 tokens), Devil's Advocate audit, and official `[APPROVED]` certification.

### Autonomous Consult-and-Execute Fleet Pipeline ([C-25] & Decision 80)
> [!TIP]
> **Consult-and-Execute Protocol**: To eliminate manual user copy-pasting and prevent browser automation timeouts in Claude Code CLI:
> 1. **Antigravity IDE** leads field execution, DOM inspection, complex browser tasks via `browser_subagent`, and local workspace file modifications.
> 2. **Claude Code CLI** is queried headlessly in the background via `claude -p "..."` for high-level architectural arbitration, edge-case auditing, and exclusive testing/verification gates.
> 3. Zero manual copy-pasting; saves >80% tokens on Opus Max and guarantees robust browser session stability.

### Two-Stage Phased Dispatch & Anti-Hollow Build Standard ([C-103])
> [!IMPORTANT]
> **Anti-Hollow Build Mandate**: When launching autonomous/turbo background workers for full-stack systems requiring external credentials or services (Discord bots, Sentry DSN, GitHub PAT, Temporal Cloud, Payment Gateways):
> - **Stage 1 (Autonomous Background Scaffolding):** Limit scope strictly to architecture blueprints, code scaffolds, workflow logic, `.env.example`, and an explicit step-by-step human provisioning checklist (`SETUP_CHECKLIST.md`).
> - **Mandatory Hard Stop Gate:** The agent MUST halt immediately after completing scaffolding and present the human checklist. Never mock credentials or falsely declare completion.
> - **Stage 2 (Interactive Handshake & Shadow Run):** Live integration testing, webhook binding, and dry-run verification begin ONLY after the user provisions real credentials in `.env`, strictly adhering to Shadow Mode.

### Token Conservation & Context Window Sweet Spot Governance (Hook 27 & Hook 25)
Governed by [.agents/CONTEXT_GOVERNANCE.md](file:///f:/AI%20PROJECTS/Autovemtech/.agents/CONTEXT_GOVERNANCE.md):
- **Golden Sweet Spot (30,000 – 300,000 tokens):** Peak attention fidelity is strictly confined to this range.
- **4 Operational Zones:**
  1. **🟢 Green Zone (< 180k tokens):** 100% attention; normal operations with `lean-ctx` signatures.
  2. **🟡 Yellow Zone (180k – 240k tokens):** Strict ban on `mode='full'` reads; line range inspection only.
  3. **🟠 Orange Zone (240k – 300k tokens):** Scope freeze, clean git commit, log the decision in the matching Brain note + today's daily note, generate resumption prompt.
  4. **🔴 Red Zone (> 300k tokens):** Stop all exploratory work; run Hook 25 / `/strategic-clear`, then `/clear` and resume from the resumption prompt.
- **Active Offloading Engine:** Route all boilerplate, shell commands, and DB migrations to OpenCode CLI (GLM 5.3 Flash) to conserve expensive Claude Opus Max token budget.
- **Claude-Exclusive Statusline Golden Safety Calibration (150,000 tokens):**
  The 150,000 token safety cap applies **EXCLUSIVELY to Claude Code CLI** (and not to other agents like Antigravity IDE / Gemini or OpenCode, which operate up to their 300,000 golden sweet spot or full model limits). Claude statusline scripts (`statusline.ps1` / `statusline.js`) must strictly calculate context consumption against this 150,000 cap to safeguard against Claude's quadratic cache cost escalation $O(N^2)$.
  Display format: `ctx {tokens}k/150k ({pct}%)` with calibrated thresholds: Cyan (<60%), Yellow (60–84%), Red (>=85% / strategic clear trigger).

---

## 5. Non-Negotiable Boundaries & Security Perimeter

1. **Quarantine / Strict No-Touch Areas**:
   - **`Digital persona/`**, raw personal passport scans/documents, and root personal payment/tax PDFs are strictly confidential.
   - AI agents must **NEVER** read, modify, index, or transmit raw personal identity files from these directories.
2. **Secrets & Environment Variables**:
   - **NEVER** hardcode credentials or tokens in markdown, scripts, or git commits.
   - All secrets must be loaded from `.env`.
3. **Language Policy**:
   - **Terminals, CLIs, Git Commits, and Code**: English strictly.
   - **Client Deliverables, Proposals, and Ad Copy**: Flawless, professional Arabic (RTL compliant), unless specifically requested otherwise in the client profile.
4. **Rule 50 (English Numerals & Zero-Pricing Standard)**:
   - Always use Western Arabic numerals (`0-9`) exclusively in all official documents, reports, proposals, and Excel sheets. Never use Eastern Arabic numerals (٠-٩).
   - Never invent or assume unconfirmed prices. Use smart formulas (e.g. `=IF(K9="","",I9*K9)`) leaving blank cells until confirmed.
5. **Rule 51 (Clean Templates & Zero Cross-Project Bleed)**:
   - Templates in `_Agency_Templates/` and MCP docx generators must be 100% generic structural templates.
   - Never allow data from one client (e.g., Shajan) to bleed into another (e.g., YAZ or Apex).
   - **Rule 51+ (Data Source Independence — Standalone App Invariant)**: When building any financial or management app from an external data source (Excel, CSV, JSON):
     1. **Phase 1 (Active):** Build with real client data for verification (`initialData_[client].js`).
     2. **Phase 2 (Purge):** `initialData.js` (the template) MUST be purged to generic professional roles (e.g., `treasurer`, `site_engineer`) and all balances zeroed (`0.00`).
     3. **Phase 3 (Dual Output):** The build script MUST produce TWO standalone HTML files: one with live client data (`_النشطة_البيانات_الحالية.html`) and one blank template (`_نسخة_جديدة_فارغة.html`).
     4. **Absolute Prohibitions in template files:** No personal names, no Excel cell references (e.g., `C92`, `C94`), no employee-named tabs or labels anywhere in UI, documentation, or export headers.
6. **Rule 1 (BiDi & RTL Isolation & Artifact Pre-Flight Gate)**:
   - **Mandatory `<div dir="rtl">` Wrapper:** Every Arabic markdown artifact (`implementation_plan.md`, `walkthrough.md`, `learning_proposal.md`, reports, client summaries) MUST strictly begin with `<div dir="rtl">` on Line 1 and end with `</div>` on the final line.
   - **Strict BiDi Isolation:** In all Arabic markdown, HTML, and SVG files, isolate all English terms, acronyms, model names, CLI commands, code symbols, and file paths using `<bdi>` (e.g. `<bdi>OpenCode CLI</bdi>` or `<bdi>`web/print/`</bdi>`) or `<span dir="ltr">` to prevent punctuation and line-flow inversion.
7. **Rule 7 (Digital Concierge & Government Visa Services Standard)**:
   - **Role Definition:** Act strictly as a "Tech Facilitator & Digital Concierge" assisting non-tech-savvy clients with official government self-service portals (e.g. Nusuk B2C at `umrah.nusuk.sa`, 96-hour Transit Visa via Saudia/Flynas).
   - **100% Upfront Collection Rule:** Always collect the full government fees + agency facilitation fees from the client upfront before initiating payment. Use a dedicated prepaid digital card (STC Pay / Urpay) loaded only with the exact transaction amount to ensure 100% financial security.
   - **No Unlicensed Brokering:** Never practice commercial visa brokerage as an individual (violates Saudi Labor Law Article 39 and Anti-Concealment). All commercial expansions must route through official B2B partnerships with licensed Saudi Umrah operators and transportation companies in Makkah and Madinah.
8. **Rule 8 (Zero-Day Git Init & Proactive User Reminder Standard)**:
   - **Mandatory Repository Foundation:** Every software engineering project must have an active local Git repository (`.git`) linked to GitHub from Day 0 before writing application code.
   - **Proactive AI Reminder Gate:** If the user starts a project or requests feature development in a workspace that lacks `.git`, the AI assistant MUST proactively pause, alert, and remind the user: *"⚠️ تنبيه حوكمة: مساحة العمل لا تحتوي على مستودع Git مهيأ (`.git`). يجب إنشاء المستودع وضبط الحجر الصحي في `.gitignore` وتثبيت أول commit لحماية المخرجات وتفعيل بروتوكول التصفير الاستراتيجي (Hook 25)."*
   - **Strict AI Quarantine in `.gitignore`:** The repository's `.gitignore` must immediately quarantine: `Digital persona/`, `.agents/`, `.claude/`, `fleet_orders/`, `fleet_templates/`, `.env`, and build caches (`**/build/`, `**/.gradle/`, `*.log`).
9. **Rule 9 (Decimal-to-Float JSON Invariant for API Endpoints)**:
   - In FastAPI/Python endpoints querying raw SQL monetary amounts or database aggregates (`sum(amount)`), **NEVER** return raw `Decimal` objects in response payloads.
   - Pydantic v2 automatically serializes raw `Decimal` objects as JSON strings (`"500.00"`), breaking client arithmetic and test comparisons.
   - All response dictionaries containing monetary/numeric fields must be routed through the recursive `_convert()` serializer (e.g. `return _resp(...)`) to guarantee all `Decimal` values are converted to rounded `float` numbers (`round(float(val), 2)`).
10. **Rule 10 (Room Topological Sync & Outbox Protection Invariant)**:
    - In offline-first Android apps using Room with foreign keys enabled (`PRAGMA foreign_keys = ON`), local cache replacement/invalidation must strictly follow reverse topological order (leaf children ➔ parent roots) inside a single `@Transaction` method.
    - The mutation outbox (`pending_writes`) must NEVER declare foreign keys and must NEVER be cleared or touched during pull sync routines.
11. **Rule 52 (Production DB Migration Parity & Physical ADB Viewport Shift Standard)**:
    - **Production Schema Parity Gate:** Before running live end-to-end integration tests or classifying HTTP 500 errors as application code defects, agents must verify that all incremental migration scripts (`db/postgres/*.sql`) have been applied to the remote/container production database (`edutrack-db-1`), executing missing migrations immediately to prevent schema mismatch crashes on new columns or tables.
    - **Physical ADB Automation Protocol:**
      - **Screen Sleep Prevention:** Always set `settings put global stay_on_while_plugged_in 3` upon ADB device connection to prevent display timeouts during automated testing.
      - **Biometric/Bouncer Respect:** When a device displays secure keyguard (`Bouncer`), automation scripts must wait gracefully for one-time user biometric/pattern unlock rather than prematurely failing or exiting.
      - **Soft-Keyboard Viewport Shift Invariant:** On physical devices, software keyboards shift Jetpack Compose viewports upward. Avoid blind coordinate taps while the keyboard is visible; navigate form fields using `KEYCODE_TAB` (61) / `ImeAction.Next`, and dismiss the software keyboard explicitly with `KEYCODE_BACK` (4) before clicking submission buttons.
12. **Rule 53 (Outbox Media Upload Idempotency & Streaming Upload Security Standard)**:
    - **Client Outbox Idempotency:** In offline-first mobile sync routines handling binary attachments, if media files are uploaded to cloud storage before dispatching the primary mutation API call, any successful file upload must immediately update the pending payload in the local database (`pendingWriteDao().upsert(item.copy(payloadJson = json.toString()))`). This prevents re-uploading identical binary files on worker retries, conserving user bandwidth and preventing orphaned server storage.
    - **Streaming Memory Safety:** Never read entire binary attachments into memory via `file.readBytes()`. Client-side network requests must stream directly from storage via `file.asRequestBody(mediaType)`. Backend endpoints must enforce upfront `Content-Length` checks (e.g. 25 MB cap) and stream disk writes in chunks (`async for chunk in request.stream()`) to prevent RAM exhaustion under concurrent load.
    - **Reverse Proxy Path Prefix Invariant:** When hosting user-uploaded static media behind an edge reverse proxy (Caddy/Nginx) configured to forward dedicated API prefixes, public media storage must be mounted directly under the forwarded API route (e.g. `/api/v1/static/uploads/`) to guarantee immediate accessibility without requiring host-level reverse proxy reconfiguration.
    - **Upload Security & XSS Shield:** All public upload endpoints must enforce an explicit extension allowlist (`pdf, jpg, jpeg, png, m4a, aac, mp3`) and explicit MIME type assignment. All scriptable formats (`.html`, `.svg`, `.js`, `.php`, `.sh`) are strictly prohibited to prevent Stored XSS and remote code execution vulnerabilities.
13. **Rule 54 (Client-Server DTO Parity & Kotlin optString Sanitization Standard)**:
    - **Full DTO Schema Parity:** When adding or extending fields in server API payloads or database models, mobile Data Transfer Objects (DTOs) and repository mappers must immediately mirror those fields. Never omit newly added server columns or hardcode them to null in client mappers, as this causes silent data loss during background pull synchronization cycles.
    - **Kotlin `optString` Null Invariant:** In Kotlin routines parsing `org.json.JSONObject`, avoid passing `null` as the fallback argument (`json.optString(key, null)`), which risks returning the literal string `"null"`. Always sanitize strings using the idiomatic pattern: `json.optString(key).takeIf { it.isNotBlank() }` to guarantee genuine nullable values.
14. **Rule 55 (Elementor Pro Form Tracking & CRO Double-Tagging Invariant)**:
    - **AJAX Form Push:** In WordPress sites built with Elementor Pro, never rely on Form Actions (Webhooks) to push client-side analytics events because they execute server-side (PHP) and cannot access `window.dataLayer`. The canonical standard is attaching a jQuery listener to Elementor's client-side `submit_success` event to cleanly push events to `window.dataLayer`:
      ```html
      <script>jQuery(document).on('submit_success',function(){window.dataLayer=window.dataLayer||[];dataLayer.push({event:'lead_form_submit'});});</script>
      ```
    - **Double Injection & Cache Bypass Invariant:** When an official connector like Site Kit by Google is active, never manually duplicate GTM containers or Google Ads gtag snippets in secondary injection plugins. Always verify live DOM scripts using a cache-busting query (`?nocache=1`) against edge/server caches (e.g. LiteSpeed Cache) to guarantee `gtm.js` and `AW-` tags load strictly once.
    - **FastMCP Google Ads API v22 Standard:** FastMCP servers interfacing with Google Ads API must target the active cloud API version (`v22`), strictly isolate stdio JSON-RPC streams by routing all logging to `sys.stderr`, and expose modular tools (`list_customers`, `get_api_status`, `get_campaigns`, `get_conversion_actions`, `run_gaql`).
15. **Rule 56 (Commercial Contract Hygiene & Anti-Skill Bleed Standard)**:
    - **Strict Anti-Skill Bleed:** When using business, growth, or framework skills (e.g. `hormozi-growth-advisor` or marketing playbooks) to draft commercial contracts, proposals, or Scopes of Work (SOW), apply principles silently in substance. **NEVER** leak skill names, author titles, buzzwords, or marketing hype (e.g. "هورموزي", "Done-For-You", "Grand Slam", "عكس المخاطر 100%") into contract headings or binding clauses. Official contracts must strictly use formal, authoritative Saudi commercial legal terminology.
    - **No Self-Imposed Payment Waivers or Penalties:** Agency payment schedules must strictly follow milestone delivery and client approval ("لا دفع قبل المشاهدة والاعتماد"). AI agents must **NEVER** invent or insert unilateral financial penalties, payment waivers, or delay discounts on the agency unless explicitly directed with exact amounts by the user.
    - **Client Deliverable Precision (Website vs. Landing Page & Manual Entry):** Rigorously distinguish between an official full website and a marketing landing page. When a client opts for manual data entry, configure database UI inputs for manual entry enablement and staff training, omitting backup file migration clauses.
16. **Rule 57 (Headless PDF Purity & Browser Header-Footer Sanitization Standard)**:
    - **Mandatory Headless Header/Footer Suppression:** When generating or rendering PDF deliverables from HTML via Chromium or Microsoft Edge Headless (`--print-to-pdf`), agents must **ALWAYS** pass `--no-pdf-header-footer`. Never generate or deliver client reports or official documents that contain raw file paths (`file:///...`), execution dates, or browser page counters (`X/Y`) in page margins.
    - **Print CSS Calibration & Page Boundary Invariant:** In HTML documents intended for print or PDF conversion (`@media print`), always configure `@page { size: A4 portrait; margin: 8mm 12mm; }` and `-webkit-print-color-adjust: exact;`. Container heights, margins, and table paddings must be calibrated to ensure documents fit their designated page count without unintended spillover lines onto trailing blank pages.
    - **Bounding Box Margin Redaction Fallback:** When sanitizing existing rendered PDFs containing leaked browser headers or footers, apply non-destructive bounding box redaction via `pymupdf` targeting the top (`y < 30`) and bottom (`y > 810`) strips to erase leaked artifacts while strictly preserving all legitimate document content.

---

## 6. Scope Guard & Minimal Sufficient Change Directive

All agents and CLI harnesses must complete tasks with the **smallest sufficient change** while rigorously addressing the **root cause**. Explicit user instructions for a given task override default behaviors below.

### 6.1. Pre-Edit Discovery & Context Restraint
- **Targeted Inspection**: Read only immediately relevant code, call paths, and conventions using `lean-ctx` (`mode='signatures'`) or line-range slices. Do **NEVER** read entire repositories or large files (`mode='full'`) for small modifications.
- **Skill Parsimony**: Load only skills strictly required for the immediate task; do not load expansive multi-agent workflows for casual keyword triggers.
- **Direct Execution**: Make clear, small fixes directly. Draft a formal `implementation_plan.md` only when the approach is ambiguous, architecture-altering, or high-impact.
- **Autonomous Routine Decisions**: Resolve routine implementation details independently. Escalate only when conflicting interpretations lead to materially divergent outcomes.

### 6.2. Implementation Hierarchy & Anti-Band-Aid Mandate
- **Implementation Precedence**: Before writing any new code, evaluate solutions in this strict order:
  1. Existing patterns, utilities, and helper functions within the repository.
  2. Standard library and built-in runtime/platform capabilities.
  3. Already installed dependencies and frameworks.
  4. Only then, minimal new code strictly necessary for the active scope.
- **Root-Cause Invariant**: Address root causes directly. Never deploy superficial band-aids (e.g. suppression blocks, blind `try/except: pass`, or brittle hacks). Do not introduce speculative abstractions, factories, or compatibility layers for hypothetical future needs.
- **Dependency Justification**: Never introduce new external libraries or packages without explicit technical justification explaining why existing installed dependencies are insufficient.
- **Scope Discipline**: Fix nearby issues only if they directly block the active task; otherwise, flag them as technical debt for follow-up. Avoid unrelated refactoring or full-file rewrites.
- **Dead Code Cleanup**: Purge replaced code paths immediately. Retain legacy shims only when explicit backward compatibility is mandated.
- **Non-Negotiables**: Never compromise validation, error handling, security, or accessibility (WCAG AA) in the name of brevity.

### 6.3. Escalation & Authorization Gates
- **Continuous Execution**: Keep implementing, verifying, and fixing within authorized scope without repeatedly pausing to ask routine confirmation.
- **Mandatory Escalation Triggers**: Halt and request explicit user confirmation before:
  - Material scope expansion beyond original intent.
  - Adding unapproved recurring costs or changing production permissions.
  - Executing irreversible or destructive system actions.
- **Analysis-Only Scope**: If requested only to analyze, inspect, or audit, report structured findings—do not modify code.

### 6.4. Fleet-Calibrated Verification & Testing Invariant
- **Fleet Testing Monopoly**: Canonical rule in §4 "Mandatory Testing & Verification Monopoly". Non-Claude agents do static inspection only.
- **Verification Parsimony**: Reuse existing tests first. Add new tests only for real behavior and genuine regression risks—never mechanical tests that mirror implementation details.
- **Ephemeral Verification**: Keep scratch scripts in temporary folders; do not leave temporary verification scripts as permanent repository tests.
- **Check Restraint**: Once checks pass, repeat them only if subsequent edits are made, failures emerge, or unresolved edge cases remain.

### 6.5. Scope Growth Circuit Breaker
If the implementation plan begins to demand future-only abstractions, unrelated refactoring, peripheral features, or redundant validation loops:
1. Immediately pause and prune the excess work.
2. Re-anchor strictly to the core requirement.
3. Finish execution strictly within the originally authorized boundary.

### 6.6. Definition of Done (DoD)
A task is considered **DONE** only when:
1. The requested functionality functions completely and verification evidence is established.
2. Every modified line directly serves the task; all scratch files, debug logs, and dead code have been purged.
3. A concise summary is delivered detailing the result, verification evidence, and any unresolved items or follow-ups.
