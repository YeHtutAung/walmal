---
name: build-module
description: Scaffold a new walmal bounded context module with full structure, Flyway migration, Docker health check, OpenAPI config, and Definition of Done tracking
arguments:
  - name: module_name
    description: Name of the module to create (e.g., "inventory", "order", "pos")
    required: true
---

# Build Module Command

Scaffold a new module named `$module_name` for the **walmal** omnichannel retail platform.

Before proceeding, read `CLAUDE.md` and verify this module aligns with the build order.

## Pre-checks
1. Verify `$module_name` is in the module build order:
   Infrastructure → Auth → Product → Inventory → Order → POS → Warehouse → Notification → API Gateway
2. Verify all prerequisite modules (earlier in the sequence) already exist
3. Reject if `$module_name` is out of scope (AI/ML, Marketplace/B2B, Loyalty/Promotions)

## Steps

### 1. Create main source structure
```
src/main/java/com/walmal/$module_name/
├── api/                  ← REST controllers and DTOs
│   └── ${Module}Controller.java
│   └── dto/
├── domain/               ← Entities and value objects
├── application/          ← Service interface + implementation
│   └── ${Module}Service.java          (interface)
│   └── ${Module}ServiceImpl.java      (implementation)
├── infrastructure/       ← Repositories, RabbitMQ listeners, Redis adapters
│   └── ${Module}Repository.java
│   └── messaging/        ← RabbitMQ publishers/listeners (if async needed)
└── config/               ← Module-specific Spring configuration
    └── ${Module}Config.java
```

### 2. Create test structure
```
src/test/java/com/walmal/$module_name/
├── api/                  ← @WebMvcTest controller tests
├── domain/               ← Domain unit tests
├── application/          ← Service unit tests (Mockito)
└── infrastructure/       ← @DataJpaTest with Testcontainers (PostgreSQL 15)
```

### 3. Create Flyway migration
```
src/main/resources/db/migration/
└── V{next}__{create_$module_name_tables}.sql
```
- Tables must be prefixed with module name (e.g., `product_categories`, `order_items`)
- Include `id` (UUID), `created_at`, `updated_at` columns on all tables
- Include `audit_log` writes for any destructive operations

### 4. Create OpenAPI configuration
- Add Springdoc annotations on the controller
- Group endpoints under `/api/v1/$module_name`

### 5. Add Docker Compose health check
- Add service entry to `docker-compose.yml` if module requires dedicated infra
- Ensure health check endpoint exists at `/actuator/health`

### 6. Create ADR
- Write `docs/adr/ADR-{next}-$module_name-module.md` documenting:
  - Module purpose and bounded context
  - Owned tables
  - Inter-module dependencies (via Service interfaces only)
  - Async events published/consumed (RabbitMQ)

## Architecture Rules to Enforce
1. Service interface in `application/` is the module's public API — nothing else is exposed
2. Repository stays in `infrastructure/` — never imported by other modules
3. Cross-module communication via Service interfaces (sync) or RabbitMQ (async) only
4. No cross-module JOINs
5. All destructive DB operations write to `audit_log` first

## SOLID Checks
- SRP: Each service class has one primary responsibility
- DIP: Infrastructure dependencies (RabbitMQ, Redis, MinIO) accessed through interfaces
- ISP: Service interface exposes only what consumers need

## Definition of Done (verify before marking complete)
- [ ] Service interface defined in `application/`
- [ ] Implementation complete
- [ ] Integration tests pass
- [ ] No cross-module repo dependencies
- [ ] OpenAPI docs generated
- [ ] Docker Compose health check passes
