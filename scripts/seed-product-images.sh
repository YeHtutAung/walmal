#!/usr/bin/env bash
# Seeds product images for the 15 Walmal Sport demo products (V17 catalog).
#
# Bash port of seed-product-images.ps1 (same behavior, curl-only) so it runs
# on the production VPS or any POSIX shell. IDEMPOTENT: skips a product only
# when it already has a PRIMARY image — stray non-primary images (e.g. admin
# E2E CRUD leftovers) must not block seeding a real primary. Safe to re-run;
# re-run after a postgres/minio volume wipe (docker compose down -v).
#
# Usage:
#   ./scripts/seed-product-images.sh [API_BASE]
#   API_BASE defaults to http://localhost:8080/api/v1
#   Env overrides: SEED_ADMIN_USER / SEED_ADMIN_PASSWORD (default admin_test /
#   the V12 test credential — change for a hardened production admin account).
set -euo pipefail

API_BASE="${1:-http://localhost:8080/api/v1}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGES_DIR="${SCRIPT_DIR}/seed-images"
ADMIN_USER="${SEED_ADMIN_USER:-admin_test}"
ADMIN_PASSWORD="${SEED_ADMIN_PASSWORD:-AdminPass123!}"

# productId image-filename pairs, from the V17 seed data (keep in sync with
# the PS1 script and generate-seed-images.py).
PRODUCTS="
10000000-0000-0000-0000-000000000001 velocity-elite-fg-boot.png
10000000-0000-0000-0000-000000000002 phantom-strike-fg-boot.png
10000000-0000-0000-0000-000000000003 pro-match-goal.png
10000000-0000-0000-0000-000000000004 harbour-city-fan-tee.png
10000000-0000-0000-0000-000000000005 dna-training-pants.png
10000000-0000-0000-0000-000000000006 hc-home-jersey.png
10000000-0000-0000-0000-000000000007 hc-away-jersey.png
10000000-0000-0000-0000-000000000008 riverside-home-jersey.png
10000000-0000-0000-0000-000000000009 national-authentic-jersey.png
10000000-0000-0000-0000-000000000010 aero-knit-speed-boot.png
10000000-0000-0000-0000-000000000011 velocity-pro-ag-boot.png
10000000-0000-0000-0000-000000000012 match-ball.png
10000000-0000-0000-0000-000000000013 grip-training-socks.png
10000000-0000-0000-0000-000000000014 lite-carbon-shinguards.png
10000000-0000-0000-0000-000000000015 dna-training-polo.png
"

echo "Logging in as ${ADMIN_USER}..."
LOGIN_BODY=$(printf '{"username":"%s","password":"%s"}' "$ADMIN_USER" "$ADMIN_PASSWORD")
ACCESS_TOKEN=$(curl -sf -X POST "${API_BASE}/auth/login" \
  -H "Content-Type: application/json" \
  --data-binary "$LOGIN_BODY" \
  | sed -n 's/.*"accessToken"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
if [ -z "$ACCESS_TOKEN" ]; then
  echo "ERROR: login did not return an accessToken" >&2
  exit 1
fi

UPLOADED=0
SKIPPED=0
FAILED=0

while read -r PRODUCT_ID FILE; do
  [ -z "$PRODUCT_ID" ] && continue
  IMAGE_FILE="${IMAGES_DIR}/${FILE}"
  if [ ! -f "$IMAGE_FILE" ]; then
    echo "[${PRODUCT_ID}] SKIP - image file not found: ${IMAGE_FILE}"
    SKIPPED=$((SKIPPED + 1))
    continue
  fi

  # Idempotency: skip only when a PRIMARY image exists ("primary":true).
  EXISTING=$(curl -sf "${API_BASE}/product/${PRODUCT_ID}/images" || echo "")
  if printf '%s' "$EXISTING" | grep -q '"primary"[[:space:]]*:[[:space:]]*true'; then
    echo "[${PRODUCT_ID}] SKIPPED (already has a primary image)"
    SKIPPED=$((SKIPPED + 1))
    continue
  fi

  RESULT=$(curl -s -X POST "${API_BASE}/product/${PRODUCT_ID}/images" \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -F "file=@${IMAGE_FILE};type=image/png" \
    -F "isPrimary=true")
  if printf '%s' "$RESULT" | grep -q '"storageKey"'; then
    echo "[${PRODUCT_ID}] UPLOADED - ${FILE} (set as primary)"
    UPLOADED=$((UPLOADED + 1))
  else
    echo "[${PRODUCT_ID}] FAILED - response: ${RESULT}" >&2
    FAILED=$((FAILED + 1))
  fi
done <<EOF
$PRODUCTS
EOF

echo "Done. uploaded=${UPLOADED} skipped=${SKIPPED} failed=${FAILED}"
[ "$FAILED" -eq 0 ] || exit 1
