#!/usr/bin/env bash
# EduTrack Pro — clean production deploy on the Hostinger VPS (Ubuntu 24.04 + Docker).
#
# Usage (as root, from the synced tree, e.g. /opt/edutrack):
#   bash deploy/deploy.sh gheras.autovem.tech
#
# Idempotent: re-running rebuilds the API image, keeps the database volume,
# re-applies the app-role password and refreshes the Caddy site. The admin
# password is (re)set only when EDUTRACK_ADMIN_PASSWORD is exported or on the
# very first run (a random one is generated and printed once).
set -euo pipefail

HOST="${1:-${GHERAS_HOST:-}}"
[[ -n "$HOST" ]] || { echo "usage: deploy.sh <public-hostname>" >&2; exit 2; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY="$ROOT/deploy"
ENV_FILE="$DEPLOY/.env"
COMPOSE=(docker compose -f "$DEPLOY/docker-compose.prod.yml" --env-file "$ENV_FILE")
PSQL=("${COMPOSE[@]}" exec -T db psql -v ON_ERROR_STOP=1 -U postgres -d gheras_edutrack -qAt)

log() { printf '\n==> %s\n' "$*"; }
die() { echo "error: $*" >&2; exit 1; }

# ---------------------------------------------------------------- preflight
[[ $EUID -eq 0 ]] || die "run as root"
command -v docker >/dev/null || die "docker is not installed"
docker compose version >/dev/null 2>&1 || die "docker compose v2 is required"
command -v openssl >/dev/null || die "openssl is required"
for f in db/postgres/001_schema.sql db/postgres/002_reference.sql db/postgres/003_phase2.sql db/postgres/004_phase3.sql db/postgres/005_saturday.sql db/postgres/006_settings.sql db/postgres/007_revoked_tokens_purge.sql db/postgres/008_national_id_auth_and_student_role.sql db/postgres/009_whatsapp_auth_otp.sql \
         server/Dockerfile server/uv.lock web/dashboard/index.html assets/gheras_logo.png \
         deploy/backup.sh deploy/edutrack-backup.service deploy/edutrack-backup.timer; do
  [[ -f "$ROOT/$f" ]] || die "missing $f — sync the full edutrack_pro tree first"
done

# ---------------------------------------------------------------- secrets
FIRST_RUN=0
if [[ ! -f "$ENV_FILE" ]]; then
  FIRST_RUN=1
  log "Generating $ENV_FILE"
  umask 077
  {
    echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)"
    echo "GHERAS_APP_PASSWORD=$(openssl rand -hex 24)"
    echo "JWT_SECRET=$(openssl rand -hex 32)"
    echo "JWT_TTL_MINUTES=720"
    echo "MOBILE_JWT_TTL_MINUTES=43200"
    echo "CORS_ORIGINS="
    echo "MAIN_BRANCH_ID=00000000-0000-0000-0000-000000000001"
  } > "$ENV_FILE"
  umask 022
fi
chmod 600 "$ENV_FILE"
# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a
[[ -n "${POSTGRES_PASSWORD:-}" && -n "${GHERAS_APP_PASSWORD:-}" && -n "${JWT_SECRET:-}" ]] \
  || die "$ENV_FILE is incomplete"

# ---------------------------------------------------------------- database
log "Starting PostgreSQL"
"${COMPOSE[@]}" up -d db
status=""
for _ in $(seq 1 40); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$("${COMPOSE[@]}" ps -q db)" 2>/dev/null || true)"
  [[ "$status" == healthy ]] && break
  sleep 3
done
[[ "$status" == healthy ]] || die "database did not become healthy"

# A reused volume skips the initdb scripts; apply 001->009 ourselves (003 through 009 are idempotent).
if [[ "$("${PSQL[@]}" -c "SELECT to_regclass('public.users') IS NOT NULL")" != "t" ]]; then
  log "Empty database — applying migrations 001 -> 009"
  for m in 001_schema.sql 002_reference.sql 003_phase2.sql 004_phase3.sql 005_saturday.sql 006_settings.sql 007_revoked_tokens_purge.sql 008_national_id_auth_and_student_role.sql 009_whatsapp_auth_otp.sql; do
    "${PSQL[@]}" -f "/docker-entrypoint-initdb.d/$m" >/dev/null
  done
