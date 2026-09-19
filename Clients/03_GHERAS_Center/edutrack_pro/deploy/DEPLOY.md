# EduTrack Pro — VPS Deployment Runbook

Clean, fresh-instance deployment (no legacy data import) of the API + web dashboard on the
Hostinger VPS (`srv1810150.hstgr.cloud`, `187.55.226.225`, Ubuntu 24.04 + Docker) behind Caddy.

## Topology

```
Browser ──HTTPS──> Caddy (host service, :80/:443, auto TLS)
                     ├── /api/*            -> 127.0.0.1:8000  (api container, uvicorn)
                     ├── /web/*, /assets/* -> /opt/edutrack   (dashboard, 11 print templates, logo)
                     └── everything else   -> 404             (db/, server/, deploy/, *.md never served)
                                api ──compose network──> db (postgres:16-alpine, no published port)
```

Same-origin by design: the dashboard calls `/api/v1` relatively, so `CORS_ORIGINS` stays empty.

## Files

| File | Runs on | Purpose |
| --- | --- | --- |
| `deploy/push.sh` | developer machine | `tar` the `assets db server web deploy` tree to `/opt/edutrack`, then run `deploy.sh` over SSH |
| `deploy/deploy.sh` | VPS (root) | idempotent: secrets → db → migrations check → app-role password → API build → admin seed → Caddy site → smoke test |
| `deploy/docker-compose.prod.yml` | VPS | `db` + `api` (+ optional `caddy` under `--profile edge` when the host has no Caddy) |
| `deploy/Caddyfile.gheras` | VPS | site block template; rendered to `/etc/caddy/sites/gheras.caddy` |
| `server/Dockerfile` | build | `python:3.12-slim` + `uv sync --frozen` from `server/uv.lock`, non-root user |
| `deploy/.env` | VPS only | generated on first run, `chmod 600`, never synced or committed |

## Pre-requisites (one-time, by Ibrahim)

1. **SSH key on the VPS** — `~/.ssh/authorized_keys` on the server must hold the developer's public key
   (`~/.ssh/vps_secure_key.pub` or `~/.ssh/id_ed25519.pub`). As of 2026-09-17 both keys are refused
   (`Permission denied (publickey)`): add one via Hostinger hPanel → VPS → SSH keys, or paste it through
   the hPanel browser terminal.
2. **DNS** — an `A` record for the public hostname pointing at `187.55.226.225`
   (recommended: `gheras.autovem.tech`; `autovem.tech` already resolves to the VPS). Wait until
   `nslookup gheras.autovem.tech` answers before deploying, or Caddy's TLS issuance fails.
3. **Firewall** — TCP 80 and **443** open in the Hostinger VPS firewall (443 currently does not connect
   from outside; the host Caddy answers on 80 with a 308 to HTTPS).

## Deploy

```bash
# from the workspace root, Git Bash
cd "Clients/03_GHERAS_Center/edutrack_pro"
export EDUTRACK_ADMIN_PASSWORD='<choose a strong password>'   # optional; otherwise one is generated and printed once
bash deploy/push.sh root@187.55.226.225 gheras.autovem.tech -i ~/.ssh/vps_secure_key
```

`deploy.sh` prints a summary ending with the dashboard URL and, on the first run, the generated admin
password (shown once — store it in the client's credential vault, never in the repo).

## What deploy.sh verifies before declaring success

- `db` container healthy; `public` schema has ≥ 43 tables and exactly one active `admin` row
  (initdb applied `001 → 002 → 003`; on a reused volume the script applies them itself — `003` is idempotent).
- `gheras_app` role password rotated to the generated secret; the API connects **only** as `gheras_app`
  (SELECT/INSERT/UPDATE grants from `003`; no superuser in the app path).
- `api` container healthy (`GET /api/v1/health` → `{"status":"ok","version":"2.0.0"}`).
- Admin argon2id hash replaced (placeholder `$argon2id$REPLACE_ON_FIRST_RUN` from `003` is unusable).
- Caddy config validated (`caddy validate`) and reloaded; then over HTTPS: health 200, `/web/dashboard/` 200,
  `/server/pyproject.toml` 404, `POST /api/v1/auth/login` with the admin credentials → 200.

## Day-2 operations

```bash
cd /opt/edutrack
C="docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env"
$C ps                                  # status
$C logs -f api                         # API logs
journalctl -u caddy -f                 # edge logs; access log: /var/log/caddy/gheras.access.log
$C exec -T db pg_dump -U postgres -Fc gheras_edutrack > /root/edutrack_$(date +%F).dump   # backup
EDUTRACK_ADMIN_PASSWORD='new' $C exec -T -e EDUTRACK_ADMIN_PASSWORD api python -m edutrack_api.importer --set-admin-password
bash deploy/deploy.sh gheras.autovem.tech   # redeploy after a new push (keeps data)
```

## Static Web Asset Caching & Hotfix Standards

1. **SPA Anti-Cache Invariant**:
   - The Caddy site block for `/web/*` must strictly include:
     ```caddy
     handle /web/* {
         header Cache-Control "no-cache, must-revalidate"
         file_server
     }
     ```
   - When modifying frontend dashboard files (`app.js`, `views/*.js`), bump the version query parameter in `index.html` and `app.js` (e.g. `?v=2.2`).
2. **Reliable Base64 Hotfixes on Windows**:
   - To deploy single updated JS/HTML files without hanging Windows OpenSSH pipes:
     ```bash
     python -c "import base64, subprocess; b64=base64.b64encode(open('local.js','rb').read()).decode(); subprocess.run(['ssh','-i','~/.ssh/edutrack_deploy_key','root@187.55.226.225',f'echo \"{b64}\" | base64 -d > /opt/edutrack/web/dashboard/js/local.js'])"
     ```

## Out of scope (Phase 3)

Automated off-site backups, rate limiting / refresh tokens, `revoked_tokens` purge job, mobile-role API
scoping, and server-side rendering of the 11 print templates to PDF.

