# Implementation Plan: Development Environment Comprehensive Audit & Hook 31 Meta-Subagent Forge

**Author**: Claude Code CLI (Opus Max) — Master Orchestrator  
**Challenger & Plan Auditor**: Codex CLI (GPT-6.1 Soul) — Adversarial Stress & Edge-Case Reviewer  
**Status**: **RATIFIED & APPROVED FOR EXECUTION (Codex Safeguards Fully Integrated)**  
**Execution Date**: 2026-10-25  
**Compliance**: Invariants [C-10] to [C-107], Rules 50, 51, and Multi-CLI Fleet Standards  

---

## 1. Executive Summary & Architecture Overview
This initiative executes a rigorous, multi-layer diagnostic of the Autovemtech development environment across 5 distinct operational tiers, remediates architectural tool gaps for Hook 31 (Multi-CLI Fleet Teamwork Preview), and deploys the `meta-subagent-forge` alongside three pillar-aligned domain architects.

---

## 2. 5-Layer Environment Diagnostic Specifications

### Layer 1: Hardware & VRAM Telemetry (GTX 1650 4GB Safety)
- **Target**: NVIDIA GeForce GTX 1650 (4096 MiB VRAM).
- **Invariants Checked**: [C-33] AI Model Directory Junctions, [C-34] Local Ollama Hardware Matrix (strict 4GB sequential role switching, default `qwen2.5-coder:1.5b`).
- **Telemetry Gathered**: Total VRAM, Active VRAM, System RAM, Thermal performance, active model processes.

### Layer 2: Multi-CLI Fleet Readiness on PATH
- **Target**: Verify presence, version, and execution capability for:
  - `claude` (Claude Code CLI — Opus Max / Sonnet 5.5)
  - `opencode` (OpenCode CLI — GLM 5.3 Flash / Muse Spark 1.3)
  - `cline` (Cline CLI — GLM 5.3 Flash medium)
  - `agcli` / Antigravity CLI (Gemini 3.8 Flash High)
  - `codex` / ChatGPT Desktop (GPT-6.1 Soul)
  - `ollama` (Local model server)
- **Invariants Checked**: [C-21] Collaborative Multi-CLI Fleet Architecture, [C-25] Autonomous Direct Dispatch, [C-35] System-Installed Claude Code CLI Baseline.

### Layer 3: FastMCP Servers Configuration & stdio Health
- **Target**: Inspect active MCP configurations in:
  - `/home/t/.gemini/antigravity/mcp/`
  - Workspace `.env` / `tools/google_ads_mcp/`
- **Invariants Checked**: [C-10] FastMCP Zero-Leak stdio Rule (buffer isolation, stderr-only logging), [C-106] Google Business Profile & Authenticated Browser CDP.

### Layer 4: Runtimes, SDKs & Path Junctions
- **Target**:
  - Flutter SDK: Verify `C:\flutter_sdk` Directory Junction per [C-26].
  - Android SDK: Validate `platform-tools/adb.exe`, `build-tools`, `platforms` under `%LOCALAPPDATA%\Android\Sdk`.
  - Node.js & npm: Verify version consistency (v20+).
  - Python runtime & `uv` package manager: Verify Python 3.12+ / 3.14 on PATH.
  - Git: Validate working tree integrity, hooks directory junction [C-89].

### Layer 5: Subagents & Skills Gap Analysis (Hook 31 Suite)
- **Target**: Inventory all subagents in `.agents/agents/`, `.agents/skills/`, `~/.gemini/config/skills/`, `~/.claude/skills/`.
- **Gap Identification**:
  - Pillar 1 (Digital Marketing & Client Growth): Missing unified `growth-marketing-pilot`.
  - Pillar 2 (AI Solutions & Agent Systems): Missing autonomous model-routed `meta-subagent-forge`.
  - Pillar 3 (Mobile & Web App Engineering): Missing dedicated `android-pro-architect` and `modern-web-architect`.

---

## 3. Subagent Engineering Specifications

