# EduTrack Pro — System Verification Report

<div dir="rtl">
<bdi>Autovem</bdi> — عميل <bdi>GHERAS Center</bdi> | تحقق من البنية التحتية السحابية وقاعدة البيانات
</div>

| Field | Value |
|---|---|
| Date | 2026-09-26 |
| Executed by | OpenCode CLI (Worker A) — `opencode-go/glm-5.3-flash` |
| Target | `https://gheras.autovem.tech` — VPS `srv1810150.hstgr.cloud` (187.55.226.225) |
| Status | 🟢 **OPERATIONAL — with 3 documented deviations from spec** |

---

## 1. Public URL Checks (curl)

Verified with `curl -4` (IPv4). Note: default dual-stack resolution on the
operator workstation returns a broken NAT64 AAAA (`64:ff9b::bb37:e2e1`) that
causes curl (schannel) to hang → HTTP code `000`. This is a **client-side DNS
observation only**; IPv4 and remote checks confirm the service is fully up.

| URL | HTTP | Notes |
|---|---|---|
| `https://gheras.autovem.tech/api/v1/health` | `200` | `{"status":"ok","version":"2.0.0"}` |
| `https://gheras.autovem.tech/web/dashboard/` | `200` | `dashboard/index.html` verified separately (200) |
| `https://gheras.autovem.tech/web/print/` | `404` | **Structural, by design** — site block responds 404 unless a specific template path is requested; templates live under `/web/print/templates/` with `print_engine.js` (200), `print.css`, logo |
| `https://gheras.autovem.tech/assets/EduTrackPro.apk` | `200` | **Content-Length: 21,065,411 bytes (~20.1 MiB)**, `Content-Type: application/vnd.android.package-archive`, Last-Modified: Thu, 24 Sep 2026 10:43:47 GMT, `X-Content-Type-Options: nosniff` |

## 2. VPS / Docker Status (SSH via `~/.ssh/edutrack_deploy_key`)

```
NAMES            STATUS                  PORTS
edutrack-api-1   Up 11 hours (healthy)   127.0.0.1:8000->8000/tcp
edutrack-db-1    Up 9 days (healthy)     5432/tcp
pocketbase       Up 2 months             0.0.0.0:8090->8090/tcp, [::]:8090/...
```

- Caddy (systemd, host) `active`, listening :80/:443, TLS valid (curl 200 via SNI)
- Site block: `/etc/caddy/sites/gheras.caddy` (deploy.sh-rendered, static allow-list + `/api/*` → 127.0.0.1:8000)

## 3. Database Verification (`edutrack-db-1`, PostgreSQL)

Living document — naming in the deployment drifted from this request's spec:

- **DB user**: `gheras_app` (not `edutrack`)
- **Database**: `gheras_edutrack` (not `edutrack`)
- **Actual schema**: `evaluations` + `student_attendance` + `staff_attendance` (not `daily_evaluations` / `attendance`); 47 tables total

### Row counts (exact output)

| Requested table | Actual table used | count |
|---|---|---|
| `users` | `users` | **7** |
| `students` | `students` | **1** |
| `daily_evaluations` | `evaluations` | **3** |
| `attendance` | `student_attendance` | **7** |

- `staff_attendance`: **0** rows (extra table checked)
- On-container API health (direct uvicorn 127.0.0.1:8000): `{"status":"ok","version":"2.0.0"}`

## 4. Summary & Follow-ups

**Overall: PASS.** API, dashboard, static assets, APK distribution, Caddy TLS
routing and the PostgreSQL container are all healthy.

Follow-ups (non-blocking, for next session):
1. **Mac curl / IPv6**: the operator workstation's NAT64 AAAA record gives curl
   a hang → `000`. Consider an IPv6-capable tunnel or remove/adjust the AAAA
   record if dual-stack clients are expected on such networks.
2. **Naming drift**: update runbooks to use `gheras_app@gheras_edutrack`, and
   `evaluations` / `student_attendance` for table names.
3. **`/web/print/` 404**: if a friendly index page is desired for the print
   directory, add a small `index.html` under `/opt/edutrack/web/print/` —
   otherwise no action needed (templates are reachable directly).
