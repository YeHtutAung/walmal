# conventions.md — walmal Coding Conventions

## Architecture Rules

The full set of architecture rules (module boundaries, SOLID requirements, cross-module communication patterns, Definition of Done) lives in `CLAUDE.md`. Do not duplicate them here — read `CLAUDE.md` first.

## Error Responses

All modules return RFC 9457 `ProblemDetail` (Spring 6 built-in). The `detail` field carries the human-readable message. See `docs/kb/SYSTEM.md` for the cross-repo error-body contract.

Per-module exception handlers (all are `@RestControllerAdvice` classes returning `ProblemDetail`):
- `AuthExceptionHandler` — `walmal-auth`
- `InventoryExceptionHandler` — `walmal-inventory`
- `OrderExceptionHandler` — `walmal-order`
- `PosExceptionHandler` — `walmal-pos`
- `ProductExceptionHandler` — `walmal-product`
- `WarehouseExceptionHandler` — `walmal-warehouse`
- `NotificationExceptionHandler` — `walmal-notification`
- `GlobalExceptionHandler` — `walmal-app` (catch-all, 500s)

## Naming Conventions

See `CLAUDE.md` "Naming Conventions" — packages, tables, API paths, Flyway files, RabbitMQ exchanges/routing keys, test method names.

## Module Package Structure

See `CLAUDE.md` "Standard Module Package Structure" — the `api/ domain/ application/ infrastructure/ config/` layout for main and test trees.

## ADR Index

Architecture Decision Records live in `docs/adr/`:
- `ADR-2-auth-module.md`
- `ADR-3-product-module.md`
- `ADR-4-inventory-module.md`
- `ADR-5-order-module-architecture.md`
- `ADR-6-pos-module-architecture.md`
- `ADR-9-api-gateway-layer.md`

## Infrastructure Abstraction Interfaces

See `CLAUDE.md` "Infrastructure Abstraction Interfaces (DIP — Required)" — the interface-to-implementation table (`DomainEventPublisher`, `FileStorageService`, `CacheService`, `NotificationChannel`). Interfaces live in `walmal-common`; implementations in `walmal-infrastructure`.
