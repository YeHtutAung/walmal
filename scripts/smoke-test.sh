#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# smoke-test.sh — Post-deployment smoke tests for Walmal API
#
# Usage:
#   ./scripts/smoke-test.sh https://api.walmal.com
#   ./scripts/smoke-test.sh http://localhost:8080
# ─────────────────────────────────────────────────────────────────
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
PASS=0
FAIL=0

check() {
  local desc="$1"
  local url="$2"
  local expected_status="${3:-200}"
  local body_pattern="${4:-}"

  RESPONSE=$(curl -sf -o /tmp/smoke_body -w "%{http_code}" "${url}" 2>/dev/null || echo "000")

  if [[ "${RESPONSE}" != "${expected_status}" ]]; then
    echo "  FAIL  [${RESPONSE}] ${desc}"
    FAIL=$((FAIL + 1))
    return
  fi

  if [[ -n "${body_pattern}" ]]; then
    if ! grep -q "${body_pattern}" /tmp/smoke_body 2>/dev/null; then
      echo "  FAIL  [body] ${desc} — pattern '${body_pattern}' not found"
      FAIL=$((FAIL + 1))
      return
    fi
  fi

  echo "  PASS  [${RESPONSE}] ${desc}"
  PASS=$((PASS + 1))
}

echo "================================================"
echo " Smoke Tests — ${BASE_URL}"
echo " $(date -Iseconds)"
echo "================================================"
echo ""

echo "── Health ──────────────────────────────────────"
check "Actuator health" "${BASE_URL}/actuator/health" 200 '"status":"UP"'

echo ""
echo "── Products API ────────────────────────────────"
check "List products" "${BASE_URL}/api/v1/products" 200
check "Product categories" "${BASE_URL}/api/v1/products/categories" 200

echo ""
echo "── Auth API ────────────────────────────────────"
check "Register (schema check)" "${BASE_URL}/api/v1/auth/register" 405   # GET → 405

echo ""
echo "── Inventory API ───────────────────────────────"
check "Default location" "${BASE_URL}/api/v1/inventory/locations/default" 200

echo ""
echo "── Results ─────────────────────────────────────"
echo "  Passed: ${PASS}"
echo "  Failed: ${FAIL}"
echo ""

if [[ "${FAIL}" -gt 0 ]]; then
  echo "SMOKE TESTS FAILED — ${FAIL} check(s) did not pass."
  exit 1
fi

echo "All smoke tests passed."
