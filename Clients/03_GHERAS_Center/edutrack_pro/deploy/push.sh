#!/usr/bin/env bash
# Developer side (Git Bash / Linux): sync the edutrack_pro tree to the VPS, then deploy.
#   bash deploy/push.sh root@187.55.226.225 gheras.autovem.tech [-i ~/.ssh/key]
# Export EDUTRACK_ADMIN_PASSWORD beforehand to choose the admin password instead of
# letting deploy.sh generate one on the first run.
set -euo pipefail
TARGET="${1:?usage: push.sh user@host public-hostname [ssh-opts...]}"
HOST="${2:?usage: push.sh user@host public-hostname [ssh-opts...]}"
shift 2
SSH=(ssh "$@")
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE_DIR=/opt/edutrack

echo "==> Syncing $ROOT -> $TARGET:$REMOTE_DIR"
tar -C "$ROOT" -czf - \
  --exclude='server/tests' --exclude='server/.venv' --exclude='__pycache__' \
  --exclude='deploy/.env' --exclude='deploy/Caddyfile.rendered' \
  assets db server web deploy \
  | "${SSH[@]}" "$TARGET" "mkdir -p $REMOTE_DIR && tar -xzf - -C $REMOTE_DIR"

echo "==> Deploying on $TARGET"
REMOTE_ENV=""
if [[ -n "${EDUTRACK_ADMIN_PASSWORD:-}" ]]; then
  REMOTE_ENV="EDUTRACK_ADMIN_PASSWORD=$(printf '%q' "$EDUTRACK_ADMIN_PASSWORD") "
fi
"${SSH[@]}" -t "$TARGET" "cd $REMOTE_DIR && ${REMOTE_ENV}bash deploy/deploy.sh $HOST"