else
  log "Applying incremental idempotent migrations (003 -> 009)"
  for m in 003_phase2.sql 004_phase3.sql 005_saturday.sql 006_settings.sql 007_revoked_tokens_purge.sql 008_national_id_auth_and_student_role.sql 009_whatsapp_auth_otp.sql; do
    "${PSQL[@]}" -f "/docker-entrypoint-initdb.d/$m" >/dev/null || true
  done
fi
TABLES="$("${PSQL[@]}" -c "SELECT count(*) FROM pg_tables WHERE schemaname='public'")"
# The dashboard lets a manager delete the seeded "admin" account, so the invariant is
# "at least one active manager", not "admin exists".
MANAGER_ROWS="$("${PSQL[@]}" -c "SELECT count(*) FROM users WHERE role='manager' AND is_active AND deleted_at IS NULL")"
[[ "$TABLES" -ge 43 ]] || die "expected >= 43 tables, found $TABLES"
[[ "$MANAGER_ROWS" -ge 1 ]] || die "no active manager account (found $MANAGER_ROWS)"
echo "schema ok: $TABLES tables, $MANAGER_ROWS active manager account(s)"

log "Setting the gheras_app role password"
"${PSQL[@]}" -c "ALTER ROLE gheras_app WITH LOGIN PASSWORD '$GHERAS_APP_PASSWORD'" >/dev/null

# ---------------------------------------------------------------- api
log "Building and starting the API"
"${COMPOSE[@]}" up -d --build api
status=""
for _ in $(seq 1 30); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$("${COMPOSE[@]}" ps -q api)" 2>/dev/null || true)"
  [[ "$status" == healthy ]] && break
  sleep 3
done
[[ "$status" == healthy ]] || { "${COMPOSE[@]}" logs --tail 50 api; die "API did not become healthy"; }
curl -fsS http://127.0.0.1:8000/api/v1/health; echo

# ---------------------------------------------------------------- backups (nightly pg_dump)
log "Installing the nightly backup timer"
install -d -m 700 /opt/edutrack/backups
chmod +x "$DEPLOY/backup.sh"
sed -e "s|__ROOT__|$ROOT|g" "$DEPLOY/edutrack-backup.service" > /etc/systemd/system/edutrack-backup.service
install -m 644 "$DEPLOY/edutrack-backup.timer" /etc/systemd/system/edutrack-backup.timer
systemd-analyze verify /etc/systemd/system/edutrack-backup.service /etc/systemd/system/edutrack-backup.timer
systemctl daemon-reload
systemctl enable --now edutrack-backup.timer >/dev/null
systemctl start edutrack-backup.service   # oneshot: blocks until backup.sh exits; a failed dump fails the deploy
LATEST_DUMP="$(ls -1t /opt/edutrack/backups/edutrack_*.dump 2>/dev/null | head -1)"
[[ -n "$LATEST_DUMP" ]] || die "backup smoke test produced no dump (journalctl -u edutrack-backup.service)"
echo "backup ok: $LATEST_DUMP ($(du -h "$LATEST_DUMP" | cut -f1)); next run: $(systemctl show edutrack-backup.timer -p NextElapseUSecRealtime --value)"

# ---------------------------------------------------------------- admin seed
GENERATED_ADMIN=""
if [[ -z "${EDUTRACK_ADMIN_PASSWORD:-}" && $FIRST_RUN -eq 1 ]]; then
  GENERATED_ADMIN="$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-16)"
  EDUTRACK_ADMIN_PASSWORD="$GENERATED_ADMIN"
