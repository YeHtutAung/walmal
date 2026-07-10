# SYSTEM.md — Cross-Repo Facts (canonical home)

> Facts here are the single source of truth for all three repos. Do not duplicate them.

## Repo Map

| Repo | Role | Workspace path | Default port |
|------|------|----------------|--------------|
| walmal | Spring Boot modular monolith (hub) | `C:/YHA/006_Claude_Workspace/walmal` | 8080 |
| walmal-store | Next.js App Router storefront | `C:/YHA/006_Claude_Workspace/walmal-store` | 3000 dev / 3001 E2E |
| walmal-admin | Vite + React + Refine admin SPA | `C:/YHA/006_Claude_Workspace/walmal-admin` | 5173 (Vite default — not pinned in vite.config.ts; verify before relying on it) |

## Infrastructure Services (`walmal/docker-compose.yml`)

| Service | Port(s) | Notes |
|---------|---------|-------|
| postgres 15 | 5432 | primary DB |
| redis 7 | 6379 | cache / session / locks |
| rabbitmq 3-management | 5672 AMQP, 15672 mgmt UI | message broker |
| minio | 9000 API, 9001 console | S3-compatible file storage |
| mailhog | 1025 SMTP, 8025 UI | dev email sink |
| app (Spring Boot) | 8080 | built image; dev/E2E use the JAR directly instead |

## Auth Contract

- Tokens: signed HS256 JWTs, base64url-encoded (requires padding + char-swap before `atob()`).
- Access-token TTL: **15 minutes** (`walmal.jwt.access-token-expire-minutes: 15` in `application.yml`; no env override). Signing key: `WALMAL_JWT_SECRET`.
- Refresh tokens: single-use rotating; stored server-side in Redis.
- Roles (enum `com.walmal.auth.domain.Role`): `ADMIN`, `STAFF`, `CASHIER`, `CUSTOMER`, `WAREHOUSE_MANAGER`, `WAREHOUSE_STAFF`, `POS_OPERATOR`.
- Per-client storage:
  - walmal-store: httpOnly `walmal-rt` cookie set by Next.js proxy routes (`/api/auth/*`); access token in Zustand memory only.
  - walmal-admin: `accessToken` + `refreshToken` in localStorage (known risk — see `../walmal-admin/docs/kb/gotchas.md`).

## Error-Body Contract

| Source | Shape | Key field |
|--------|-------|-----------|
| Spring backend (all modules) | RFC 9457 ProblemDetail | `detail` |
| walmal-store auth proxy routes | `{ code, message }` | `message` |
| walmal-store payment-intent route | `{ error }` | `error` |

Client parsing precedence: field errors > `message` > `detail`.

## Event Contract

- Transactional outbox: business logic writes to `outbox_events` (V15 migration) inside the same DB transaction.
- `OutboxRelay` (`walmal-infrastructure`) polls and delivers to RabbitMQ after commit.
- Routing-key scheme: `{module}.{event}` (e.g. `order.created`, `inventory.stock.low`); exchange scheme: `{module}.exchange`.
- Delivery guarantee: at-least-once (relay retries up to 60 attempts before parking a row as FAILED; a delete rollback can re-send a row). Consumers must be idempotent.
- FAILED-row recovery: reset via `UPDATE outbox_events SET status='PENDING', attempts=0 WHERE status='FAILED'`. See `docs/DR_PLAN.md` Scenario 6.

## Environment Variables Matrix (names + purpose only — never values)

### walmal (Spring Boot)
- `WALMAL_JWT_SECRET` — HS256 signing key
- `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` — Postgres connection
- `SPRING_DATA_REDIS_HOST/PORT/PASSWORD` — Redis connection
- `SPRING_RABBITMQ_HOST/PORT/USERNAME/PASSWORD/VIRTUAL_HOST` — RabbitMQ
- `MINIO_URL`, `MINIO_PUBLIC_URL`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` — MinIO
- `SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD` — SMTP (MailHog in dev)
- `WALMAL_CORS_ALLOWED_ORIGINS` — comma-separated allowed origins
- `WALMAL_RATE_LIMIT_AUTHENTICATED` — req/min for auth'd users (default 60)
- `WALMAL_RATE_LIMIT_UNAUTHENTICATED` — req/min for guests (default 20)
- `WALMAL_TRUST_PROXY` — set true when behind a reverse proxy

### walmal-store (Next.js)
- `NEXT_PUBLIC_API_URL` — backend base URL (e.g. `http://localhost:8080/api/v1`)
- `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` — Stripe pk_test_/pk_live_
- `STRIPE_SECRET_KEY` — Stripe sk_test_/sk_live_ (server-side only)

### walmal-admin (Vite)
- `VITE_API_BASE_URL` — backend base URL

### Test profile (`application-test.yml`)
- Rate limits: 100,000 req/min (both auth'd and unauth'd) — effectively unlimited.
- CORS: includes `http://localhost:3001` for E2E Next.js server.
- Profile marker: `info.walmal.profile: test` visible at public `/actuator/info` (used by `global-setup.ts` drift check).

## Test Credentials

Seed accounts are defined in `walmal-app/src/main/resources/db/migration/V12__auth_add_test_accounts.sql`. That file is the source of truth for usernames, emails, and roles. Do not store passwords here.
