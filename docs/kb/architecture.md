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
