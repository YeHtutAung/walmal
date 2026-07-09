#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# restore-db.sh — Restore PostgreSQL database from a backup
#
# Usage:
#   ./scripts/restore-db.sh <backup-file.sql.gz>
#
# Environment variables:
#   DB_HOST       PostgreSQL host           (default: localhost)
#   DB_PORT       PostgreSQL port           (default: 5432)
#   DB_NAME       Database name             (default: walmal)
#   DB_USER       Database user             (default: walmal)
#   PGPASSWORD    Database password         (required)
#   SKIP_CONFIRM  Set to "true" to skip the confirmation prompt
#
# WARNING: This drops and recreates the target database.
#          All existing data will be lost.
# ─────────────────────────────────────────────────────────────────
set -euo pipefail

BACKUP_FILE="${1:-}"
if [[ -z "${BACKUP_FILE}" ]]; then
  echo "ERROR: No backup file specified."
  echo "Usage: $0 <backup-file.sql.gz>"
  exit 1
fi

if [[ ! -f "${BACKUP_FILE}" ]]; then
  echo "ERROR: Backup file not found: ${BACKUP_FILE}"
  exit 1
fi

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-walmal}"
DB_USER="${DB_USER:-walmal}"
SKIP_CONFIRM="${SKIP_CONFIRM:-false}"

echo "┌─────────────────────────────────────────────────────────┐"
echo "│               DATABASE RESTORE — WALMAL                 │"
echo "├─────────────────────────────────────────────────────────┤"
echo "│  Host:     ${DB_HOST}:${DB_PORT}"
echo "│  Database: ${DB_NAME}"
echo "│  User:     ${DB_USER}"
echo "│  File:     ${BACKUP_FILE}"
echo "└─────────────────────────────────────────────────────────┘"
echo ""
echo "WARNING: This will DROP and RECREATE the '${DB_NAME}' database."
echo "         ALL EXISTING DATA WILL BE PERMANENTLY LOST."
echo ""

if [[ "${SKIP_CONFIRM}" != "true" ]]; then
  read -r -p "Type 'yes' to proceed: " CONFIRM
  if [[ "${CONFIRM}" != "yes" ]]; then
    echo "Aborted."
    exit 0
  fi
fi

PSQL="psql --host=${DB_HOST} --port=${DB_PORT} --username=${DB_USER} --no-password"

echo "[$(date -Iseconds)] Dropping existing database '${DB_NAME}'..."
${PSQL} --dbname=postgres -c "DROP DATABASE IF EXISTS \"${DB_NAME}\";"

echo "[$(date -Iseconds)] Creating fresh database '${DB_NAME}'..."
${PSQL} --dbname=postgres -c "CREATE DATABASE \"${DB_NAME}\" OWNER \"${DB_USER}\";"

echo "[$(date -Iseconds)] Restoring from ${BACKUP_FILE}..."
gunzip -c "${BACKUP_FILE}" | ${PSQL} --dbname="${DB_NAME}"

echo "[$(date -Iseconds)] Verifying restore..."
TABLE_COUNT=$(${PSQL} --dbname="${DB_NAME}" -t -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")
echo "[$(date -Iseconds)] Tables restored: ${TABLE_COUNT}"

echo "[$(date -Iseconds)] Restore complete. Run Flyway repair if schema history is mismatched:"
echo "  ./mvnw flyway:repair -pl walmal-app"