fi
if [[ -n "${EDUTRACK_ADMIN_PASSWORD:-}" ]]; then
  log "Setting the admin password"
  "${COMPOSE[@]}" exec -T -e EDUTRACK_ADMIN_PASSWORD="$EDUTRACK_ADMIN_PASSWORD" api \
    python -m edutrack_api.importer --set-admin-password
fi

# ---------------------------------------------------------------- edge (Caddy)
render_caddy() {  # $1 = web root, $2 = api upstream, $3 = output file
  sed -e "s|__HOST__|$HOST|g" -e "s|__ROOT__|$1|g" -e "s|__API__|$2|g" "$DEPLOY/Caddyfile.gheras" > "$3"
}
EDGE=""
if systemctl is-active --quiet caddy; then
  log "Host Caddy detected — installing /etc/caddy/sites/gheras.caddy"
  mkdir -p /etc/caddy/sites /var/log/caddy
  id caddy >/dev/null 2>&1 && chown -R caddy:caddy /var/log/caddy
  chmod -R a+rX "$ROOT/web" "$ROOT/assets"   # the caddy service user must read the static tree
  render_caddy "$ROOT" "127.0.0.1:8000" /etc/caddy/sites/gheras.caddy
  if ! grep -qE '^\s*import\s+/etc/caddy/sites/\*' /etc/caddy/Caddyfile; then
    printf '\nimport /etc/caddy/sites/*.caddy\n' >> /etc/caddy/Caddyfile
  fi
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
  systemctl reload caddy
  EDGE="host caddy (/etc/caddy/sites/gheras.caddy)"
elif docker ps --format '{{.Ports}}' | grep -qE '(^|[ ,])(0\.0\.0\.0|\[::\]):80->'; then
  die "port 80 is published by another container; route /api/* to 127.0.0.1:8000 and /web/*, /assets/* to $ROOT there"
else
  log "No host Caddy — starting the bundled edge (profile: edge)"
  render_caddy "/opt/edutrack" "api:8000" "$DEPLOY/Caddyfile.rendered"
  "${COMPOSE[@]}" --profile edge up -d caddy
  EDGE="bundled caddy container"
fi

# ---------------------------------------------------------------- smoke
log "Smoke test via https://$HOST"
ok=0
for _ in $(seq 1 12); do
  if curl -fsS --max-time 10 "https://$HOST/api/v1/health" >/dev/null 2>&1; then ok=1; break; fi
  sleep 5
done
if [[ $ok -eq 1 ]]; then
  curl -fsS "https://$HOST/api/v1/health"; echo
  curl -fsS -o /dev/null -w 'dashboard: HTTP %{http_code}\n' "https://$HOST/web/dashboard/"
  curl -sS -o /dev/null -w 'server/ blocked: HTTP %{http_code}\n' "https://$HOST/server/pyproject.toml"
  if [[ -n "${EDUTRACK_ADMIN_PASSWORD:-}" ]]; then
    code="$(curl -sS -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
      -d "{\"username\":\"admin\",\"password\":\"$EDUTRACK_ADMIN_PASSWORD\"}" "https://$HOST/api/v1/auth/login")"
    echo "admin login: HTTP $code"
  fi
else
  echo "WARNING: https://$HOST is not answering yet (DNS propagation, port 443 firewall, or TLS issuance still running)." >&2
  echo "         Check: journalctl -u caddy -n 50   |   dig +short $HOST   |   ufw status" >&2
fi

# ---------------------------------------------------------------- summary
echo
echo "================ EduTrack Pro deployed ================"
echo "Dashboard     : https://$HOST/web/dashboard/"
echo "API health    : https://$HOST/api/v1/health"
echo "Edge          : $EDGE"
echo "Secrets       : $ENV_FILE (600, root)"
echo "Backups       : /opt/edutrack/backups (daily 03:00 UTC, 14-day rotation; systemctl list-timers edutrack-backup.timer)"
echo "Admin user    : admin"
if [[ -n "$GENERATED_ADMIN" ]]; then
  echo "Admin password: $GENERATED_ADMIN   <-- shown once; store it in the client's credential vault"
fi
echo "======================================================="
