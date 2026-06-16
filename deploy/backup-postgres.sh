#!/usr/bin/env bash
# Nightly Postgres backup → local rotation → Google Drive upload (via rclone).
# Designed to run from the VPS user's cron. See docs/DEPLOY.md → "Backups" for setup.
set -euo pipefail

# ── Config (override via env in the cron line if needed) ──────────────────────
APP_DIR="${APP_DIR:-$HOME/salaryReview}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/salaryReview-backups}"
KEEP_LOCAL_DAYS="${KEEP_LOCAL_DAYS:-7}"
RCLONE_REMOTE="${RCLONE_REMOTE:-gdrive:salaryReview-backups}"
KEEP_REMOTE_DAYS="${KEEP_REMOTE_DAYS:-30}"
POSTGRES_SERVICE="${POSTGRES_SERVICE:-postgres}"
POSTGRES_DB="${POSTGRES_DB:-salonreview}"
POSTGRES_USER="${POSTGRES_USER:-salon}"

log() { printf '%s backup-postgres: %s\n' "$(date -u +%FT%TZ)" "$*"; }

if [[ ! -f "$APP_DIR/docker-compose.yml" ]]; then
  log "ERROR: $APP_DIR/docker-compose.yml not found (set APP_DIR)" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"
umask 077  # backups are sensitive — owner read/write only
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="$BACKUP_DIR/salonreview-$stamp.dump"

cd "$APP_DIR"

# -Fc = pg_dump custom format. Already compressed and restorable with `pg_restore`.
# -T disables TTY so this works under cron.
log "dumping $POSTGRES_DB → $out"
sudo docker compose exec -T "$POSTGRES_SERVICE" \
  pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB" > "$out"

# Custom-format dumps begin with the PGDMP magic. Guards against a half-written file
# if pg_dump errored mid-stream after the redirect created the file.
if ! head -c 5 "$out" 2>/dev/null | grep -q PGDMP; then
  log "ERROR: dump is not a valid PGDMP file, removing" >&2
  rm -f "$out"
  exit 1
fi

size="$(du -h "$out" | cut -f1)"
log "dump ok ($size)"

# Local rotation: drop dumps older than KEEP_LOCAL_DAYS.
find "$BACKUP_DIR" -maxdepth 1 -name 'salonreview-*.dump' -type f \
  -mtime +"$KEEP_LOCAL_DAYS" -delete

# Upload + remote rotation. `|| true` on delete: a brand-new remote folder has
# nothing to prune yet, and we don't want that to fail the whole job.
log "uploading to $RCLONE_REMOTE"
rclone copy "$out" "$RCLONE_REMOTE/" --quiet
rclone delete "$RCLONE_REMOTE/" --min-age "${KEEP_REMOTE_DAYS}d" --quiet || true

log "done"
