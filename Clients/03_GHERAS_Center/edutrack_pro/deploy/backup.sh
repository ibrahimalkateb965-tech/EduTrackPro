#!/usr/bin/env bash
# EduTrack Pro — nightly PostgreSQL backup (pg_dump) with verification and rotation.
#
# Usage: bash deploy/backup.sh
#
# Installed as a systemd timer by deploy.sh; can also be run manually.
set -euo pipefail

ROOT="${EDUTRACK_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
DEPLOY="$ROOT/deploy"
ENV_FILE="$DEPLOY/.env"
COMPOSE=(docker compose -f "$DEPLOY/docker-compose.prod.yml" --env-file "$ENV_FILE")
BACKUP_DIR="${BACKUP_DIR:-/opt/edutrack/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
LOCK_FILE="${LOCK_FILE:-/run/lock/edutrack-backup.lock}"
MIN_FREE_KB=204800   # 200 MiB

log() { printf '\n==> %s\n' "$*"; }
die() { echo "error: $*" >&2; exit 1; }

# ---------------------------------------------------------------- preflight
[[ -f "$ENV_FILE" ]] || die "missing $ENV_FILE"
command -v docker >/dev/null || die "docker is not installed"
[[ "$RETENTION_DAYS" =~ ^[0-9]+$ ]] || die "RETENTION_DAYS must be an integer"

install -d -m 700 "$BACKUP_DIR"

# ---------------------------------------------------------------- single instance
exec 9>"$LOCK_FILE"
flock -n 9 || { echo "another backup is running" >&2; exit 2; }

# ---------------------------------------------------------------- free space
avail="$(df --output=avail -k "$BACKUP_DIR" | tail -1 | tr -d ' ')"
(( avail >= MIN_FREE_KB )) || die "less than 200 MiB free in $BACKUP_DIR"

# ---------------------------------------------------------------- dump
STAMP="$(date -u +%Y%m%d_%H%M%S)"
OUT="$BACKUP_DIR/edutrack_${STAMP}.dump"
TMP="$OUT.part"
trap 'rm -f "$TMP"' EXIT
log "Dumping gheras_edutrack -> $OUT"
"${COMPOSE[@]}" exec -T db pg_dump -Fc -U postgres -d gheras_edutrack > "$TMP"

# ---------------------------------------------------------------- verify
size="$(stat -c %s "$TMP")"
(( size >= 1024 )) || die "dump is only $size bytes"
entries="$("${COMPOSE[@]}" exec -T db pg_restore -l < "$TMP" | grep -c '^[0-9]' || true)"
(( entries >= 40 )) || die "dump TOC has only $entries entries (expected >= 40)"

# ---------------------------------------------------------------- atomic publish
chmod 600 "$TMP"
mv "$TMP" "$OUT"
trap - EXIT

# ---------------------------------------------------------------- student photos (private volume, not in the dump)
PHOTOS_OUT="$BACKUP_DIR/student_photos_${STAMP}.tar.gz"
PHOTOS_TMP="$PHOTOS_OUT.part"
trap 'rm -f "$PHOTOS_TMP"' EXIT
log "Archiving student photos -> $PHOTOS_OUT"
"${COMPOSE[@]}" exec -T api tar -C /app -czf - student_photos > "$PHOTOS_TMP"
tar -tzf "$PHOTOS_TMP" >/dev/null || die "student photo archive is not readable"
chmod 600 "$PHOTOS_TMP"
mv "$PHOTOS_TMP" "$PHOTOS_OUT"
trap - EXIT

# ---------------------------------------------------------------- rotation
removed="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'edutrack_*.dump' -mtime +"$RETENTION_DAYS" -print -delete | wc -l | tr -d ' ')"
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'edutrack_*.dump.part' -mtime +1 -delete
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'student_photos_*.tar.gz' -mtime +"$RETENTION_DAYS" -delete
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'student_photos_*.tar.gz.part' -mtime +1 -delete

echo "backup ok: $OUT ($(du -h "$OUT" | cut -f1), $entries toc entries) + $PHOTOS_OUT ($(du -h "$PHOTOS_OUT" | cut -f1)), removed $removed old dump(s)"
