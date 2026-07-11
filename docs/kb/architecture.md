# architecture.md — walmal Spring Boot Modular Monolith

## Maven Modules (root `pom.xml` `<modules>` order)

| Module | Role |
|--------|------|
| `walmal-common` | Shared domain interfaces (`DomainEventPublisher`, `FileStorageService`, `CacheService`, `NotificationChannel`) and value objects; no Spring beans |
| `walmal-infrastructure` | Concrete implementations of common interfaces (RabbitMQ, MinIO, Redis, SMTP); `OutboxRelay`; `InfrastructureConfiguration` |
| `walmal-auth` | User accounts, JWT issuance/validation, roles, refresh-token lifecycle |
| `walmal-product` | Product catalogue, categories, variants, images |
| `walmal-inventory` | Stock levels, reservations, locations, outbox-driven reservation events |
| `walmal-order` | Order lifecycle (PENDING → CONFIRMED → SHIPPED → DELIVERED / CANCELLED); guest order support |
| `walmal-pos` | Point-of-sale sales, offline-sync conflict resolution |
| `walmal-warehouse` | Fulfillments (`warehouse_fulfillments`/`warehouse_fulfillment_lines`), picking/packing/shipping workflow |
| `walmal-notification` | Email notifications via `NotificationChannel`; `notification_log` table; guest-recipient support |
| `walmal-app` | Assembly: API gateway layer, `RateLimitFilter`, `GlobalExceptionHandler`, Spring Boot main class; packages the runnable JAR |

## Module Communication Rules

- **Synchronous (in-process):** modules call each other via service *interfaces* defined in `application/` — never via another module's Repository bean.
- **Asynchronous:** use `DomainEventPublisher` → transactional outbox → RabbitMQ. Never call another module's service directly for async work.
- Business logic depends only on `walmal-common` interfaces; `walmal-infrastructure` provides implementations (DIP).

## Key Paths

| Item | Path |
|------|------|
| Flyway migrations | `walmal-app/src/main/resources/db/migration/` |
| OutboxRelay | `walmal-infrastructure/src/main/java/com/walmal/infrastructure/messaging/OutboxRelay.java` |
| RabbitDomainEventPublisher | `walmal-infrastructure/src/main/java/com/walmal/infrastructure/messaging/RabbitDomainEventPublisher.java` |
| RateLimitFilter | `walmal-app/src/main/java/com/walmal/gateway/filter/RateLimitFilter.java` |
| InfrastructureConfiguration | `walmal-infrastructure/src/main/java/com/walmal/infrastructure/config/InfrastructureConfiguration.java` |
| GlobalExceptionHandler | `walmal-app/src/main/java/com/walmal/gateway/exception/GlobalExceptionHandler.java` |
| AuthExceptionHandler | `walmal-auth/src/main/java/com/walmal/auth/api/AuthExceptionHandler.java` |
| Role enum | `walmal-auth/src/main/java/com/walmal/auth/domain/Role.java` |
| Main application config | `walmal-app/src/main/resources/application.yml` |
| Test profile config | `walmal-app/src/main/resources/application-test.yml` |

## Admin Aggregation Endpoints

`GET /api/v1/orders/admin/daily-summary` (`walmal-order`, `OrderController`) — `@PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")`, same gate as `/orders/admin`. Returns `ApiResponse<List<DailyOrderSummaryDto>>`: exactly 30 entries, one per UTC day `[today-29, today]` inclusive, zero-filled for days with no orders. Each entry: `{date: LocalDate, orderCount: long, revenue: BigDecimal (scale 2), currency: String}`. `orderCount` counts orders of every status that day; `revenue` sums `totalAmount` for `FULFILLED`-status orders only that day; `currency` is the first `FULFILLED` order's currency across the whole window, defaulting to `"USD"` if none fulfilled.

Implementation: `OrderAdminServiceImpl.getDailySummary()` computes the UTC cutoff (`today.minusDays(29)`), calls `OrderRepository.findForDailySummary(Instant cutoff)` — a JPQL constructor-projection query returning lightweight `OrderTimeseriesRow` records (`createdAt`, `totalAmount`, `currency`, `status`), not full `Order` entities — then delegates to the pure, unit-testable `OrderAdminServiceImpl.buildDailySummary(List<OrderTimeseriesRow>, LocalDate)` for date-bucketing, zero-fill, and summing.

This is this codebase's first GROUP BY-equivalent (date-bucketing/aggregation) pattern: JPQL projection query + Java-side grouping, rather than a native SQL `GROUP BY`. It is **not** the first JPQL constructor-projection query overall — `walmal-pos`'s `PosSaleRepository.findSyncConflicts` already used `SELECT new ...Dto(...)` projections to avoid N+1 loads. What's new here specifically is aggregating a flat projection into date buckets in application code afterward. Future features needing similar time-series/rollup endpoints should follow this precedent: project a flat row DTO in the repository, keep the actual grouping/summing logic in a separate pure method (easy to unit test without a database), and call it from a thin service method that only computes the query bounds.

`GET /api/v1/inventory/categories/stock-health` (`walmal-inventory`, `CategoryStockHealthController`) — `@PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'WAREHOUSE_MANAGER')")`. Returns `ApiResponse<List<CategoryStockHealthDto>>`: one entry per category (including categories with zero products), sorted alphabetically by category name. Each entry: `{categoryId, categoryName, productCount, okCount, lowCount, criticalCount}` — `productCount` counts distinct products (including variant-less ones); `okCount`/`lowCount`/`criticalCount` tally per-variant stock rows classified via `InventoryStock.classifyHealth()`, so they need not sum to `productCount`.

This is `walmal-inventory`'s first cross-module rollup: `CategoryStockHealthServiceImpl` calls `walmal-product`'s `ProductCatalogService.getAllCategoryProductVariantMappings()` (a flat category/product/variant projection) to get the category shape, then batch-loads matching stock rows via `InventoryStockRepository.findByVariantIdIn(...)` and tallies health counts in application code — same flat-projection-plus-Java-side-aggregation shape as the `daily-summary` endpoint above, but composing across two modules' service interfaces rather than aggregating one module's own repository rows.

## Flyway Migration Map (V1–V15)

| Version | Description |
|---------|-------------|
| V1 | common — create `audit_log` |
| V2 | auth — create user/refresh-token tables |
| V3 | product — create product/category/variant/image tables |
| V4 | inventory — create stock/reservation/location tables |
| V5 | order — create order/order-item tables |
| V6 | pos — create POS sale/session tables |
| V7 | warehouse — create fulfillment tables |
| V8 | notification — create `notification_log` |
| V9 | seed dev data |
| V10 | auth — fix dev credentials |
| V11 | auth — add missing roles |
| V12 | auth — add E2E/integration test accounts (CUSTOMER + ADMIN) |
| V13 | order — add guest email field |
| V14 | notification — guest recipients (`recipient_email` nullable, `recipient_id` nullable); `warehouse_fulfillments.user_id` nullable |
| V15 | common — create `outbox_events` table |
