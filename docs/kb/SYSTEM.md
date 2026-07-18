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

## Payment Verification Contract (walmal-store ↔ walmal)

Web checkout is **client-confirms, server-verifies**. The shopper confirms the
card payment in the browser (Stripe `confirmCardPayment`); the store then calls
`POST /api/v1/orders` and **must include `paymentReference`** — the Stripe
PaymentIntent id (`pi_...`). `paymentReference` is required (`@NotBlank`); a
request without it is `400`, so no order can be confirmed without a payment to
verify.

The backend verifies server-side before confirming (never trusts the client):
- `stub` gateway (dev/test/demo) accepts any reference — no real check.
- `stripe` gateway (production) retrieves the PaymentIntent by that id and
  confirms the order only if Stripe reports it `succeeded` **and** its amount
  (in minor units) and currency equal the server-computed order total. A
  mismatch or a non-succeeded intent releases the reservation and cancels the
  order (`PAYMENT_FAILED`).

Order currency is server-authoritative regardless of gateway: it must equal the
variant's own price currency, else `400` (a client cannot pay a cheaper currency
for the same goods).

POS in-store sales are terminal-authoritative: `PosSaleServiceImpl` passes a
`pos-terminal:<terminalId>` reference, which the stripe gateway accepts without a
Stripe call (payment was captured at the reader).

## Admin-Facing Endpoint Contracts

Endpoints intended for consumption by `walmal-admin` (or other non-walmal clients) that aren't covered by the generic Auth/Error contracts above.

