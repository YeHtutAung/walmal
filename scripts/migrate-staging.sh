#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# migrate-staging.sh — Apply Flyway migrations to staging database
#
# Usage:
#   SPRING_DATASOURCE_URL=jdbc:postgresql://staging-host:5432/walmal \
#   SPRING_DATASOURCE_USERNAME=walmal \
#   SPRING_DATASOURCE_PASSWORD=secret \
#   ./scripts/migrate-staging.sh
#
# The script:
#   1. Runs flyway:info to preview pending migrations
#   2. Asks for confirmation (unless SKIP_CONFIRM=true)
#   3. Applies migrations
#   4. Prints post-migration info
# ─────────────────────────────────────────────────────────────────
set -euo pipefail

DATASOURCE_URL="${SPRING_DATASOURCE_URL:?SPRING_DATASOURCE_URL must be set}"
DATASOURCE_USER="${SPRING_DATASOURCE_USERNAME:?SPRING_DATASOURCE_USERNAME must be set}"
DATASOURCE_PASS="${SPRING_DATASOURCE_PASSWORD:?SPRING_DATASOURCE_PASSWORD must be set}"
SKIP_CONFIRM="${SKIP_CONFIRM:-false}"

FLYWAY_OPTS=(
  -pl walmal-app
  -Dflyway.url="${DATASOURCE_URL}"
  -Dflyway.user="${DATASOURCE_USER}"
  -Dflyway.password="${DATASOURCE_PASS}"
  --batch-mode --no-transfer-progress
)

echo "┌─────────────────────────────────────────────────────────┐"
echo "│           STAGING DATABASE MIGRATION                    │"
echo "├─────────────────────────────────────────────────────────┤"
echo "│  URL: ${DATASOURCE_URL}"
echo "└─────────────────────────────────────────────────────────┘"
echo ""

echo "[$(date -Iseconds)] Checking pending migrations..."
./mvnw flyway:info "${FLYWAY_OPTS[@]}"

if [[ "${SKIP_CONFIRM}" != "true" ]]; then
  echo ""
  read -r -p "Apply the migrations above to STAGING? Type 'yes' to proceed: " CONFIRM
  if [[ "${CONFIRM}" != "yes" ]]; then
    echo "Aborted."
    exit 0
  fi
fi

echo "[$(date -Iseconds)] Applying migrations to staging..."
./mvnw flyway:migrate "${FLYWAY_OPTS[@]}"

echo "[$(date -Iseconds)] Post-migration status:"
./mvnw flyway:info "${FLYWAY_OPTS[@]}"

echo "[$(date -Iseconds)] Staging migration complete"
