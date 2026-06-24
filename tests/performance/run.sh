#!/usr/bin/env bash
# =============================================================================
# run.sh — Execute all k6 load tests and aggregate results
#
# Usage:
#   ./tests/performance/run.sh                # run all tests
#   ./tests/performance/run.sh auth product   # run specific tests
#   BASE_URL=http://staging:8080/api/v1 ./tests/performance/run.sh
#
# Output:
#   tests/performance/results/<name>.json   — machine-readable k6 summary
#   tests/performance/results/<name>.html   — HTML report (k6 v0.48+)
#
# Requirements:
#   k6 >= 0.48  (https://k6.io/docs/get-started/installation/)
#   Backend running at BASE_URL (default: http://localhost:8080/api/v1)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"
BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"

mkdir -p "$RESULTS_DIR"

# Tests to run (in order). Can be overridden by passing names as arguments.
DEFAULT_TESTS=(auth product inventory pos checkout warehouse)
if [[ $# -gt 0 ]]; then
  TESTS=("$@")
else
  TESTS=("${DEFAULT_TESTS[@]}")
fi

PASSED=0
FAILED=0
SKIPPED=0
SUMMARY_LINES=()

run_test() {
  local name="$1"
  local script="$SCRIPT_DIR/${name}.load.js"

  if [[ ! -f "$script" ]]; then
    echo "  [SKIP] $script not found"
    SKIPPED=$((SKIPPED + 1))
    return
  fi

  echo ""
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "  Running: $name"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

  local json_out="$RESULTS_DIR/${name}.json"
  local html_out="$RESULTS_DIR/${name}.html"
  local exit_code=0

  k6 run \
    --env BASE_URL="$BASE_URL" \
    --out "json=${json_out}" \
    --report-file "${html_out}" \
    "$script" || exit_code=$?

  if [[ $exit_code -eq 0 ]]; then
    echo "  [PASS] $name"
    PASSED=$((PASSED + 1))
    SUMMARY_LINES+=("  PASS  $name  →  $json_out")
  else
    echo "  [FAIL] $name (exit $exit_code)"
    FAILED=$((FAILED + 1))
    SUMMARY_LINES+=("  FAIL  $name  →  $json_out")
  fi
}

echo ""
echo "========================================================"
echo "  Walmal Backend — k6 Performance Test Suite"
echo "  BASE_URL: $BASE_URL"
echo "  Results:  $RESULTS_DIR"
echo "========================================================"

START_TIME=$(date +%s)

for test_name in "${TESTS[@]}"; do
  run_test "$test_name"
done

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

echo ""
echo "========================================================"
echo "  Results Summary"
echo "========================================================"
for line in "${SUMMARY_LINES[@]}"; do
  echo "$line"
done
echo ""
echo "  Total: $((PASSED + FAILED + SKIPPED))  |  Passed: $PASSED  |  Failed: $FAILED  |  Skipped: $SKIPPED"
echo "  Duration: ${ELAPSED}s"
echo "========================================================"

if [[ $FAILED -gt 0 ]]; then
  exit 1
fi
