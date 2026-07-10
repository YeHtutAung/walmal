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

Located at `walmal-app/src/main/resources/application-test.yml`. Activated with `-Dspring.profiles.active=test`.

Key overrides:
- `walmal.rate-limit.authenticated-limit: 100000` (effectively unlimited)
- `walmal.rate-limit.unauthenticated-limit: 100000`
- CORS includes `http://localhost:3001`
- `info.walmal.profile: test` — exposed at `/actuator/info`; used by E2E `global-setup.ts` to detect stale JARs

## Stale JAR Rule

After any change to files under `walmal-app/src/main/resources/` (including `application-test.yml`), the JAR must be rebuilt before running E2E tests. A stale JAR silently ignores config changes — the running process reads from the packaged copy, not the working tree.

```bash
cd C:/YHA/006_Claude_Workspace/walmal
./mvnw -pl walmal-app -am -DskipTests clean package
```

## E2E Tests (walmal-store Playwright)

E2E tests live in `walmal-store/tests/e2e/`. See `walmal-store/docs/kb/testing.md` for the Playwright setup. The backend must be running with the test profile and the current JAR.

## k6 Performance Tests

Scripts live in `tests/performance/` (6 scripts: auth, checkout, inventory, pos, product, warehouse). See `docs/kb/gotchas.md` for k6 invocation notes and rate-limit overrides required for load testing.
