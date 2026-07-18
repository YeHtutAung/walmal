# testing.md — walmal Testing Guide

## Test Categories

- **Unit tests**: no tag, run by default with `./mvnw test`. Use Mockito; no containers.
- **Integration tests**: tagged `@Tag("integration")`, excluded by default (surefire excludes `integration`). Require Testcontainers (Postgres, Redis, RabbitMQ spun per test class).

## Maven Commands

```bash
# Unit tests only (default)
./mvnw test

# Unit + integration tests
./mvnw test -DexcludedGroups=

# Single module (always add -am to compile walmal-common from source, not stale ~/.m2)
./mvnw -pl walmal-auth -am test
./mvnw -pl walmal-auth -am test -DexcludedGroups=

# JAR rebuild after any config/resource change (required before E2E tests)
./mvnw -pl walmal-app -am -DskipTests clean package
```

## Testcontainers Workaround

Docker Engine 29.x breaks Testcontainers 1.20.4 API negotiation (`/info` HTTP 400).
Workaround: pass `-Dapi.version=1.44` to every integration-test run:

```bash
./mvnw -pl walmal-auth -am test -DexcludedGroups= -Dapi.version=1.44
```

Testcontainers version: `1.20.4` (property `testcontainers.version` in root `pom.xml`).

## Test Profile (`application-test.yml`)

Located at `walmal-app/src/main/resources/application-test.yml`. Activated with `-Dspring.profiles.active=test`. Its overrides (rate limits, CORS, profile marker) are cross-repo facts — see `docs/kb/SYSTEM.md` "Test profile".

## Stale JAR Rule

After any change to files under `walmal-app/src/main/resources/` (including `application-test.yml`), the JAR must be rebuilt before running E2E tests. A stale JAR silently ignores config changes — the running process reads from the packaged copy, not the working tree.

```bash
cd C:/YHA/006_Claude_Workspace/walmal
./mvnw -pl walmal-app -am -DskipTests clean package
```

## Seed Catalog (V9 + V17 — "Walmal Sport")

`V9__seed_dev_data.sql` originally seeded a generic electronics/apparel demo
catalog. `V17__reseed_sports_catalog.sql` rebrands it in place as **Walmal
Sport**: the 5 original products/variants keep their UUIDs and prices
(E2E + k6 depend on them) but get sports names/SKUs, and 10 new sports
products are added — **15 seeded products total**
(`10000000-0000-0000-0000-000000000001` … `0015`).

Categories flatten to 4 active root categories: Jerseys (`jerseys`,
`c0000000-0000-0000-0000-000000000021`), Boots (`boots`,
`c0000000-0000-0000-0000-000000000011`), Teamwear (`teamwear`,
`c0000000-0000-0000-0000-000000000022`), Equipment (`equipment`,
`c0000000-0000-0000-0000-000000000012`). The old parents Electronics
(`c0000000-0000-0000-0000-000000000001`) and Apparel
(`c0000000-0000-0000-0000-000000000002`) are set `is_active=FALSE` but
still come back from `GET /product/categories` with `active:false` — the
tree endpoint does not filter on `is_active`.

Test-critical variants keep their UUIDs and prices:
`20000000-0000-0000-0000-000000000001` = SKU `WP-VELO-LE-UK9` "Velocity
Elite LE UK 9 Chaos Red" $1199.99 (product: Velocity Elite FG Boot);
`20000000-0000-0000-0000-000000000002` = SKU `WP-VELO-LE-UK9G` "Velocity
Elite LE UK 9 Gold Limited" $1419.99. The V9 `order_items` snapshot text
("Galaxy S24 Ultra 256GB Black") is intentionally left untouched by V17 —
it's a historical order-line snapshot, not live catalog data.

**Redis caveat:** the category tree is cached at `product:category:tree`
(30-minute TTL, see `ProductSearchServiceImpl`). Flyway migrations write
directly to Postgres and never evict it, so after running V17 (or any raw
SQL reseed) the tree can keep serving stale category data for up to 30
minutes — flush Redis (`docker exec walmal-redis redis-cli FLUSHALL`) or
wait out the TTL after migrating.

