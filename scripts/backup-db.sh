#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# backup-db.sh — Daily PostgreSQL backup for Walmal
#
# Usage:
#   ./scripts/backup-db.sh
#
# Environment variables (required unless defaults suit):
#   DB_HOST       PostgreSQL host           (default: localhost)
#   DB_PORT       PostgreSQL port           (default: 5432)
#   DB_NAME       Database name             (default: walmal)
#   DB_USER       Database user             (default: walmal)
#   PGPASSWORD    Database password         (required)
#   BACKUP_DIR    Local backup directory    (default: /var/backups/walmal)
#   RETAIN_DAYS   Days to keep backups      (default: 30)
#   S3_BUCKET     S3 bucket for offsite copy (optional, e.g. s3://my-backups/walmal)
#
# Recommended cron (daily at 02:00):
#   0 2 * * * /opt/walmal/scripts/backup-db.sh >> /var/log/walmal-backup.log 2>&1
# ─────────────────────────────────────────────────────────────────
set -euo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-walmal}"
DB_USER="${DB_USER:-walmal}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/walmal}"
RETAIN_DAYS="${RETAIN_DAYS:-30}"
S3_BUCKET="${S3_BUCKET:-}"

TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_FILE="${BACKUP_DIR}/walmal_${TIMESTAMP}.sql.gz"

echo "[$(date -Iseconds)] Starting backup: ${DB_NAME} → ${BACKUP_FILE}"

# Ensure backup directory exists
mkdir -p "${BACKUP_DIR}"

# Dump and compress in one pipe (no temp uncompressed file)
pg_dump \
  --host="${DB_HOST}" \
  --port="${DB_PORT}" \
  --username="${DB_USER}" \
  --dbname="${DB_NAME}" \
  --format=plain \
  --no-password \
  | gzip -9 > "${BACKUP_FILE}"

BACKUP_SIZE=$(du -sh "${BACKUP_FILE}" | cut -f1)
echo "[$(date -Iseconds)] Backup complete: ${BACKUP_SIZE}"

# ── Offsite copy to S3 (optional) ────────────────────────────────
if [[ -n "${S3_BUCKET}" ]]; then
  echo "[$(date -Iseconds)] Uploading to ${S3_BUCKET}..."
  aws s3 cp "${BACKUP_FILE}" "${S3_BUCKET}/$(basename "${BACKUP_FILE}")" \
    --storage-class STANDARD_IA
  echo "[$(date -Iseconds)] S3 upload complete"
fi

# ── Prune old backups ─────────────────────────────────────────────
echo "[$(date -Iseconds)] Pruning backups older than ${RETAIN_DAYS} days..."
find "${BACKUP_DIR}" -name "walmal_*.sql.gz" -mtime "+${RETAIN_DAYS}" -delete
REMAINING=$(find "${BACKUP_DIR}" -name "walmal_*.sql.gz" | wc -l)
echo "[$(date -Iseconds)] Backup directory now has ${REMAINING} file(s)"

echo "[$(date -Iseconds)] Backup job finished successfully"
