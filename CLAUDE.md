# walmal — Omnichannel Retail Platform (MVP)

---

## Session Rules
- Read this file completely before any action
- At session start, state which module you are working on and confirm
  it is the correct next step in the build order
- Never modify a module that is not the current session's target
- If this file and an agent file conflict, this file (CLAUDE.md) wins
- Always start with `walmal-orchestrator` for any multi-step task
- Never invoke `module-builder` before `backend-architect` and
  `database-designer` have completed their steps for the current module

---

## Project Context
Unified omnichannel retail platform. Modular monolith (Spring Boot).
Strategy: Monolith First, Orchestration-Ready Later.

---

## Architecture Rules (NEVER violate these)
1. Each module owns its own DB tables. NO cross-module JOINs in business logic.
2. Cross-module communication: via Service interfaces ONLY, never direct Repository access.
3. Async operations: RabbitMQ only. No direct method calls for notifications/sync.
4. Every module must have: Service interface, implementation, controller, and integration test.
5. No module may import another module's Repository bean.

---

## Infrastructure Abstraction Interfaces (DIP — Required)
These interfaces MUST exist in `common/` and be used by all modules.
Business logic depends on these interfaces ONLY.
Infrastructure submodule provides the concrete implementations.

| Interface | Abstracts | Never Use Directly In Business Logic |
|---|---|---|
| `DomainEventPublisher` | RabbitMQ | `RabbitTemplate` |
| `FileStorageService` | MinIO | MinIO SDK |
| `CacheService` | Redis | `RedisTemplate` |
| `NotificationChannel` | Email / In-App | JavaMailSender, etc. |

New infrastructure (e.g. future Kafka) swaps the implementation only.
Business logic never changes.

---

## SOLID Application Rules
- **SRP**: Every service class has one primary responsibility
- **DIP**: All infrastructure dependencies (RabbitMQ, Redis, MinIO, Payment Gateway)
  accessed through interfaces. No direct framework class usage in business logic.
- **ISP**: Inter-module service interfaces are segregated by consumer —
  no module depends on methods it doesn't use. Split interfaces by consumer, not by entity.
- **OCP**: Apply to NotificationChannel, payment processing, and stock allocation strategies.
  New behaviour extends — never modifies — existing classes.
- **LSP**: No subtype throws `UnsupportedOperationException` for inherited methods.
  If a subtype cannot honour a contract, redesign the hierarchy.

---

## Cross-Module Communication Pattern

### Synchronous (in-process service calls)
```java
// ✅ CORRECT — depend on the interface in the application/ layer
@Service
public class OrderServiceImpl {
    private final InventoryReservationService inventoryService; // interface only
}

// ❌ VIOLATION — never inject another module's Repository
@Service
public class OrderServiceImpl {
    private final InventoryRepository inventoryRepo; // NEVER
}
```

### Asynchronous (RabbitMQ events)
```java
// ✅ CORRECT — publish through DomainEventPublisher interface
eventPublisher.publish(new OrderCreatedEvent(order));

// ❌ VIOLATION — never call another module's service for async operations
notificationService.sendEmail(order); // use RabbitMQ instead
```

---

## Tech Stack
- Java 21, Spring Boot 3.x, Maven
- PostgreSQL 15 (primary DB)
- Redis 7 (cache + session + distributed locks)
- RabbitMQ 3.x (message broker — all async messaging)
- Docker + Docker Compose (local dev orchestration)
- MinIO (S3-compatible file storage)

---

## Naming Conventions
- **Base package**: `com.walmal.{module}` (e.g. `com.walmal.inventory`)
- **Table naming**: `{module}_{entity}` (e.g. `product_categories`, `order_items`)
- **API base path**: `/api/v1/{module}` (e.g. `/api/v1/inventory`)
- **Flyway migrations**: `V{n}__{module}_{description}.sql`
  (e.g. `V3__inventory_create_tables.sql`)
