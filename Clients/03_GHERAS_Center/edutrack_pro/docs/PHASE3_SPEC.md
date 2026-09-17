# EduTrack Pro — Phase 3 Specification & Delivery Record: Web Dashboard & Live Deployment

- **Owner**: Autovem Master Architect
- **Client**: Gheras Center (`Clients/03_GHERAS_Center`)
- **Status**: **[DELIVERED & LIVE IN PRODUCTION]**
- **Live URL**: https://gheras.autovem.tech/web/dashboard/
- **VPS Host**: `root@187.55.226.225:/opt/edutrack` (Hostinger Cloud VPS KVM 1)
- **Commit Reference**: `bcf4d21`

---

## 1. Scope & Delivered Architecture

Phase 3 transitioned the static management views and MVP prototypes into a fully production-grade, dynamic Web Dashboard for Gheras Center administrators:

1. **Interactive 24-Tool Grid (`home.js`):**
   - Transformed the legacy dashboard into an interactive, 3-column CSS Grid.
   - All 24 tools and navigational links mapped with dynamic event listeners.
   - Replaced placeholder links with active routing and informative toast messages for pending features.

2. **Real Backend KPI Integration (`reports/daily`):**
   - Replaced synthetic mockup numbers with live endpoint statistics:
     - Total Students
     - Today's Present / Absent
     - Monthly Expenses
     - Monthly Collected Revenue
     - Outstanding Total Balance (styled with `.red` highlight indicator)
   - Dynamic user greeting pulling directly from the authenticated session (`#user-name`).

3. **Accessibility (A11y) & Session Security:**
   - Enforced `role="button"` and `tabindex="0"` on navigational controls.
   - Implemented `Enter` and `Space` keyboard listeners across all interactive nodes.
   - Decoupled authentication state from sub-views, routing logout actions through the custom event `gheras:logout` governed centrally by `app.js`.

4. **Timezone & Date Parsing Hardening:**
   - Standardized Arabic date rendering by appending `T00:00:00` to ISO-8601 date strings (`YYYY-MM-DD`), preventing timezone rollback under Riyadh time (UTC+3).

---

## 2. Production Deployment & Verification

- **Deployment Script**: `deploy/push.sh`
- **Reverse Proxy**: Caddy server on Hostinger VPS routing `/api/*` to Uvicorn (`127.0.0.1:8000`) and serving static assets from `/opt/edutrack/web/` and `/opt/edutrack/assets/`.
- **Directory Protection**: Strict whitelist rule; requests outside allowed web assets return `HTTP 404`.
- **Smoke Test Verification Results**:
  - `GET https://gheras.autovem.tech/api/v1/health` -> `HTTP 200`
  - `GET https://gheras.autovem.tech/web/dashboard/` -> `HTTP 200`
  - Sensitive Paths Blocked: `HTTP 404`

---

## 3. Operational Lessons Learned

1. **PowerShell vs. Linux Shell**:
   - `export` is an invalid cmdlet in Windows PowerShell; environment variables must be defined via `$env:VAR="value"` or encapsulated in bash runners.
2. **Path Resolution in MSYS/Git Bash on Windows**:
   - Tilde expansion (`~/.ssh/...`) is susceptible to path mangling (`C:UsersKt/...`). Absolute paths with `-o StrictHostKeyChecking=no` guarantee stable automated headless deployments.
