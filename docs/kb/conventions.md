# conventions.md — walmal Coding Conventions

## Architecture Rules

The full set of architecture rules (module boundaries, SOLID requirements, cross-module communication patterns, Definition of Done) lives in `CLAUDE.md`. Do not duplicate them here — read `CLAUDE.md` first.

## Error Responses

All modules return RFC 9457 `ProblemDetail` (Spring 6 built-in). The `detail` field carries the human-readable message. See `docs/kb/SYSTEM.md` for the cross-repo error-body contract.

Per-module exception handlers (all extend `ResponseEntityExceptionHandler` indirectly via `@RestControllerAdvice`):
- `AuthExceptionHandler` — `walmal-auth`
- `InventoryExceptionHandler` — `walmal-inventory`
- `OrderExceptionHandler` — `walmal-order`
- `PosExceptionHandler` — `walmal-pos`
- `ProductExceptionHandler` — `walmal-product`
- `WarehouseExceptionHandler` — `walmal-warehouse`
- `NotificationExceptionHandler` — `walmal-notification`
- `GlobalExceptionHandler` — `walmal-app` (catch-all, 500s)

## Naming Conventions

- Base package: `com.walmal.{module}` (e.g. `com.walmal.inventory`)
- DB tables: `{module}_{entity}` (e.g. `order_items`, `product_categories`)
- API base path: `/api/v1/{module}` (e.g. `/api/v1/inventory`)
- Flyway migrations: `V{n}__{module}_{description}.sql`
- RabbitMQ exchanges: `{module}.exchange`
- RabbitMQ routing keys: `{module}.{event}` (e.g. `order.created`)
- Test method names: `should_{expectedBehavior}_when_{condition}`

## Module Package Structure

Each module follows:
```
src/main/java/com/walmal/{module}/
  api/           REST controllers + DTOs only
  domain/        Entities + value objects
  application/   Service interfaces (public API) + implementations
  infrastructure/ Repositories, RabbitMQ listeners, Redis adapters
  config/        Module-specific Spring configuration
```

## ADR Index

Architecture Decision Records live in `docs/adr/`:
- `ADR-2-auth-module.md`
- `ADR-3-product-module.md`
- `ADR-4-inventory-module.md`
- `ADR-5-order-module-architecture.md`
- `ADR-6-pos-module-architecture.md`
- `ADR-9-api-gateway-layer.md`

## Infrastructure Abstraction Interfaces

Business logic uses only the interfaces from `walmal-common`; never Spring/framework classes directly:

| Interface | Abstracts |
|-----------|-----------|
| `DomainEventPublisher` | RabbitMQ / `RabbitTemplate` |
| `FileStorageService` | MinIO SDK |
| `CacheService` | `RedisTemplate` |
| `NotificationChannel` | `JavaMailSender` / in-app |
