# SYSTEM.md — Cross-Repo Facts (canonical home)

> Facts here are the single source of truth for all three repos. Do not duplicate them.

## Repo Map

| Repo | Role | Workspace path | Default port |
|------|------|----------------|--------------|
| walmal | Spring Boot modular monolith (hub) | `C:/YHA/006_Claude_Workspace/walmal` | 8080 |
| walmal-store | Next.js App Router storefront | `C:/YHA/006_Claude_Workspace/walmal-store` | 3000 dev / 3001 E2E |
| walmal-admin | Vite + React + Refine admin SPA | `C:/YHA/006_Claude_Workspace/walmal-admin` | 5173 dev (Vite default — not pinned in vite.config.ts); 5174 E2E (pinned, strictPort, in `playwright.config.ts`) |

## Infrastructure Services (`walmal/docker-compose.yml`)

| Service | Port(s) | Notes |
|---------|---------|-------|
| postgres 15 | 5432 | primary DB |
| redis 7 | 6379 | cache / session / locks |
| rabbitmq 3-management | 5672 AMQP, 15672 mgmt UI | message broker |
| minio | 9000 API, 9001 console | S3-compatible file storage |
| mailhog | 1025 SMTP, 8025 UI | dev email sink |
| app (Spring Boot) | 8080 | built image; **`profiles: ["full"]` — NOT started by a bare `docker compose up`**. Dev/E2E use the JAR directly instead; run the container with `docker compose --profile full up -d --wait`. |

`docker compose up -d --wait` starts the five backing services only. `app` is
profile-gated on purpose: before that, a bare `up` also built and started the
backend on 8080, so the quickstart's `java -jar` step then failed on a bound
port while `/actuator/health` still answered UP *from the container* — a green
check masking the failure. Both E2E configs had independently worked around it
by naming the five services explicitly; they no longer need to.

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

## Admin-Facing Endpoint Contracts

Endpoints intended for consumption by `walmal-admin` (or other non-walmal clients) that aren't covered by the generic Auth/Error contracts above.

| Endpoint | Auth | Response |
|----------|------|----------|
| `GET /api/v1/orders/admin/daily-summary` | `ADMIN` or `STAFF` role (JWT) | `ApiResponse<List<DailyOrderSummaryDto>>` — 30 entries, one per UTC day `[today-29, today]` inclusive, zero-filled. Each: `{date: LocalDate, orderCount: long, revenue: BigDecimal (scale 2), currency: String}`. `orderCount` = all statuses; `revenue` = sum of `FULFILLED`-order `totalAmount` for that day; `currency` = first `FULFILLED` order's currency in the window, `"USD"` if none. |
| `GET /api/v1/inventory/categories/stock-health` | `ADMIN`, `STAFF`, or `WAREHOUSE_MANAGER` role (JWT) | `ApiResponse<List<CategoryStockHealthDto>>` — one entry per category (including categories with zero products), sorted alphabetically by category name. Each: `{categoryId, categoryName, productCount, okCount, lowCount, criticalCount}`. `productCount` = distinct products in that category (including variant-less products); `okCount`/`lowCount`/`criticalCount` = per-variant stock-row health tallies (`InventoryStock.classifyHealth()`) — these need not sum to `productCount` (a category can have products with zero stock rows). Owned by `walmal-inventory` (see `docs/kb/architecture.md` "Admin Aggregation Endpoints" for the module-ownership rationale). **Two consumers in `walmal-admin` — change this shape and check both:** (1) the dashboard (the Inventory tab of `TabbedChart.tsx` — the 2026-07 Tier 2 restructure absorbed the earlier `CategoryStockHealthChart.tsx`, which had replaced the old global-only Stock Health chart); (2) the **categories list** (`src/pages/categories/list.tsx`, via `list-helpers.ts#buildProductCountMap`), which uses `productCount` for its per-category product-count column. Consumer (2) is easy to miss — it is not a chart and lives outside `pages/dashboard/`. |
| `GET /api/v1/product/search?q=` | Public (`permitAll` GET path in `AuthSecurityConfig`) | `ApiResponse<Page<ProductSummaryDto>>` — each: `{productId, name, slug, brand, primaryImageUrl, lowestPrice, currency}`; default page size 20. Matches case-insensitive *contains* on product name, brand, variant SKU, or variant barcode; LIKE wildcards (`%`, `_`, `\`) in `q` match literally. **Empty/blank/missing `q` = list-all.** That is load-bearing: it is the admin products list page's list-all path, so this endpoint deliberately has **no** min-length guard (a code comment in `ProductSearchServiceImpl` says not to add one — doing so breaks that page). Same URL/params/response as before global search; only the matching semantics were widened (was name/brand only). |
| `GET /api/v1/orders/admin/search?q=` | `ADMIN` or `STAFF` role (JWT) | `ApiResponse<Page<OrderAdminSummaryDto>>` — each: `{id, userId, status, totalAmount, currency, itemCount, createdAt}`; default page size 20, sorted by `createdAt` ascending (oldest first — callers wanting newest-first must pass sort explicitly). Matches order-ID **prefix** (case-insensitive — uppercase pasted IDs are folded; Postgres renders UUIDs lowercase) or guest-email **substring**; registered-customer orders (null guest email) match only via the ID predicate. Guard: `q` missing (`defaultValue = ""`) or shorter than 2 chars after trim returns an empty page **without touching the DB**. Wildcards in `q` match literally. |
| `GET /api/v1/auth/users/search?q=` | `ADMIN` role only (JWT) | **Bare `Page<UserProfileResponse>` — NO `ApiResponse` envelope** (the auth module's own response convention; walmal-admin's shared `unwrap` tolerates both shapes). Each: `{id, username, email, role, isActive}`. Matches username or email substring, case-insensitive. Same guard as orders search: `q` missing (`defaultValue = ""`) or under 2 chars after trim returns an empty page without touching the DB. Wildcards in `q` match literally (the derived Spring Data query escapes them itself). |
| `POST /api/v1/auth/users` | `ADMIN` role only (JWT) | Body `{username, email, password, role, active?}` — `active` is optional `Boolean`; `null`/omitted = `true` (pre-existing default). `false` creates the user inactive (walmal-admin's create-user form sends it; was silently ignored before 2026-07-12). Response: **bare `UserProfileResponse` — NO `ApiResponse` envelope** (auth-module convention, same as `users/search` above). |

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
- CORS: includes `http://localhost:3001` (store E2E) and `:5173`–`:5177` (admin dev/E2E; admin E2E pins `:5174`).
- Profile marker: `info.walmal.profile: test` visible at public `/actuator/info` (used by `global-setup.ts` drift check).

## Test Credentials

Seed accounts are defined in `walmal-app/src/main/resources/db/migration/V12__auth_add_test_accounts.sql`. That file is the source of truth for usernames, emails, and roles. Do not store passwords here.
