#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# migrate-dev.sh — Apply Flyway migrations to local dev database
#
# Usage:
#   ./scripts/migrate-dev.sh
#
# Assumes docker-compose services are running:
#   docker compose up -d postgres
# ─────────────────────────────────────────────────────────────────
set -euo pipefail

DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://localhost:5432/walmal}"
DATASOURCE_USER="${SPRING_DATASOURCE_USERNAME:-walmal}"
DATASOURCE_PASS="${SPRING_DATASOURCE_PASSWORD:-walmal}"

echo "[$(date -Iseconds)] Running Flyway migrate on ${DATASOURCE_URL}"

./mvnw flyway:migrate -pl walmal-app \
  -Dflyway.url="${DATASOURCE_URL}" \
  -Dflyway.user="${DATASOURCE_USER}" \
  -Dflyway.password="${DATASOURCE_PASS}" \
  --batch-mode --no-transfer-progress

echo "[$(date -Iseconds)] Migration complete"

# Show applied migrations
./mvnw flyway:info -pl walmal-app \
  -Dflyway.url="${DATASOURCE_URL}" \
  -Dflyway.user="${DATASOURCE_USER}" \
  -Dflyway.password="${DATASOURCE_PASS}" \
  --batch-mode --no-transfer-progress
