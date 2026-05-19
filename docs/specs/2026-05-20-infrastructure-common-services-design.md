# Infrastructure & Common Services — Design Spec

**Module:** Step 1 of 9 in walmal build order
**Date:** 2026-05-20
**Status:** Approved

---

## Overview

Foundation layer for the walmal omnichannel retail platform. Two Maven modules that enforce DIP at compile time: `walmal-common` (interfaces only) and `walmal-infrastructure` (concrete implementations). Plus `walmal-app` as the Spring Boot assembly.

---

## Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Maven structure | Multi-module | Compile-time boundary enforcement for architecture rules |
| Common vs Infrastructure split | Two separate modules | Business modules cannot see concrete classes (RabbitTemplate, RedisTemplate, etc.) |
| Spring Boot version | 3.4.x | Latest stable, best Java 21 alignment, greenfield MVP |
| Migration tool | Flyway | Convention from CLAUDE.md |
| Primary DB | PostgreSQL 15 | Per tech stack |
| Cache/Locks | Redis 7 | Per tech stack |
| Messaging | RabbitMQ 3.x | Per tech stack, topic exchanges |
| File storage | MinIO | S3-compatible, per tech stack |

---

## Maven Module Structure

```
walmal/
├── pom.xml                          ← Parent POM (dependency management, plugins)
├── walmal-common/
│   └── pom.xml                      ← Interfaces, base entities, shared types
├── walmal-infrastructure/
│   └── pom.xml                      ← Concrete implementations (depends on walmal-common)
├── walmal-app/
│   └── pom.xml                      ← Spring Boot assembly (depends on all modules)
├── docker-compose.yml
└── docs/
```

Future business modules added as siblings: `walmal-auth/`, `walmal-product/`, etc.

---

## walmal-common Package Structure

```
com.walmal.common/
├── event/
│   ├── DomainEvent.java              ← Base class (eventId, timestamp, eventType)
│   └── DomainEventPublisher.java     ← Interface: publish(DomainEvent)
├── storage/
│   ├── FileStorageService.java       ← Interface: upload, download, delete, getUrl
│   └── StoredFile.java               ← Value object (key, bucket, contentType, size)
├── cache/
│   ├── CacheService.java             ← Interface: get, put, evict, putWithTTL
│   └── DistributedLockService.java   ← Interface: tryLock, unlock, executeWithLock
├── notification/
│   ├── NotificationChannel.java      ← Interface: send(Notification)
│   └── Notification.java             ← Value object (recipient, subject, body, type)
├── audit/
│   ├── AuditAction.java              ← Enum: DELETE, UPDATE, STATUS_CHANGE
│   ├── AuditLog.java                 ← JPA entity (id, tableName, recordId, action, oldValue, newValue, performedBy, timestamp)
│   ├── AuditEntry.java               ← Value object for audit log requests
│   └── AuditService.java             ← Interface: log(AuditEntry)
├── model/
│   ├── BaseEntity.java               ← Mapped superclass (UUID id, createdAt, updatedAt)
│   └── ApiResponse.java              ← Generic wrapper: success, message, data, errors
└── exception/
    ├── WalmalException.java          ← Base runtime exception
    ├── ResourceNotFoundException.java
    ├── BusinessRuleException.java
    └── ConcurrencyConflictException.java
```

No config package. No repository. No implementation. Pure interfaces, entities, and value objects.

---

## walmal-infrastructure Package Structure

```
com.walmal.infrastructure/
├── messaging/
│   └── RabbitDomainEventPublisher.java    ← implements DomainEventPublisher
├── storage/
│   └── MinioFileStorageService.java       ← implements FileStorageService
├── cache/
│   ├── RedisCacheService.java             ← implements CacheService
│   └── RedisDistributedLockService.java   ← implements DistributedLockService
├── notification/
│   ├── EmailNotificationChannel.java      ← implements NotificationChannel
│   └── InAppNotificationChannel.java      ← implements NotificationChannel
├── audit/
│   ├── AuditLogRepository.java            ← Spring Data JPA repository
│   └── AuditServiceImpl.java             ← implements AuditService
└── config/
    ├── InfrastructureAutoConfiguration.java  ← Registers all concrete beans
    └── RabbitMQTopologyConfig.java           ← Declares topic exchanges ({module}.exchange)
```

### RabbitMQ Topology

- `RabbitMQTopologyConfig` declares topic exchanges only (e.g., `order.exchange`, `inventory.exchange`)
- Queues are declared by consuming modules, not here
- Exchange naming convention: `{module}.exchange`
- Routing key convention: `{module}.{event}` (e.g., `order.created`)

---

## walmal-app

```
com.walmal/
└── WalmalApplication.java            ← @SpringBootApplication
```

- `application.yml` with profiles (default, dev, test)
- JPA entity scanning configured to include `com.walmal` packages so AuditLog entity in walmal-common is detected
- `@EntityScan("com.walmal")` on the application class

---

## Docker Compose Services

| Service | Image | Port | Purpose |
|---|---|---|---|
| postgres | postgres:15 | 5432 | Primary database |
| redis | redis:7 | 6379 | Cache + session + distributed locks |
| rabbitmq | rabbitmq:3-management | 5672 / 15672 | Async messaging + management UI |
| minio | minio/minio | 9000 / 9001 | S3-compatible file storage + console |

All services include health checks. The Spring Boot app is NOT in Docker Compose (runs locally via IDE/Maven).

---

## Flyway Migration

`V1__common_create_audit_log.sql`:
- `audit_log` table with: id (UUID PK), table_name, record_id, action, old_value (JSONB), new_value (JSONB), performed_by, created_at
- `created_at` indexed for query performance

---

## Infrastructure Abstraction Interfaces

| Interface | Location | Methods | Abstracts |
|---|---|---|---|
| `DomainEventPublisher` | common/event/ | `publish(DomainEvent)` | RabbitMQ |
| `FileStorageService` | common/storage/ | `upload`, `download`, `delete`, `getUrl` | MinIO |
| `CacheService` | common/cache/ | `get`, `put`, `evict`, `putWithTTL` | Redis |
| `DistributedLockService` | common/cache/ | `tryLock`, `unlock`, `executeWithLock` | Redis |
| `NotificationChannel` | common/notification/ | `send(Notification)` | Email / In-App |
| `AuditService` | common/audit/ | `log(AuditEntry)` | PostgreSQL via AuditLogRepository |

Business modules depend on these interfaces ONLY. Concrete implementations are in walmal-infrastructure and injected at runtime.

---

## Out of Scope for This Step

- No business module code
- No authentication/authorization
- No REST controllers (except health check via Spring Actuator)
- No business-specific RabbitMQ queues (only topology/exchanges)