### 1. `meta-subagent-forge` (Pillar 2 Core Engine)
- **Purpose**: Autonomous, self-refining engine that identifies missing subagent roles during runtime and scaffolds production-grade `SKILL.md` + `.md` agent definitions.
- **Model Routing**:
  - Complex reasoning, prompt engineering, multi-step workflows -> `Claude Opus 5.5 / Opus Max`.
  - Deep algorithmic logic, math engines, parsers, C++/Dart FFI -> `Codex GPT-6.1 Soul`.
  - Scaffolding, CLI scripts, boilerplate -> `OpenCode GLM 5.3 Flash`.
- **Output Standards**: Valid YAML frontmatter, `<div dir="rtl">` Arabic descriptions, strict tool whitelist, Rule 50/51 invariants.

### 2. `growth-marketing-pilot` (Pillar 1 Architect)
- **Purpose**: Orchestrates Google Ads campaigns (PMax, Search, Call-Only), CRO landing page architectures, GTM click-to-call hooks, and weekly client reporting adhering strictly to Rule 50 (0-9 numerals) and Rule 51 (Clean Templates).

### 3. `android-pro-architect` (Pillar 3 Mobile Architect)
- **Purpose**: Kotlin, Jetpack Compose, Material 3, Room DB topological sync [C-72], IO dispatching [C-79], TalkBack accessibility [C-09], Google Play Console policies [C-56].

### 4. `modern-web-architect` (Pillar 3 Web Architect)
- **Purpose**: High-performance semantic HTML5, zero-bloat vanilla CSS, print-ready styling (`@media print`), 60fps animations [C-105], cross-browser QA [C-96].

---

## 4. Triple Deployment Protocol
Each generated skill must be synchronously placed in:
1. Local Workspace: `.agents/skills/<name>/SKILL.md` and `.agents/agents/<name>.md`.
2. Global System: `~/.gemini/config/skills/<name>/SKILL.md` and `~/.claude/skills/<name>/SKILL.md`.
3. Central Repository: `/mnt/f/AI PROJECTS/Claude+Antigravity/.agents/skills/<name>/SKILL.md`.

---

## 5. Codex Adversarial Safeguards Integration (Mandatory Gates)
1. **[C-34] Ollama VRAM Protection Gate**: Local fallback models capped strictly at `qwen2.5-coder:1.5b`. No 7B/8B models may be spawned on the GTX 1650 (4GB) to prevent CUDA OOM.
2. **Tool Schema Deserialization Gate**: All subagents must strictly adhere to the modern Google Antigravity Subagent schema: `read_file`, `edit_file`, `write_file`, `run_command`, `manage_task`, `send_message`, `schedule`, `invoke_subagent`, `define_subagent`, `manage_subagents`, `search_web`, `ask_question`. Legacy tool names (`view_file`, `replace_file_content`, `write_to_file`) are strictly blocked.
3. **[C-104] Bidirectional Path Normalization Gate**: All deployment operations must execute `resolve_path()` to transparently map Linux and Windows paths, creating parent directories safely.
4. **Rule 50 & 51 Bleed Prevention Gate**: System prompts must explicitly forbid Eastern Arabic numerals (`٠-٩`) and mandate zero-pricing / clean placeholders.
5. **[C-103] Anti-Hollow Build Mandate**: Agents must stop at Stage 1 scaffolding when external credentials are required, generating `SETUP_CHECKLIST.md` rather than mocking credentials.

---

## 6. Verification & Sign-Off Gates
1. Run diagnostic runner script `tools/dev_env_audit.py` to produce structured `AUDIT_REPORT.json`.
2. Validate syntax of all generated YAML and Markdown files.
3. Synchronize 8 HTML web applications via `python3 scripts/update_html.py`.
4. Log PILOT session line in `Brain/06_Daily/2026-10-25.md` per [C-107].
5. Execute `pilot_report.py` and enforce exclusive `Claude Code CLI [APPROVED]` sign-off.
