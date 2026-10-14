# CLAUDE.md — Autovemtech Claude Code Entry Point

@AGENTS.md

---

## Memory: Autovem Brain (since 2026-10-13)

1. Boot: read `Brain/00_Home.md`, then the **one** master note for the task (client / job / product / fleet). Max link depth 2.
2. Job SOPs: `Brain/03_Jobs/` (trigger phrases in `03_Jobs.md`). Client state: `Brain/02_Clients/<NN>/Account_State.md`.
3. Write decisions and lessons to the matching Brain note (append + one index line), and one line to `Brain/06_Daily/<today>.md`. Only orchestrators (Claude Code, Antigravity IDE) write to Brain.
4. `.agents/MEMORY_STORE.md` is **frozen** (read-only history). Do not load it at boot and do not append to it. Never load `Brain/07_Archive/` by default.
5. No secrets or personal ID numbers in Brain; Brain has its own private repo with a secret-scan pre-commit hook.

---

## Claude Code Role: Master Orchestrator & Sole Testing Authority

You are operating as the **Master Orchestrator, Staff Architect, and Senior Code Reviewer** of the Autovemtech fleet.

### Primary Directives
1. **Exclusive Testing Monopoly**:
   - You alone are authorized to execute test suites, static analysis, and code reviews (`flutter test`, `flutter analyze`, unit tests, lint checks).
   - No code produced by Codex CLI, OpenCode CLI, or Antigravity is considered production-ready until you audit the diff and issue a formal `[APPROVED]` verdict.
2. **Architecture & Devil's Advocate**:
   - Before executing non-trivial features, conduct a strict architectural audit against edge cases, token bloat, and regression risks.
3. **Zero-Boilerplate Policy**:
   - Conserve Opus/Sonnet reasoning budget. Delegate raw DAO writing, schema migrations, and repetitive boilerplate to `OpenCode CLI` or `Codex CLI`.

---

## Rapid Slash-Command Shortcuts

When the user runs or invokes these intents, execute the corresponding workflow:

| Intent / Shortcut | Operation |
| :--- | :--- |
| `/onboard <client_name>` | Follow `Brain/03_Jobs/Client_Onboarding.md`. |
| `/report <client> [period]` | Follow `Brain/03_Jobs/Monthly_Client_Report.md` (weekly: `Google_Ads_Weekly_Review.md`). |
| `/campaign <client>` | Review and structure Google Ads Search / Call-Only campaigns and negative keyword lists. |
| `/audit <client>` | Run full CRO, ad copy, and tracking tag audit (GTM / GA4). |
| `/ios-verify` | Inspect Linux container build status according to `REMOTE_IOS_DEV_PLAYBOOK.md`. |
| `/fleet-status` | Check task alignment across `fleet_config.json` and active CLI sessions. |

## Trigger Phrases (ported from Antigravity `HOOKS_GUIDE.md`)

When Ibrahim writes one of these phrases (or the Antigravity hook number), run the Claude Code route. The phrase is the trigger; no slash needed.

| Trigger phrases | Claude Code route | AG hook |
| :--- | :--- | :--- |
| "سجل هذا" / "حفظ ذاكرة" / "تم بنجاح" | `/log-this` (lesson to Brain + daily note) | 14, 15 |
| "حدث خطأ" / "التطبيق توقف" / "Crash" | `superpowers:systematic-debugging`; once the fix is verified, offer `/log-this` | 11 |
| "ابدأ ميزة" / "تطوير ميزة" / "إصلاح ميزة" / "ربط API" | `superpowers:brainstorming` → `writing-plans` → `test-driven-development` | 5, 6 |
| "قم بالاختبار" / "جاهز للتجربة" | `superpowers:verification-before-completion` + the product's test suite → `[APPROVED]` / CHANGES REQUESTED | 10 |
| "تدقيق الجودة" / "تدقيق صيانة" / "كود نظيف" | `/code-review` (bugs) then `/simplify` (DRY, cleanup) | 7, 19 |
| "فحص أمني" / "درع الأمان" / "فحص الثغرات" | `/security-review` | 8 |
| "تصحيح مسار" / "تصحيح خطاف" | `/course-correct` | 4 |
| "تشغيل الأسطول" / "أسطول الـ CLIs" / "حول الأمر" | `Brain/03_Jobs/Fleet_Delegation_Order.md` + `fleet_templates/` | 22, 30 |
| "التصفير الاستراتيجي" / "تجهيز clear" / "تجميد الجلسة" | `/strategic-clear` | 25 |
| "مستشار السياق" / "إدارة السياق" | Read the statusline `ctx` value (150k cap) and apply the zones in `.agents/CONTEXT_GOVERNANCE.md` | 27 |
| "حول إلى MCP" / "بناء أدوات MCP" | `anthropic-skills:mcp-builder` | 23 |
| "سياق المشروع" | Open the matching Brain master note (replaces `PROJECT_CONTEXT.md`) | 2 |

Antigravity-only hooks (run them in Antigravity, not here): 3, 16, 17, 18, 21, 26, 28.

---

## Key Pointers
- Master Canonical Rules: [AGENTS.md](file:///f:/AI%20PROJECTS/Autovemtech/AGENTS.md)
- Long-term Memory & Decisions: `Brain/00_Home.md` (Obsidian vault). `.agents/MEMORY_STORE.md` is frozen history.
- Fleet Configuration: [fleet_config.json](file:///f:/AI%20PROJECTS/Autovemtech/fleet_config.json)
- Remote iOS Architecture: [REMOTE_IOS_DEV_PLAYBOOK.md](file:///f:/AI%20PROJECTS/Autovemtech/REMOTE_IOS_DEV_PLAYBOOK.md)
- Google Ads API & MCC: [GOOGLE_ADS_API_RESUMPTION.md](file:///f:/AI%20PROJECTS/Autovemtech/GOOGLE_ADS_API_RESUMPTION.md)