| Endpoint | Auth | Response |
|----------|------|----------|
| `GET /api/v1/orders/admin/daily-summary` | `ADMIN` or `STAFF` role (JWT) | `ApiResponse<List<DailyOrderSummaryDto>>` — 30 entries, one per UTC day `[today-29, today]` inclusive, zero-filled. Each: `{date: LocalDate, orderCount: long, revenue: BigDecimal (scale 2), currency: String}`. `orderCount` = all statuses; `revenue` = sum of `FULFILLED`-order `totalAmount` for that day; `currency` = first `FULFILLED` order's currency in the window, `"USD"` if none. |
| `GET /api/v1/inventory/categories/stock-health` | `ADMIN`, `STAFF`, or `WAREHOUSE_MANAGER` role (JWT) | `ApiResponse<List<CategoryStockHealthDto>>` — one entry per category (including categories with zero products), sorted alphabetically by category name. Each: `{categoryId, categoryName, productCount, okCount, lowCount, criticalCount}`. `productCount` = distinct products in that category (including variant-less products); `okCount`/`lowCount`/`criticalCount` = per-variant stock-row health tallies (`InventoryStock.classifyHealth()`) — these need not sum to `productCount` (a category can have products with zero stock rows). Owned by `walmal-inventory` (see `docs/kb/architecture.md` "Admin Aggregation Endpoints" for the module-ownership rationale). **Two consumers in `walmal-admin` — change this shape and check both:** (1) the dashboard (the Inventory tab of `TabbedChart.tsx` — the 2026-07 Tier 2 restructure absorbed the earlier `CategoryStockHealthChart.tsx`, which had replaced the old global-only Stock Health chart); (2) the **categories list** (`src/pages/categories/list.tsx`, via `list-helpers.ts#buildProductCountMap`), which uses `productCount` for its per-category product-count column. Consumer (2) is easy to miss — it is not a chart and lives outside `pages/dashboard/`. |
| `GET /api/v1/product/search?q=&status=` | Public (`permitAll` GET path in `AuthSecurityConfig`) | `ApiResponse<Page<ProductSummaryDto>>` — each: `{productId, name, slug, brand, primaryImageUrl, lowestPrice, currency}`; default page size 20. Matches case-insensitive *contains* on product name, brand, variant SKU, or variant barcode; LIKE wildcards (`%`, `_`, `\`) in `q` match literally. **Empty/blank/missing `q` = list-all.** That is load-bearing: it is the admin products list page's list-all path, so this endpoint deliberately has **no** min-length guard (a code comment in `ProductSearchServiceImpl` says not to add one — doing so breaks that page). **`status` (optional, 2026-07-18): `ACTIVE` or `INACTIVE`; absent = all statuses — also load-bearing for the admin list, which needs INACTIVE rows. The storefront passes `status=ACTIVE` on every catalog fetch (its listing/homepage never show deactivated products, incl. admin-E2E residue). Invalid value → framework 400.** |
| `GET /api/v1/product/categories/{categoryId}/products?status=` | Public (`permitAll` GET path in `AuthSecurityConfig`) | `ApiResponse<Page<ProductSummaryDto>>` (same DTO as `product/search`); default page size 20. Optional `status` param with the same contract as `product/search` (absent = all statuses; the storefront passes `ACTIVE`). |
| `POST /api/v1/payment/webhook` | **Stripe signature** (`Stripe-Signature` header verified against `STRIPE_WEBHOOK_SECRET` — the signature IS the auth; the path is `permitAll` and exempt from the gateway rate limiter because Stripe retries on any non-2xx incl. 429) | Reconciliation log, NOT an authorization step (the synchronous server-side `PaymentIntent.retrieve` at order creation remains the real verification). `payment_intent.succeeded`/`payment_intent.payment_failed` → row in `payment_webhook_events` (V18): status `MATCHED` when the intent id equals an order's `payment_reference`, else `UNMATCHED` (the flag to investigate). Idempotent on `event_id` (unique constraint; duplicates ack 200 with no second row). Unknown event types → 200, no row. Bad/missing signature → 400, nothing persisted. Intent id is read from the event's **raw JSON** (`data.object.id`) — the typed stripe-java deserializer returns empty on API-version mismatch. 2026-07-19. |
| `GET /api/v1/orders/admin/search?q=` | `ADMIN` or `STAFF` role (JWT) | `ApiResponse<Page<OrderAdminSummaryDto>>` — each: `{id, userId, status, totalAmount, currency, itemCount, createdAt}`; default page size 20, sorted by `createdAt` ascending (oldest first — callers wanting newest-first must pass sort explicitly). Matches order-ID **prefix** (case-insensitive — uppercase pasted IDs are folded; Postgres renders UUIDs lowercase) or guest-email **substring**; registered-customer orders (null guest email) match only via the ID predicate. Guard: `q` missing (`defaultValue = ""`) or shorter than 2 chars after trim returns an empty page **without touching the DB**. Wildcards in `q` match literally. |
| `GET /api/v1/auth/users/search?q=` | `ADMIN` role only (JWT) | **Bare `Page<UserProfileResponse>` — NO `ApiResponse` envelope** (the auth module's own response convention; walmal-admin's shared `unwrap` tolerates both shapes). Each: `{id, username, email, role, isActive}`. Matches username or email substring, case-insensitive. Same guard as orders search: `q` missing (`defaultValue = ""`) or under 2 chars after trim returns an empty page without touching the DB. Wildcards in `q` match literally (the derived Spring Data query escapes them itself). |
| `POST /api/v1/auth/users` | `ADMIN` role only (JWT) | Body `{username, email, password, role, active?}` — `active` is optional `Boolean`; `null`/omitted = `true` (pre-existing default). `false` creates the user inactive (walmal-admin's create-user form sends it; was silently ignored before 2026-07-12). Response: **bare `UserProfileResponse` — NO `ApiResponse` envelope** (auth-module convention, same as `users/search` above). |

## Environment Variables Matrix (names + purpose only — never values)

### walmal (Spring Boot)
- `WALMAL_JWT_SECRET` — HS256 signing key. **Mandatory: no default.** The
  default profile has no fallback value, so the app fails fast at startup
  ("walmal.jwt.secret must not be blank") if it is unset — it cannot boot on
  a committed key. Must be ≥32 bytes. Only the `test` profile supplies a
  throwaway key (via `application-test.yml`), for local/E2E runs.
- `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` — Postgres connection
- `SPRING_DATA_REDIS_HOST/PORT/PASSWORD` — Redis connection
- `SPRING_RABBITMQ_HOST/PORT/USERNAME/PASSWORD/VIRTUAL_HOST` — RabbitMQ
- `MINIO_URL`, `MINIO_PUBLIC_URL`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` — MinIO
- `SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD` — SMTP (MailHog in dev)
- `WALMAL_CORS_ALLOWED_ORIGINS` — comma-separated allowed origins
- `WALMAL_RATE_LIMIT_AUTHENTICATED` — req/min for auth'd users (default 60)
- `WALMAL_PAYMENT_GATEWAY` — which payment gateway to activate. **Fail-closed:
  no default.** Unset means no gateway bean is created, so the app fails fast at
  startup (`OrderCreationServiceImpl` has an unsatisfied `PaymentGatewayService`
  dependency) rather than confirming orders on the always-success stub. The
  `test` profile sets it to `stub` (local/dev/demo/E2E, accepts any reference).
  Production sets it to `stripe`, which verifies each order's payment against
  Stripe server-side (see the Payment Verification contract below) and requires
  `STRIPE_SECRET_KEY`. Never `stub` in production.
- `STRIPE_SECRET_KEY` — required by the backend when `WALMAL_PAYMENT_GATEWAY=stripe`
  (maps to `walmal.payment.stripe.secret-key`; the stripe gateway fails fast at
  startup if blank). The **same** secret the walmal-store payment-intent route
  uses to create the intent — store creates, backend verifies.
- `STRIPE_WEBHOOK_SECRET` — verifies `POST /api/v1/payment/webhook`'s
  `Stripe-Signature` header (maps to `walmal.payment.stripe.webhook-secret`;
  registered unconditionally — NOT gated on `WALMAL_PAYMENT_GATEWAY` — and fails
  fast at startup if blank in any full-context profile; the `test` profile ships
  a throwaway default, env-overridable).
- `WALMAL_RATE_LIMIT_UNAUTHENTICATED` — req/min for guests (default 20)
- `WALMAL_TRUST_PROXY` — set true when behind a reverse proxy
- `SPRING_MAIL_SMTP_AUTH` / `SPRING_MAIL_SMTP_STARTTLS_ENABLE` — mail auth/TLS
  flags (`application-prod.yml`), both default `false`. The prod profile used
  to hardcode `true`/`true`; MailHog (the prod-compose email sink — see
  "Production Deployment Topology" below) supports neither, so these are now
  env-driven with MailHog-compatible defaults. Set both `true` only if
  `SPRING_MAIL_HOST` is swapped for a real SMTP provider.

### Deployment (`docker-compose.prod.yml` / `deploy/Caddyfile`, not Spring properties)
- `WALMAL_DOMAIN` — bare apex domain; Caddy builds
  `shop./admin./api./status./mail.{$WALMAL_DOMAIN}` from it.
- `ACME_EMAIL` — Let's Encrypt registration/expiry-notice address (Caddy
  global `email` option).
