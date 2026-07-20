#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# deploy/backup.sh — Production backup: Postgres dump + MinIO data tar
#
# Targets the prod compose stack specifically (docker-compose.prod.yml,
# service names `postgres`/`minio`). This is deliberately separate from
# scripts/backup-db.sh, which backs up a bare-host Postgres instance
# outside compose — this script always shells into the running prod
# containers via `docker compose exec`.
#
# Usage (from the walmal repo root, on the VPS):
#   ./deploy/backup.sh
#
# Recommended cron (03:00 daily, per docs/DEPLOYMENT.md):
#   0 3 * * * cd /opt/walmal && ./deploy/backup.sh >> /var/log/walmal-backup.log 2>&1
#
# Environment variables:
#   COMPOSE_FILE   Path to the prod compose file (default: docker-compose.prod.yml)
#   BACKUP_ROOT    Root backup directory              (default: /opt/walmal/backups)
#   RETAIN_DAYS    Days to keep dated backup dirs      (default: 7)
#   SPRING_DATASOURCE_USERNAME   Postgres user inside the container (required —
#                                already set in the stack's .env)
#   S3_BACKUP_BUCKET   Opt-in off-site copy: S3 bucket NAME (no s3:// prefix).
#                      Unset = local backup only. Credentials come from the
#                      standard AWS chain (IAM role / `aws configure` / env).
#   S3_BACKUP_PREFIX   Key prefix within the bucket    (default: walmal-backups)
#
# ── Restore procedure (MUST-RUN-ONCE drill — see docs/DEPLOYMENT.md) ──
#   1. Postgres:
#        docker compose -f docker-compose.prod.yml exec -T postgres \
#          pg_restore -U "$SPRING_DATASOURCE_USERNAME" -d walmal --clean --if-exists \
#          < backups/<date>/walmal.dump
#      (Restoring into a *scratch* database first is strongly recommended
#      before ever pointing this at the live `walmal` database — create a
#      throwaway DB, restore into it, verify row counts, THEN restore prod.)
#   2. MinIO data volume (stop minio first to avoid restoring under load).
#      NOTE: don't hardcode the volume name — it is <project>_walmal-minio-data
#      where <project> is the compose project name (this script computes it;
#      confirm with `docker volume ls | grep walmal-minio-data`):
#        docker compose -f docker-compose.prod.yml stop minio
#        docker run --rm \
#          -v <project>_walmal-minio-data:/data \
#          -v "$(pwd)/backups/<date>":/backup \
#          alpine sh -c "rm -rf /data/* && tar -xzf /backup/minio-data.tar.gz -C /data"
#        docker compose -f docker-compose.prod.yml start minio
# ─────────────────────────────────────────────────────────────────
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
BACKUP_ROOT="${BACKUP_ROOT:-/opt/walmal/backups}"
RETAIN_DAYS="${RETAIN_DAYS:-7}"
DB_USER="${SPRING_DATASOURCE_USERNAME:?SPRING_DATASOURCE_USERNAME must be set}"

DATE_DIR="$(date +%Y-%m-%d)"
TARGET_DIR="${BACKUP_ROOT}/${DATE_DIR}"

echo "[$(date -Iseconds)] Starting backup -> ${TARGET_DIR}"
mkdir -p "${TARGET_DIR}"

# ── Postgres: custom-format dump (pg_restore-able, compressed) ────
echo "[$(date -Iseconds)] Dumping Postgres (walmal)..."
docker compose -f "${COMPOSE_FILE}" exec -T postgres \
  pg_dump -Fc -U "${DB_USER}" walmal > "${TARGET_DIR}/walmal.dump"

if [[ ! -s "${TARGET_DIR}/walmal.dump" ]]; then
  echo "[$(date -Iseconds)] ERROR: walmal.dump is empty — aborting before pruning anything." >&2
  exit 1
fi
echo "[$(date -Iseconds)] Postgres dump complete: $(du -h "${TARGET_DIR}/walmal.dump" | cut -f1)"