- **ADR files**: `docs/adr/ADR-{n}-{module}-{topic}.md`
- **Test naming**: `should_{expectedBehavior}_when_{condition}`
- **RabbitMQ exchanges**: `{module}.exchange` (e.g. `order.exchange`)
- **RabbitMQ routing keys**: `{module}.{event}` (e.g. `order.created`, `inventory.stock.low`)

---

## Module Build Order (FOLLOW THIS SEQUENCE)
Build one module per Claude Code session. Do not skip or reorder.

1. Infrastructure & Common Services
2. Authentication Module
3. Product Module
4. Inventory Module
5. Order Module
6. POS Module
7. Warehouse Module
8. Notification Module
9. API Gateway layer

---

## Standard Module Package Structure
```
src/main/java/com/walmal/{module}/
├── api/              ← REST controllers and DTOs only
├── domain/           ← Entities and value objects
├── application/      ← Service interfaces (public API) and implementations
├── infrastructure/   ← Repositories, RabbitMQ listeners, Redis adapters
└── config/           ← Module-specific Spring configuration

src/test/java/com/walmal/{module}/
├── api/              ← @WebMvcTest controller tests
├── domain/           ← Domain unit tests
├── application/      ← Service unit tests (Mockito)
└── infrastructure/   ← @DataJpaTest with Testcontainers
```

---

## Definition of Done (per module — all must pass before next module starts)
- [ ] Service interface defined in `application/`
- [ ] Implementation complete
- [ ] Integration tests pass
- [ ] No cross-module repo dependencies
- [ ] OpenAPI docs generated (Springdoc annotations on all controllers)
- [ ] Docker Compose health check passes

---

## Critical Business Rules

### Inventory Reservation Lifecycle
- Reserve stock on order creation
- Confirm reservation on payment success
- Release reservation on order cancel or payment failure
- States: `AVAILABLE` → `RESERVED` → `CONFIRMED` / `RELEASED`

### Offline POS Sync Conflict Resolution
When POS sells offline and web store sells the same stock simultaneously:
1. If POS sale timestamp is earlier than web order → POS wins, web order cancelled
2. If stock exhausted and web order is earlier or timestamps are equal → warehouse buffer stock wins
3. Always notify the affected customer when their order is cancelled due to sync conflict

### Audit Log
- All destructive DB operations (DELETE, destructive UPDATE) must write to
  `audit_log` table **before** execution
- No exceptions — missing audit log write is an architecture violation

---

## Out of Scope (DO NOT BUILD)
Reject any task or proposal related to these:
- AI/ML features or AI orchestration
- Marketplace / B2B
- Loyalty / Promotions
- Advanced Analytics
- Microservices decomposition
- SMS Gateway (future only — not MVP)
- Advanced WMS

---

## Agents
Specialized sub-agents in `.claude/agents/`.

| Agent | Responsibility |
|---|---|
| `walmal-orchestrator` | Entry point for multi-step tasks. Coordinates all specialists in sequence. |
| `backend-architect` | Architecture decisions, module boundaries, ADR authoring |
| `database-designer` | PostgreSQL schema design, Flyway migrations, table ownership |
| `module-builder` | Feature module scaffolding and implementation |
| `test-validator` | Test coverage, boundary validation, Definition of Done enforcement |
| `pos-sync-specialist` | POS offline sync and conflict resolution logic only |
| `security-auditor` | JWT/RBAC review, audit log compliance, payment security |

### Agent Invocation Rule
- Always start with `walmal-orchestrator` for any multi-step task
- Sequence for building a module:
  `backend-architect` → `database-designer` → `module-builder` → `test-validator`
- Sequence for validation only:
  `test-validator` → `backend-architect` (if violations found)
- `pos-sync-specialist` is invoked by the orchestrator for any POS sync work
- `security-auditor` runs after every module that touches auth, payments, or user data

---

## Commands
Custom slash commands in `.claude/commands/`:

| Command | Purpose |
|---|---|
| `/build-module {module_name}` | Scaffold a new bounded context module end-to-end |
| `/validate-boundaries` | Check module boundary integrity, SOLID compliance, and DoD status |