- `MAILHOG_BASIC_AUTH` — one "username bcrypt-hash" line (from
  `caddy hash-password`) gating the `mail.` subdomain.
- `WALMAL_IMAGE` / `WALMAL_STORE_IMAGE` / `WALMAL_ADMIN_IMAGE` — optional GHCR
  image overrides (default `:latest`; set to a `sha-*` tag to pin/rollback).

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

## Seed Catalog ("Walmal Sport")

`walmal-app/src/main/resources/db/migration/V17__reseed_sports_catalog.sql`
rebrands the dev/E2E catalog in place as a sports store. Same UUIDs and
prices as before (all three repos' E2E/k6 suites depend on them) —
only names, slugs, descriptions, brands, SKUs, and attributes change, plus
10 new products.

- 4 active root categories: Jerseys (`jerseys`,
  `c0000000-0000-0000-0000-000000000021`), Boots (`boots`,
  `c0000000-0000-0000-0000-000000000011`), Teamwear (`teamwear`,
  `c0000000-0000-0000-0000-000000000022`), Equipment (`equipment`,
  `c0000000-0000-0000-0000-000000000012`). The old parents Electronics
  (`c0000000-0000-0000-0000-000000000001`) and Apparel
  (`c0000000-0000-0000-0000-000000000002`) are deactivated but still
  returned by `GET /product/categories` with `active:false` — the tree
  endpoint does not filter on `is_active`.
- 15 seeded products total (`10000000-0000-0000-0000-000000000001` …
  `0015`). Test-critical variants keep their UUIDs and prices:
  `20000000-0000-0000-0000-000000000001` = SKU `WP-VELO-LE-UK9` "Velocity
  Elite LE UK 9 Chaos Red" $1199.99 (product: Velocity Elite FG Boot);
  `20000000-0000-0000-0000-000000000002` = SKU `WP-VELO-LE-UK9G`
  "Velocity Elite LE UK 9 Gold Limited" $1419.99.
- Reseed caveat: the category tree is Redis-cached
  (`product:category:tree`, 30-min TTL) and Flyway's raw SQL never evicts
  it, so a stale tree can serve for up to 30 minutes after migrating —
  flush Redis or wait out the TTL before relying on fresh category data.
- Seed images: `walmal/scripts/generate-seed-images.py` generates 15 PNGs
  into `walmal/scripts/seed-images/`; `walmal/scripts/seed-product-images.ps1`
  uploads them (idempotent — skips products with an existing primary
  image). Re-run both after a volume wipe.
- The V9 `order_items` snapshot text ("Galaxy S24 Ultra 256GB Black") is
  intentionally untouched by V17 — it's a historical order-line snapshot,
  not live catalog data.

## Test Credentials

Seed accounts are defined in `walmal-app/src/main/resources/db/migration/V12__auth_add_test_accounts.sql`. That file is the source of truth for usernames, emails, and roles. Do not store passwords here.
