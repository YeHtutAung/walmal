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

## Seed Product Images (test data)

V9 seeds 5 demo products (`10000000-0000-0000-0000-000000000001` … `0005`)
with zero `product_images` rows, so `primaryImageUrl` is null until images
are uploaded — the storefront/admin show "No image" placeholders otherwise.

`scripts/seed-product-images.ps1` fixes this: logs in as `admin_test`, and
for each of the 5 product IDs uploads the matching PNG from
`scripts/seed-images/` via `POST /api/v1/product/{id}/images`
(`isPrimary=true` set on upload — the endpoint does not auto-primary the
first image). Idempotent: skips a product if it already has a **primary**
image (a stray non-primary image, e.g. left behind by the admin E2E
product-CRUD spec, does not block seeding). Re-run it after a
postgres/minio volume wipe (`docker compose down -v`), or any time the 5
demo products come back imageless:

```powershell
pwsh -File scripts/seed-product-images.ps1
```

Uploaded objects land in the MinIO bucket `product-images`
(`http://localhost:9000/product-images/...`).

## E2E Tests (walmal-store Playwright)

E2E tests live in `../walmal-store/tests/e2e/`. See `../walmal-store/docs/kb/testing.md` for the Playwright setup. The backend must be running with the test profile and the current JAR.

## k6 Performance Tests

Scripts live in `tests/performance/` (6 scripts: auth, checkout, inventory, pos, product, warehouse). See `docs/kb/gotchas.md` for k6 invocation notes and rate-limit overrides required for load testing.
