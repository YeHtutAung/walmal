---
name: validate-boundaries
description: Check module boundary integrity, architecture rule compliance, SOLID violations, and Definition of Done status across the walmal platform
---

# Validate Boundaries Command

Analyze the **walmal** codebase for architecture rule violations, boundary breaches, and Definition of Done gaps.

Read `CLAUDE.md` before running checks.

## Architecture Rule Checks

### Rule 1: Module Table Ownership
- Scan all SQL, JPQL, and native queries
- Flag any JOIN between tables owned by different modules
- Flag any Repository method that references another module's table
- **Output:** ❌ Violation / ✅ Pass per module pair

### Rule 2: Service Interface Communication
- Scan all import statements across modules
- Flag any module importing another module's `infrastructure/` package
- Flag any direct Repository injection from another module
- Verify cross-module calls go through `application/` Service interfaces only
- **Output:** ❌ Violation / ✅ Pass per dependency

### Rule 3: Async via RabbitMQ Only
- Scan for direct method calls that should be async (notifications, sync operations)
- Verify RabbitMQ is used for all cross-module async communication
- Flag any `@Async` or `CompletableFuture` used for inter-module messaging
- **Output:** ❌ Violation / ⚠️ Warning / ✅ Pass

### Rule 4: Module Completeness
- Verify every module has: Service interface, implementation, controller, integration test
- **Output:** ❌ Missing / ✅ Present per component per module

### Rule 5: Repository Isolation
- Scan Spring bean wiring (`@Autowired`, constructor injection)
- Flag any Repository bean injected outside its owning module
- **Output:** ❌ Violation / ✅ Pass per module

## SOLID Violation Checks

### SRP — Single Responsibility
- Flag service classes with more than one primary responsibility
- Check for God classes with excessive method counts

### DIP — Dependency Inversion
- Flag direct framework class usage in business logic (e.g., `RabbitTemplate` in service layer)
- Verify infrastructure dependencies accessed through interfaces

### ISP — Interface Segregation
- Flag Service interfaces that force consumers to depend on methods they don't use
- Check for bloated interfaces that should be split by consumer

### OCP — Open/Closed
- Check notification channels, payment processing, stock allocation for extension points
- Flag switch/if-else chains that should use polymorphism

### LSP — Liskov Substitution
- Flag any subtype that throws `UnsupportedOperationException` for inherited methods

## Business Rule Validation

### Audit Log Compliance
- Scan all DELETE and destructive UPDATE operations
- Flag any that don't write to `audit_log` table first
- **Output:** ❌ Violation / ✅ Pass per operation

### Inventory Reservation Lifecycle
- Verify reservation states exist: `AVAILABLE` → `RESERVED` → `CONFIRMED` / `RELEASED`
- Check order creation triggers reservation, payment confirms, cancel releases
- **Output:** ⚠️ Warning if incomplete / ✅ Pass

### POS Sync Conflict Resolution
- Verify warehouse buffer stock wins over web stock on conflict
- **Output:** ⚠️ Warning if not implemented / ✅ Pass

## Definition of Done (per module)
For each existing module, check:
- [ ] Service interface defined
- [ ] Implementation complete
- [ ] Integration tests pass
- [ ] No cross-module repo dependencies
- [ ] OpenAPI docs generated
- [ ] Docker Compose health check passes

## Structural Checks
- **Package structure** — Every module follows: `api/`, `domain/`, `application/`, `infrastructure/`, `config/`
- **Circular dependencies** — No circular dependency chains between modules
- **Domain leakage** — Domain entities not exposed in API DTOs
- **Build order** — No module depends on a module later in the build sequence

## Output Format
```
=== walmal Boundary Validation Report ===

Module: [module_name]
  Architecture Rules:
    Rule 1 (Table Ownership):     ✅ Pass / ❌ Violation: [details]
    Rule 2 (Service Interfaces):  ✅ Pass / ❌ Violation: [details]
    Rule 3 (Async RabbitMQ):      ✅ Pass / ❌ Violation: [details]
    Rule 4 (Completeness):        ✅ Pass / ❌ Missing: [details]
    Rule 5 (Repo Isolation):      ✅ Pass / ❌ Violation: [details]
  SOLID:
    SRP: ✅ / ⚠️ [details]
    DIP: ✅ / ⚠️ [details]
    ISP: ✅ / ⚠️ [details]
    OCP: ✅ / ⚠️ [details]
    LSP: ✅ / ⚠️ [details]
  Business Rules:
    Audit Log:                    ✅ Pass / ❌ Violation: [details]
  Definition of Done:             [X/6] complete

Summary: X violations, Y warnings across Z modules
```
