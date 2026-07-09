#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# migrate-prod.sh — Apply Flyway migrations to production database
#
# Usage:
#   SPRING_DATASOURCE_URL=jdbc:postgresql://prod-host:5432/walmal \
#   SPRING_DATASOURCE_USERNAME=walmal \
#   SPRING_DATASOURCE_PASSWORD=secret \
#   ./scripts/migrate-prod.sh
#
# Safety steps:
#   1. Run flyway:info (dry-run view)
#   2. Run backup-db.sh (pre-migration backup)
#   3. Double-confirmation prompt
#   4. Apply migrations
#   5. Post-migration verification
#
# See docs/MIGRATION_RUNBOOK.md for the full procedure.
# ─────────────────────────────────────────────────────────────────
set -euo pipefail

DATASOURCE_URL="${SPRING_DATASOURCE_URL:?SPRING_DATASOURCE_URL must be set}"
DATASOURCE_USER="${SPRING_DATASOURCE_USERNAME:?SPRING_DATASOURCE_USERNAME must be set}"
DATASOURCE_PASS="${SPRING_DATASOURCE_PASSWORD:?SPRING_DATASOURCE_PASSWORD must be set}"
SKIP_CONFIRM="${SKIP_CONFIRM:-false}"
SKIP_BACKUP="${SKIP_BACKUP:-false}"

FLYWAY_OPTS=(
  -pl walmal-app
  -Dflyway.url="${DATASOURCE_URL}"
  -Dflyway.user="${DATASOURCE_USER}"
  -Dflyway.password="${DATASOURCE_PASS}"
  --batch-mode --no-transfer-progress
)

echo "┌─────────────────────────────────────────────────────────┐"
echo "│         ⚠  PRODUCTION DATABASE MIGRATION  ⚠            │"
echo "├─────────────────────────────────────────────────────────┤"
echo "│  URL: ${DATASOURCE_URL}"
echo "└─────────────────────────────────────────────────────────┘"
echo ""

# ── Step 1: Preview pending migrations ────────────────────────────
echo "[$(date -Iseconds)] Step 1/4: Previewing pending migrations..."
./mvnw flyway:info "${FLYWAY_OPTS[@]}"
echo ""

# ── Step 2: Take a backup before migrating ────────────────────────
if [[ "${SKIP_BACKUP}" != "true" ]]; then
  echo "[$(date -Iseconds)] Step 2/4: Taking pre-migration backup..."
  export DB_HOST
  DB_HOST=$(echo "${DATASOURCE_URL}" | sed 's|.*://\([^:]*\).*|\1|')
  export DB_USER="${DATASOURCE_USER}"
  export PGPASSWORD="${DATASOURCE_PASS}"
  ./scripts/backup-db.sh
  echo "[$(date -Iseconds)] Backup complete — proceeding to migration"
else
  echo "[$(date -Iseconds)] Step 2/4: BACKUP SKIPPED (SKIP_BACKUP=true)"
fi
echo ""

# ── Step 3: Double confirmation ───────────────────────────────────
if [[ "${SKIP_CONFIRM}" != "true" ]]; then
  echo "⚠  This will modify the PRODUCTION database."
  echo "   Ensure the application is in maintenance mode or at low traffic."
  echo ""
  read -r -p "Type 'migrate production' to proceed: " CONFIRM
  if [[ "${CONFIRM}" != "migrate production" ]]; then
    echo "Aborted."
    exit 0
  fi
fi

# ── Step 4: Apply migrations ──────────────────────────────────────
echo "[$(date -Iseconds)] Step 3/4: Applying migrations to PRODUCTION..."
./mvnw flyway:migrate "${FLYWAY_OPTS[@]}"

# ── Step 5: Verify ────────────────────────────────────────────────
echo ""
echo "[$(date -Iseconds)] Step 4/4: Post-migration verification..."
./mvnw flyway:info "${FLYWAY_OPTS[@]}"

echo ""
echo "[$(date -Iseconds)] ✓ Production migration complete"
echo ""
echo "Next steps:"
echo "  1. Run smoke tests: ./scripts/smoke-test.sh https://api.walmal.com"
echo "  2. Monitor /actuator/health for any degradation"
echo "  3. Check application logs for migration-related errors"