# ── MinIO: tar the data volume via a throwaway alpine container ───
# (avoids requiring tar inside the minio image itself, and avoids
# touching the running minio container's filesystem directly)
echo "[$(date -Iseconds)] Archiving MinIO data volume..."
# Docker Compose prefixes named volumes with the project name (default: the
# lowercased directory `docker compose` was run from, non-alphanumerics
# stripped) — override MINIO_VOLUME explicitly if COMPOSE_PROJECT_NAME was
# set to something else when the stack was brought up.
# printf (not a bare pipe from basename) so basename's trailing newline never
# reaches `tr -c`, which would convert it into a stray trailing '-'.
DEFAULT_PROJECT_NAME="$(printf '%s' "$(basename "$(pwd)")" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9' '-')"
MINIO_VOLUME="${MINIO_VOLUME:-${COMPOSE_PROJECT_NAME:-${DEFAULT_PROJECT_NAME}}_walmal-minio-data}"

# Guard: `docker run -v` silently AUTO-CREATES a missing named volume, so a
# wrong name would tar a fresh empty volume and report success. Abort instead.
# This is the safety net for the name derivation above — if the derivation is
# ever wrong for a setup, the run fails loudly here rather than producing
# empty "backups".
docker volume inspect "${MINIO_VOLUME}" >/dev/null 2>&1 || {
  echo "[$(date -Iseconds)] ERROR: docker volume '${MINIO_VOLUME}' not found — set MINIO_VOLUME explicitly." >&2
  exit 1
}
docker run --rm \
  -v "${MINIO_VOLUME}:/data:ro" \
  -v "${TARGET_DIR}:/backup" \
  alpine tar -czf /backup/minio-data.tar.gz -C /data .

if [[ ! -s "${TARGET_DIR}/minio-data.tar.gz" ]]; then
  echo "[$(date -Iseconds)] ERROR: minio-data.tar.gz is empty — aborting before pruning anything." >&2
  exit 1
fi
echo "[$(date -Iseconds)] MinIO archive complete: $(du -h "${TARGET_DIR}/minio-data.tar.gz" | cut -f1)"

# ── 7-day rotation: delete dated dirs older than RETAIN_DAYS ──────
# -name '????-??-??' restricts deletion to this script's own date-named
# dirs — anything else placed under BACKUP_ROOT is never touched.
echo "[$(date -Iseconds)] Pruning backups older than ${RETAIN_DAYS} days..."
find "${BACKUP_ROOT}" -maxdepth 1 -mindepth 1 -type d -name '????-??-??' -mtime "+${RETAIN_DAYS}" -print -exec rm -rf {} \;

# ── Off-site copy to S3 (opt-in) ──────────────────────────────────
# Local backups share the instance's fate — lose the box, lose them. Set
# S3_BACKUP_BUCKET to also push each day's backup off-site. Runs AFTER the
# local dump/archive are verified above, so the on-disk backup is complete
# and safe regardless of what happens here. Off-site retention is best
# managed with an S3 lifecycle rule on the bucket, independent of the local
# RETAIN_DAYS rotation above (this script never deletes anything remote).
if [[ -n "${S3_BACKUP_BUCKET:-}" ]]; then
  S3_BACKUP_PREFIX="${S3_BACKUP_PREFIX:-walmal-backups}"
  S3_DEST="s3://${S3_BACKUP_BUCKET}/${S3_BACKUP_PREFIX}/${DATE_DIR}"
  # Opted in but no CLI: fail loudly rather than silently skip — a backup you
  # think is going off-site but isn't is worse than a known local-only one.
  command -v aws >/dev/null 2>&1 || {
    echo "[$(date -Iseconds)] ERROR: S3_BACKUP_BUCKET set but 'aws' CLI not found — install it or unset S3_BACKUP_BUCKET." >&2
    exit 1
  }
  echo "[$(date -Iseconds)] Syncing ${TARGET_DIR} -> ${S3_DEST}..."
  aws s3 sync "${TARGET_DIR}" "${S3_DEST}" --only-show-errors
  echo "[$(date -Iseconds)] Off-site sync complete: ${S3_DEST}"
else
  echo "[$(date -Iseconds)] S3_BACKUP_BUCKET unset — skipping off-site copy (local backup only)."
fi

echo "[$(date -Iseconds)] Backup job finished successfully: ${TARGET_DIR}"