V17 also deletes the `product_images` rows for the 5 renamed products
(their old electronics/apparel art no longer matches), so all 15 products
have zero images post-migration and `primaryImageUrl` is null until images
are uploaded — the storefront/admin show "No image" placeholders otherwise.

`scripts/generate-seed-images.py` (PIL; run via `python`, not the
PowerShell python shim, which is a broken Cygwin stub on this machine)
generates 15 flat-illustration PNGs into `scripts/seed-images/`, one per
product. `scripts/seed-product-images.ps1` then logs in as `admin_test`
and for each of the 15 product IDs uploads the matching PNG via
`POST /api/v1/product/{id}/images` (`isPrimary=true` set on upload — the
endpoint does not auto-primary the first image). Idempotent: skips a
product if it already has a **primary** image (a stray non-primary image,
e.g. left behind by the admin E2E product-CRUD spec, does not block
seeding). Re-run both after a postgres/minio volume wipe
(`docker compose down -v`), or any time the 15 demo products come back
imageless:

```bash
python scripts/generate-seed-images.py
```

```powershell
pwsh -File scripts/seed-product-images.ps1
```

Uploaded objects land in the MinIO bucket `product-images`
(`http://localhost:9000/product-images/...`).

## E2E Tests (walmal-store Playwright)

E2E tests live in `../walmal-store/tests/e2e/`. See `../walmal-store/docs/kb/testing.md` for the Playwright setup. The backend must be running with the test profile and the current JAR.

## k6 Performance Tests

Scripts live in `tests/performance/` (6 scripts: auth, checkout, inventory, pos, product, warehouse). See `docs/kb/gotchas.md` for k6 invocation notes and rate-limit overrides required for load testing.

## CI/CD Pipeline (GitHub Actions, `.github/workflows/ci.yml`)

Five jobs on push/PR to `main`/`develop`:

1. **test** — full `./mvnw verify` against Postgres/RabbitMQ/Redis service
   containers (247 tests).
2. **build-and-push** (needs `test`; push only) — builds the JAR, then the
   Docker image, pushed to GHCR (`ghcr.io/<owner>/walmal-app`), tagged
   `sha-<commit>` always and `latest` on `main`.
3. **security-scan** (needs `build-and-push`; push only) — Trivy scans the
   pushed image (CRITICAL/HIGH), uploads SARIF to the GitHub Security tab;
   non-blocking (`exit-code 0`).
4. **deploy** ("Deploy (production)"; needs `[build-and-push, security-scan]`;
   `environment: production`) — SSHes to the VPS (`appleboy/ssh-action`,
   `DEPLOY_HOST`/`DEPLOY_USER`/`DEPLOY_SSH_KEY` secrets) and runs
   `docker compose -f docker-compose.prod.yml pull app && ... up -d app`
   in `/opt/walmal`, after a `git pull --ff-only` so compose/Caddyfile stay
   current on the box.
5. **smoke** (needs `deploy`; same gate) — curls
   `https://api.${WALMAL_DOMAIN}/actuator/health` (retries ~12×10s for
   `"status":"UP"`) and `https://shop.${WALMAL_DOMAIN}` for HTTP 200. No
   secrets in URLs — `WALMAL_DOMAIN` is a public repo variable.

**Single production deploy, no staging** (collapsed 2026-07-19 — the old
pipeline had a 4-job staging→prod chain with manual approval between them;
that's gone in favor of one gated deploy since there's a single VPS). See
`docs/DEPLOYMENT.md` for the full runbook.

**Deploy is skipped by default**: jobs 4–5 are gated on the repo variable
`DEPLOY_ENABLED == 'true'` (jobs can't read secrets in `if:`, so a variable
is the switch). Until the user provisions the VPS and sets it, pushes go
green through job 3 and jobs 4–5 show as skipped — CI requires zero
infrastructure to stay green.
