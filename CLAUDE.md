# walmal — Omnichannel Retail Platform (MVP)

## Project Context
Unified omnichannel retail platform. Modular monolith (Spring Boot).
Strategy: Monolith First, Orchestration-Ready Later.

## Architecture Rules (NEVER violate these)
1. Each module owns its own DB tables. NO cross-module JOINs in business logic.
2. Cross-module communication: via Service interfaces ONLY, never direct Repository access.
3. Async operations: RabbitMQ only. No direct method calls for notifications/sync.
4. Every module must have: Service interface, implementation, controller, and integration test.
5. No module may import another module's Repository bean.

## SOLID Application Rules
- SRP: Every service class has one primary responsibility
- DIP: All infrastructure dependencies (RabbitMQ, Redis, S3, Payment Gateway) 
  accessed through interfaces. No direct framework class usage in business logic.
- ISP: Inter-module service interfaces are segregated by consumer — 
  no module depends on methods it doesn't use
- OCP: Apply to Notification channels, payment processing, stock allocation
- LSP: No subtype throws UnsupportedOperationException for inherited methods

## Tech Stack
- Java 21, Spring Boot 3.x, Maven
- PostgreSQL 15 (primary DB)
- Redis 7 (cache + session + locks)
- RabbitMQ 3.x (message broker)
- Docker + Docker Compose (local dev)
- MinIO (S3-compatible file storage)

## Module Build Order (FOLLOW THIS SEQUENCE)
1. Infrastructure & Common Services
2. Authentication Module
3. Product Module
4. Inventory Module
5. Order Module
6. POS Module
7. Warehouse Module
8. Notification Module
9. API Gateway layer

## Definition of Done (per module)
- [ ] Service interface defined
- [ ] Implementation complete
- [ ] Integration tests pass
- [ ] No cross-module repo dependencies
- [ ] OpenAPI docs generated
- [ ] Docker Compose health check passes

## Critical Business Rules
- Offline POS sync conflict resolution: warehouse buffer stock wins over web stock
- Inventory reservation on order creation, confirmed on payment, released on cancel
- All destructive DB operations must write to audit_log table first

## Out of Scope (DO NOT BUILD)
- AI/ML features
- Marketplace/B2B
- Loyalty/Promotions
- Microservices decomposition

## Agents
Specialized sub-agents in `.claude/agents/`:
- **backend-architect** — Architecture decisions and module boundaries
- **database-designer** — Schema design, migrations, and data modeling
- **module-builder** — Feature module scaffolding and implementation
- **test-validator** — Test coverage, validation, and quality gates

## Commands
Custom slash commands in `.claude/commands/`:
- `/build-module` — Scaffold a new bounded context module
- `/validate-boundaries` — Check module boundary integrity

## Conventions
- Follow existing code patterns
- Write tests for all new features
- Document architecture decisions in `docs/adr/`
- Package structure per module: `api/`, `domain/`, `application/`, `infrastructure/`, `config/`
