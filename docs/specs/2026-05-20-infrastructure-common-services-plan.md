# Infrastructure & Common Services — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the foundation layer (walmal-common + walmal-infrastructure + walmal-app) with all abstraction interfaces, concrete implementations, Docker Compose, and Flyway migration.

**Architecture:** Multi-module Maven project. `walmal-common` holds interfaces and shared types only — no implementations. `walmal-infrastructure` provides concrete implementations (RabbitMQ, Redis, MinIO, email). `walmal-app` is the Spring Boot assembly with JPA entity scanning across all `com.walmal` packages.

**Tech Stack:** Java 21, Spring Boot 3.4.x, Maven, PostgreSQL 15, Redis 7, RabbitMQ 3.x, MinIO, Flyway, Docker Compose, JUnit 5, Testcontainers

---

## File Structure

### walmal-common
```
walmal-common/
├── pom.xml
└── src/main/java/com/walmal/common/
    ├── model/
    │   ├── BaseEntity.java
    │   └── ApiResponse.java
    ├── exception/
    │   ├── WalmalException.java
    │   ├── ResourceNotFoundException.java
    │   ├── BusinessRuleException.java
    │   └── ConcurrencyConflictException.java
    ├── event/
    │   ├── DomainEvent.java
    │   └── DomainEventPublisher.java
    ├── cache/
    │   ├── CacheService.java
    │   └── DistributedLockService.java
    ├── storage/
    │   ├── FileStorageService.java
    │   └── StoredFile.java
    ├── notification/
    │   ├── NotificationChannel.java
    │   └── Notification.java
    └── audit/
        ├── AuditAction.java
        ├── AuditLog.java
        ├── AuditEntry.java
        └── AuditService.java
```

### walmal-infrastructure
```
walmal-infrastructure/
├── pom.xml
└── src/
    ├── main/java/com/walmal/infrastructure/
    │   ├── messaging/
    │   │   └── RabbitDomainEventPublisher.java
    │   ├── cache/
    │   │   ├── RedisCacheService.java
    │   │   └── RedisDistributedLockService.java
    │   ├── storage/
    │   │   └── MinioFileStorageService.java
    │   ├── notification/
    │   │   ├── EmailNotificationChannel.java
    │   │   └── InAppNotificationChannel.java
    │   ├── audit/
    │   │   ├── AuditLogRepository.java
    │   │   └── AuditServiceImpl.java
    │   └── config/
    │       ├── InfrastructureAutoConfiguration.java
    │       └── RabbitMQTopologyConfig.java
    └── test/java/com/walmal/infrastructure/
        ├── audit/
        │   └── AuditServiceImplTest.java
        ├── cache/
        │   ├── RedisCacheServiceTest.java
        │   └── RedisDistributedLockServiceTest.java
        ├── messaging/
        │   └── RabbitDomainEventPublisherTest.java
        ├── storage/
        │   └── MinioFileStorageServiceTest.java
        └── notification/
            └── EmailNotificationChannelTest.java
```

### walmal-app
```
walmal-app/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/walmal/WalmalApplication.java
    │   └── resources/
    │       └── application.yml
    └── test/java/com/walmal/
        └── WalmalApplicationTest.java
```

### Root
```
walmal/
├── pom.xml                    ← Parent POM
├── .gitignore
├── docker-compose.yml
└── walmal-app/src/main/resources/
    └── db/migration/
        └── V1__common_create_audit_log.sql
```

---

## Task 1: Parent POM and Module Scaffolding

**Files:**
- Create: `.gitignore`
- Create: `pom.xml` (parent)
- Create: `walmal-common/pom.xml`
- Create: `walmal-infrastructure/pom.xml`
- Create: `walmal-app/pom.xml`

- [ ] **Step 0: Create .gitignore**

```gitignore
# Maven
target/

# IDE
.idea/
*.iml
.vscode/
*.swp
.project
.classpath
.settings/

# OS
.DS_Store
Thumbs.db

# Env
.env
*.log
```

- [ ] **Step 1: Create parent POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.5</version>
        <relativePath/>
    </parent>

    <groupId>com.walmal</groupId>
    <artifactId>walmal</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>walmal</name>
    <description>Omnichannel Retail Platform</description>

    <modules>
        <module>walmal-common</module>
        <module>walmal-infrastructure</module>
        <module>walmal-app</module>
    </modules>

    <properties>
        <java.version>21</java.version>
        <minio.version>8.5.13</minio.version>
        <testcontainers.version>1.20.4</testcontainers.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- Internal modules -->
            <dependency>
                <groupId>com.walmal</groupId>
                <artifactId>walmal-common</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.walmal</groupId>
                <artifactId>walmal-infrastructure</artifactId>
                <version>${project.version}</version>
            </dependency>
            <!-- MinIO -->
            <dependency>
                <groupId>io.minio</groupId>
                <artifactId>minio</artifactId>
                <version>${minio.version}</version>
            </dependency>
            <!-- Testcontainers BOM -->
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers-bom</artifactId>
                <version>${testcontainers.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

- [ ] **Step 2: Create walmal-common POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.walmal</groupId>
        <artifactId>walmal</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>walmal-common</artifactId>
    <name>walmal-common</name>
    <description>Shared interfaces, base entities, and value objects</description>

    <dependencies>
        <!-- JPA for BaseEntity and AuditLog entity -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <!-- Jackson for DomainEvent serialization -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <!-- Validation -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: Create walmal-infrastructure POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.walmal</groupId>
        <artifactId>walmal</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>walmal-infrastructure</artifactId>
    <name>walmal-infrastructure</name>
    <description>Concrete implementations of common interfaces</description>

    <dependencies>
        <dependency>
            <groupId>com.walmal</groupId>
            <artifactId>walmal-common</artifactId>
        </dependency>
        <!-- RabbitMQ -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
        <!-- Redis -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <!-- MinIO -->
        <dependency>
            <groupId>io.minio</groupId>
            <artifactId>minio</artifactId>
        </dependency>
        <!-- Mail -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-mail</artifactId>
        </dependency>
        <!-- JPA for AuditLogRepository -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.amqp</groupId>
            <artifactId>spring-rabbit-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 4: Create walmal-app POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.walmal</groupId>
        <artifactId>walmal</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>walmal-app</artifactId>
    <name>walmal-app</name>
    <description>Spring Boot application assembly</description>

    <dependencies>
        <dependency>
            <groupId>com.walmal</groupId>
            <artifactId>walmal-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.walmal</groupId>
            <artifactId>walmal-infrastructure</artifactId>
        </dependency>
        <!-- Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <!-- Actuator -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <!-- OpenAPI -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>2.7.0</version>
        </dependency>
        <!-- PostgreSQL -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <!-- Flyway -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 5: Verify Maven builds**

Run: `mvn clean validate -f pom.xml`
Expected: BUILD SUCCESS (modules recognized, no compilation yet)

- [ ] **Step 6: Commit**

```bash
git add .gitignore pom.xml walmal-common/pom.xml walmal-infrastructure/pom.xml walmal-app/pom.xml
git commit -m "feat: add .gitignore, parent POM, and Maven module scaffolding"
```

---

## Task 2: walmal-common — Base Model and Exceptions

**Files:**
- Create: `walmal-common/src/main/java/com/walmal/common/model/BaseEntity.java`
- Create: `walmal-common/src/main/java/com/walmal/common/model/ApiResponse.java`
- Create: `walmal-common/src/main/java/com/walmal/common/exception/WalmalException.java`
- Create: `walmal-common/src/main/java/com/walmal/common/exception/ResourceNotFoundException.java`
- Create: `walmal-common/src/main/java/com/walmal/common/exception/BusinessRuleException.java`
- Create: `walmal-common/src/main/java/com/walmal/common/exception/ConcurrencyConflictException.java`

- [ ] **Step 1: Create BaseEntity**

```java
package com.walmal.common.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    protected void setId(UUID id) { this.id = id; }
}
```

- [ ] **Step 2: Create ApiResponse**

```java
package com.walmal.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    boolean success,
    String message,
    T data,
    List<String> errors
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, null, data, null);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message, null, null);
    }

    public static <T> ApiResponse<T> error(String message, List<String> errors) {
        return new ApiResponse<>(false, message, null, errors);
    }
}
```

- [ ] **Step 3: Create exception hierarchy**

`WalmalException.java`:
```java
package com.walmal.common.exception;

public class WalmalException extends RuntimeException {
    public WalmalException(String message) { super(message); }
    public WalmalException(String message, Throwable cause) { super(message, cause); }
}
```

`ResourceNotFoundException.java`:
```java
package com.walmal.common.exception;

public class ResourceNotFoundException extends WalmalException {
    public ResourceNotFoundException(String resource, Object id) {
        super(String.format("%s not found with id: %s", resource, id));
    }
}
```

`BusinessRuleException.java`:
```java
package com.walmal.common.exception;

public class BusinessRuleException extends WalmalException {
    public BusinessRuleException(String message) { super(message); }
}
```

`ConcurrencyConflictException.java`:
```java
package com.walmal.common.exception;

public class ConcurrencyConflictException extends WalmalException {
    public ConcurrencyConflictException(String message) { super(message); }
}
```

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -pl walmal-common`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add walmal-common/src/
git commit -m "feat(common): add BaseEntity, ApiResponse, and exception hierarchy"
```

---

## Task 3: walmal-common — DomainEvent and DomainEventPublisher

**Files:**
- Create: `walmal-common/src/main/java/com/walmal/common/event/DomainEvent.java`
- Create: `walmal-common/src/main/java/com/walmal/common/event/DomainEventPublisher.java`

- [ ] **Step 1: Create DomainEvent base class**

```java
package com.walmal.common.event;

import java.time.Instant;
import java.util.UUID;

public abstract class DomainEvent {

    private final UUID eventId;
    private final Instant timestamp;
    private final String eventType;

    protected DomainEvent(String eventType) {
        this.eventId = UUID.randomUUID();
        this.timestamp = Instant.now();
        this.eventType = eventType;
    }

    public UUID getEventId() { return eventId; }
    public Instant getTimestamp() { return timestamp; }
    public String getEventType() { return eventType; }
}
```

- [ ] **Step 2: Create DomainEventPublisher interface**

```java
package com.walmal.common.event;

public interface DomainEventPublisher {
    void publish(DomainEvent event);
    void publish(DomainEvent event, String routingKey);
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl walmal-common`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add walmal-common/src/main/java/com/walmal/common/event/
git commit -m "feat(common): add DomainEvent base class and DomainEventPublisher interface"
```

---

## Task 4: walmal-common — CacheService and DistributedLockService

**Files:**
- Create: `walmal-common/src/main/java/com/walmal/common/cache/CacheService.java`
- Create: `walmal-common/src/main/java/com/walmal/common/cache/DistributedLockService.java`

- [ ] **Step 1: Create CacheService interface**

```java
package com.walmal.common.cache;

import java.time.Duration;
import java.util.Optional;

public interface CacheService {
    <T> Optional<T> get(String key, Class<T> type);
    <T> void put(String key, T value);
    <T> void put(String key, T value, Duration ttl);
    void evict(String key);
    void evictByPrefix(String prefix);
}
```

- [ ] **Step 2: Create DistributedLockService interface**

```java
package com.walmal.common.cache;

import java.time.Duration;
import java.util.function.Supplier;

public interface DistributedLockService {
    boolean tryLock(String key, Duration timeout);
    void unlock(String key);
    <T> T executeWithLock(String key, Duration timeout, Supplier<T> action);
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl walmal-common`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add walmal-common/src/main/java/com/walmal/common/cache/
git commit -m "feat(common): add CacheService and DistributedLockService interfaces"
```

---

## Task 5: walmal-common — FileStorageService

**Files:**
- Create: `walmal-common/src/main/java/com/walmal/common/storage/FileStorageService.java`
- Create: `walmal-common/src/main/java/com/walmal/common/storage/StoredFile.java`

- [ ] **Step 1: Create StoredFile value object**

```java
package com.walmal.common.storage;

public record StoredFile(
    String key,
    String bucket,
    String contentType,
    long size
) {}
```

- [ ] **Step 2: Create FileStorageService interface**

```java
package com.walmal.common.storage;

import java.io.InputStream;

public interface FileStorageService {
    StoredFile upload(String bucket, String key, InputStream content, String contentType, long size);
    InputStream download(String bucket, String key);
    void delete(String bucket, String key);
    String getPresignedUrl(String bucket, String key);
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl walmal-common`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add walmal-common/src/main/java/com/walmal/common/storage/
git commit -m "feat(common): add FileStorageService interface and StoredFile value object"
```

---

## Task 6: walmal-common — NotificationChannel

**Files:**
- Create: `walmal-common/src/main/java/com/walmal/common/notification/Notification.java`
- Create: `walmal-common/src/main/java/com/walmal/common/notification/NotificationChannel.java`

- [ ] **Step 1: Create Notification value object**

```java
package com.walmal.common.notification;

public record Notification(
    String recipient,
    String subject,
    String body,
    NotificationType type
) {
    public enum NotificationType {
        EMAIL, IN_APP
    }
}
```

- [ ] **Step 2: Create NotificationChannel interface**

```java
package com.walmal.common.notification;

public interface NotificationChannel {
    void send(Notification notification);
    boolean supports(Notification.NotificationType type);
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl walmal-common`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add walmal-common/src/main/java/com/walmal/common/notification/
git commit -m "feat(common): add NotificationChannel interface and Notification value object"
```

---

## Task 7: walmal-common — Audit Types

**Files:**
- Create: `walmal-common/src/main/java/com/walmal/common/audit/AuditAction.java`
- Create: `walmal-common/src/main/java/com/walmal/common/audit/AuditLog.java`
- Create: `walmal-common/src/main/java/com/walmal/common/audit/AuditEntry.java`
- Create: `walmal-common/src/main/java/com/walmal/common/audit/AuditService.java`

- [ ] **Step 1: Create AuditAction enum**

```java
package com.walmal.common.audit;

public enum AuditAction {
    DELETE,
    UPDATE,
    STATUS_CHANGE
}
```

- [ ] **Step 2: Create AuditLog JPA entity**

```java
package com.walmal.common.audit;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log", indexes = {
    @Index(name = "idx_audit_log_created_at", columnList = "created_at"),
    @Index(name = "idx_audit_log_table_record", columnList = "table_name, record_id")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "record_id", nullable = false)
    private UUID recordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private AuditAction action;

    @Column(name = "old_value", columnDefinition = "jsonb")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "jsonb")
    private String newValue;

    @Column(name = "performed_by", nullable = false)
    private String performedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {}

    public AuditLog(String tableName, UUID recordId, AuditAction action,
                    String oldValue, String newValue, String performedBy) {
        this.tableName = tableName;
        this.recordId = recordId;
        this.action = action;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.performedBy = performedBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getTableName() { return tableName; }
    public UUID getRecordId() { return recordId; }
    public AuditAction getAction() { return action; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public String getPerformedBy() { return performedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 3: Create AuditEntry value object**

```java
package com.walmal.common.audit;

import java.util.UUID;

public record AuditEntry(
    String tableName,
    UUID recordId,
    AuditAction action,
    String oldValue,
    String newValue,
    String performedBy
) {}
```

- [ ] **Step 4: Create AuditService interface**

```java
package com.walmal.common.audit;

public interface AuditService {
    void log(AuditEntry entry);
}
```

- [ ] **Step 5: Verify compilation**

Run: `mvn compile -pl walmal-common`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add walmal-common/src/main/java/com/walmal/common/audit/
git commit -m "feat(common): add AuditLog entity, AuditEntry, AuditAction, and AuditService interface"
```

---

## Task 8: walmal-infrastructure — AuditServiceImpl

**Files:**
- Create: `walmal-infrastructure/src/main/java/com/walmal/infrastructure/audit/AuditLogRepository.java`
- Create: `walmal-infrastructure/src/main/java/com/walmal/infrastructure/audit/AuditServiceImpl.java`
- Create: `walmal-infrastructure/src/test/java/com/walmal/infrastructure/audit/AuditServiceImplTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.walmal.infrastructure.audit;

import com.walmal.common.audit.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditServiceImplTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private AuditServiceImpl auditService;

    @Test
    void should_persistAuditLog_when_logIsCalled() {
        AuditEntry entry = new AuditEntry(
            "order_items", UUID.randomUUID(), AuditAction.DELETE,
            "{\"qty\":5}", null, "admin"
        );

        auditService.log(entry);

        verify(auditLogRepository).save(any(AuditLog.class));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl walmal-infrastructure -Dtest=AuditServiceImplTest`
Expected: FAIL — classes not found

- [ ] **Step 3: Create AuditLogRepository**

```java
package com.walmal.infrastructure.audit;

import com.walmal.common.audit.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
}
```

- [ ] **Step 4: Create AuditServiceImpl**

```java
package com.walmal.infrastructure.audit;

import com.walmal.common.audit.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditServiceImpl implements AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditServiceImpl(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(AuditEntry entry) {
        AuditLog auditLog = new AuditLog(
            entry.tableName(),
            entry.recordId(),
            entry.action(),
            entry.oldValue(),
            entry.newValue(),
            entry.performedBy()
        );
        auditLogRepository.save(auditLog);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -pl walmal-infrastructure -Dtest=AuditServiceImplTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add walmal-infrastructure/src/
git commit -m "feat(infra): add AuditLogRepository and AuditServiceImpl with unit test"
```

---

## Task 9: walmal-infrastructure — RabbitDomainEventPublisher

**Files:**
- Create: `walmal-infrastructure/src/main/java/com/walmal/infrastructure/messaging/RabbitDomainEventPublisher.java`
- Create: `walmal-infrastructure/src/test/java/com/walmal/infrastructure/messaging/RabbitDomainEventPublisherTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.walmal.infrastructure.messaging;

import com.walmal.common.event.DomainEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RabbitDomainEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private RabbitDomainEventPublisher publisher;

    @Test
    void should_sendToRabbitMQ_when_eventPublished() {
        DomainEvent event = new DomainEvent("order.created") {};

        publisher.publish(event);

        verify(rabbitTemplate).convertAndSend(
            eq("order.exchange"),
            eq("order.created"),
            eq(event)
        );
    }

    @Test
    void should_useCustomRoutingKey_when_provided() {
        DomainEvent event = new DomainEvent("order.created") {};

        publisher.publish(event, "order.created.priority");

        verify(rabbitTemplate).convertAndSend(
            eq("order.exchange"),
            eq("order.created.priority"),
            eq(event)
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl walmal-infrastructure -Dtest=RabbitDomainEventPublisherTest`
Expected: FAIL — class not found

- [ ] **Step 3: Create RabbitDomainEventPublisher**

```java
package com.walmal.infrastructure.messaging;

import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class RabbitDomainEventPublisher implements DomainEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public RabbitDomainEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(DomainEvent event) {
        String exchange = deriveExchange(event.getEventType());
        rabbitTemplate.convertAndSend(exchange, event.getEventType(), event);
    }

    @Override
    public void publish(DomainEvent event, String routingKey) {
        String exchange = deriveExchange(event.getEventType());
        rabbitTemplate.convertAndSend(exchange, routingKey, event);
    }

    private String deriveExchange(String eventType) {
        String module = eventType.split("\\.")[0];
        return module + ".exchange";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl walmal-infrastructure -Dtest=RabbitDomainEventPublisherTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add walmal-infrastructure/src/
git commit -m "feat(infra): add RabbitDomainEventPublisher with unit tests"
```

---

## Task 10: walmal-infrastructure — RedisCacheService and RedisDistributedLockService

**Files:**
- Create: `walmal-infrastructure/src/main/java/com/walmal/infrastructure/cache/RedisCacheService.java`
- Create: `walmal-infrastructure/src/main/java/com/walmal/infrastructure/cache/RedisDistributedLockService.java`
- Create: `walmal-infrastructure/src/test/java/com/walmal/infrastructure/cache/RedisCacheServiceTest.java`
- Create: `walmal-infrastructure/src/test/java/com/walmal/infrastructure/cache/RedisDistributedLockServiceTest.java`

- [ ] **Step 1: Write failing test for RedisCacheService**

```java
package com.walmal.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisCacheServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private RedisCacheService cacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cacheService = new RedisCacheService(redisTemplate, objectMapper);
    }

    @Test
    void should_returnValue_when_keyExists() {
        when(valueOps.get("test-key")).thenReturn("{\"name\":\"walmal\"}");

        Optional<TestDto> result = cacheService.get("test-key", TestDto.class);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("walmal");
    }

    @Test
    void should_returnEmpty_when_keyNotFound() {
        when(valueOps.get("missing")).thenReturn(null);

        Optional<TestDto> result = cacheService.get("missing", TestDto.class);

        assertThat(result).isEmpty();
    }

    @Test
    void should_putWithTtl_when_durationProvided() {
        cacheService.put("key", new TestDto("val"), Duration.ofMinutes(5));

        verify(valueOps).set(eq("key"), anyString(), eq(Duration.ofMinutes(5)));
    }

    record TestDto(String name) {}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl walmal-infrastructure -Dtest=RedisCacheServiceTest`
Expected: FAIL

- [ ] **Step 3: Create RedisCacheService**

```java
package com.walmal.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.common.cache.CacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

@Service
public class RedisCacheService implements CacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cache value for key '{}': {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public <T> void put(String key, T value) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize cache value", e);
        }
    }

    @Override
    public <T> void put(String key, T value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize cache value", e);
        }
    }

    @Override
    public void evict(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public void evictByPrefix(String prefix) {
        Set<String> keys = redisTemplate.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl walmal-infrastructure -Dtest=RedisCacheServiceTest`
Expected: PASS

- [ ] **Step 5: Write failing test for RedisDistributedLockService**

```java
package com.walmal.infrastructure.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisDistributedLockServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks
    private RedisDistributedLockService lockService;

    @Test
    void should_returnTrue_when_lockAcquired() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("lock:order-123"), anyString(), eq(Duration.ofSeconds(30))))
            .thenReturn(true);

        boolean result = lockService.tryLock("order-123", Duration.ofSeconds(30));

        assertThat(result).isTrue();
    }

    @Test
    void should_returnFalse_when_lockAlreadyHeld() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("lock:order-123"), anyString(), eq(Duration.ofSeconds(30))))
            .thenReturn(false);

        boolean result = lockService.tryLock("order-123", Duration.ofSeconds(30));

        assertThat(result).isFalse();
    }

    @Test
    void should_executeAction_when_lockAcquiredViaExecuteWithLock() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("lock:test"), anyString(), eq(Duration.ofSeconds(10))))
            .thenReturn(true);
        when(redisTemplate.delete("lock:test")).thenReturn(true);

        String result = lockService.executeWithLock("test", Duration.ofSeconds(10), () -> "done");

        assertThat(result).isEqualTo("done");
    }
}
```

- [ ] **Step 6: Create RedisDistributedLockService**

```java
package com.walmal.infrastructure.cache;

import com.walmal.common.cache.DistributedLockService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class RedisDistributedLockService implements DistributedLockService {

    private static final String LOCK_PREFIX = "lock:";
    private final StringRedisTemplate redisTemplate;

    public RedisDistributedLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryLock(String key, Duration timeout) {
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(LOCK_PREFIX + key, UUID.randomUUID().toString(), timeout);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void unlock(String key) {
        // TODO: Verify lock ownership via stored UUID before deleting (use Lua script)
        redisTemplate.delete(LOCK_PREFIX + key);
    }

    @Override
    public <T> T executeWithLock(String key, Duration timeout, Supplier<T> action) {
        if (!tryLock(key, timeout)) {
            throw new IllegalStateException("Failed to acquire lock for key: " + key);
        }
        try {
            return action.get();
        } finally {
            unlock(key);
        }
    }
}
```

- [ ] **Step 7: Run all cache tests**

Run: `mvn test -pl walmal-infrastructure -Dtest="RedisCacheServiceTest,RedisDistributedLockServiceTest"`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add walmal-infrastructure/src/
git commit -m "feat(infra): add RedisCacheService and RedisDistributedLockService with unit tests"
```

---

## Task 11: walmal-infrastructure — MinioFileStorageService

**Files:**
- Create: `walmal-infrastructure/src/main/java/com/walmal/infrastructure/storage/MinioFileStorageService.java`
- Create: `walmal-infrastructure/src/test/java/com/walmal/infrastructure/storage/MinioFileStorageServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.walmal.infrastructure.storage;

import com.walmal.common.storage.StoredFile;
import io.minio.*;
import io.minio.http.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MinioFileStorageServiceTest {

    @Mock
    private MinioClient minioClient;

    @InjectMocks
    private MinioFileStorageService storageService;

    @Test
    void should_returnStoredFile_when_uploadSucceeds() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        InputStream content = new ByteArrayInputStream("test".getBytes());
        StoredFile result = storageService.upload("bucket", "key.txt", content, "text/plain", 4);

        assertThat(result.key()).isEqualTo("key.txt");
        assertThat(result.bucket()).isEqualTo("bucket");
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void should_createBucket_when_bucketDoesNotExist() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        InputStream content = new ByteArrayInputStream("test".getBytes());
        storageService.upload("new-bucket", "key.txt", content, "text/plain", 4);

        verify(minioClient).makeBucket(any(MakeBucketArgs.class));
    }

    @Test
    void should_callRemoveObject_when_deleteIsCalled() throws Exception {
        storageService.delete("bucket", "key.txt");

        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl walmal-infrastructure -Dtest=MinioFileStorageServiceTest`
Expected: FAIL — class not found

- [ ] **Step 3: Create MinioFileStorageService**

```java
package com.walmal.infrastructure.storage;

import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import io.minio.*;
import io.minio.http.Method;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Service
public class MinioFileStorageService implements FileStorageService {

    private final MinioClient minioClient;

    public MinioFileStorageService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    @Override
    public StoredFile upload(String bucket, String key, InputStream content,
                             String contentType, long size) {
        try {
            ensureBucketExists(bucket);
            minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .stream(content, size, -1)
                .contentType(contentType)
                .build());
            return new StoredFile(key, bucket, contentType, size);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload file: " + key, e);
        }
    }

    @Override
    public InputStream download(String bucket, String key) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to download file: " + key, e);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete file: " + key, e);
        }
    }

    @Override
    public String getPresignedUrl(String bucket, String key) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(key)
                .expiry(1, TimeUnit.HOURS)
                .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned URL: " + key, e);
        }
    }

    private void ensureBucketExists(String bucket) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl walmal-infrastructure -Dtest=MinioFileStorageServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add walmal-infrastructure/src/main/java/com/walmal/infrastructure/storage/ walmal-infrastructure/src/test/java/com/walmal/infrastructure/storage/
git commit -m "feat(infra): add MinioFileStorageService with unit tests"
```

---

## Task 12: walmal-infrastructure — Notification Channels

**Files:**
- Create: `walmal-infrastructure/src/main/java/com/walmal/infrastructure/notification/EmailNotificationChannel.java`
- Create: `walmal-infrastructure/src/main/java/com/walmal/infrastructure/notification/InAppNotificationChannel.java`
- Create: `walmal-infrastructure/src/test/java/com/walmal/infrastructure/notification/EmailNotificationChannelTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.walmal.infrastructure.notification;

import com.walmal.common.notification.Notification;
import com.walmal.common.notification.Notification.NotificationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailNotificationChannelTest {

    @Mock
    private JavaMailSender mailSender;

    @InjectMocks
    private EmailNotificationChannel channel;

    @Test
    void should_sendEmail_when_notificationProvided() {
        Notification notification = new Notification(
            "user@example.com", "Order Confirmed", "Your order is confirmed.", NotificationType.EMAIL
        );

        channel.send(notification);

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void should_supportEmailType_when_checked() {
        assertThat(channel.supports(NotificationType.EMAIL)).isTrue();
        assertThat(channel.supports(NotificationType.IN_APP)).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl walmal-infrastructure -Dtest=EmailNotificationChannelTest`
Expected: FAIL — class not found

- [ ] **Step 3: Create EmailNotificationChannel**

```java
package com.walmal.infrastructure.notification;

import com.walmal.common.notification.Notification;
import com.walmal.common.notification.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationChannel.class);
    private final JavaMailSender mailSender;

    public EmailNotificationChannel(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void send(Notification notification) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(notification.recipient());
        message.setSubject(notification.subject());
        message.setText(notification.body());
        mailSender.send(message);
        log.info("Email sent to {}: {}", notification.recipient(), notification.subject());
    }

    @Override
    public boolean supports(Notification.NotificationType type) {
        return type == Notification.NotificationType.EMAIL;
    }
}
```

- [ ] **Step 2: Create InAppNotificationChannel**

```java
package com.walmal.infrastructure.notification;

import com.walmal.common.notification.Notification;
import com.walmal.common.notification.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class InAppNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(InAppNotificationChannel.class);

    @Override
    public void send(Notification notification) {
        // TODO: Persist to in-app notification table when Notification module is built
        log.info("In-app notification for {}: {}", notification.recipient(), notification.subject());
    }

    @Override
    public boolean supports(Notification.NotificationType type) {
        return type == Notification.NotificationType.IN_APP;
    }
}
```

- [ ] **Step 5: Run all notification tests**

Run: `mvn test -pl walmal-infrastructure -Dtest=EmailNotificationChannelTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add walmal-infrastructure/src/main/java/com/walmal/infrastructure/notification/ walmal-infrastructure/src/test/java/com/walmal/infrastructure/notification/
git commit -m "feat(infra): add EmailNotificationChannel and InAppNotificationChannel with unit tests"
```

---

## Task 13: walmal-infrastructure — Configuration Classes

**Files:**
- Create: `walmal-infrastructure/src/main/java/com/walmal/infrastructure/config/InfrastructureAutoConfiguration.java`
- Create: `walmal-infrastructure/src/main/java/com/walmal/infrastructure/config/RabbitMQTopologyConfig.java`

- [ ] **Step 1: Create InfrastructureAutoConfiguration**

```java
package com.walmal.infrastructure.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@ComponentScan("com.walmal.infrastructure")
@EnableJpaRepositories("com.walmal.infrastructure")
public class InfrastructureAutoConfiguration {

    @Bean
    public MinioClient minioClient(
            @Value("${walmal.minio.url}") String url,
            @Value("${walmal.minio.access-key}") String accessKey,
            @Value("${walmal.minio.secret-key}") String secretKey) {
        return MinioClient.builder()
            .endpoint(url)
            .credentials(accessKey, secretKey)
            .build();
    }
}
```

- [ ] **Step 2: Create RabbitMQTopologyConfig**

```java
package com.walmal.infrastructure.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQTopologyConfig {

    @Bean
    public TopicExchange authExchange() {
        return new TopicExchange("auth.exchange");
    }

    @Bean
    public TopicExchange productExchange() {
        return new TopicExchange("product.exchange");
    }

    @Bean
    public TopicExchange inventoryExchange() {
        return new TopicExchange("inventory.exchange");
    }

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange("order.exchange");
    }

    @Bean
    public TopicExchange posExchange() {
        return new TopicExchange("pos.exchange");
    }

    @Bean
    public TopicExchange warehouseExchange() {
        return new TopicExchange("warehouse.exchange");
    }

    @Bean
    public TopicExchange notificationExchange() {
        return new TopicExchange("notification.exchange");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl walmal-infrastructure`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add walmal-infrastructure/src/main/java/com/walmal/infrastructure/config/
git commit -m "feat(infra): add InfrastructureAutoConfiguration and RabbitMQTopologyConfig"
```

---

## Task 14: walmal-app — Application Assembly

**Files:**
- Create: `walmal-app/src/main/java/com/walmal/WalmalApplication.java`
- Create: `walmal-app/src/main/resources/application.yml`
- Create: `walmal-app/src/test/java/com/walmal/WalmalApplicationTest.java`

- [ ] **Step 1: Create WalmalApplication**

```java
package com.walmal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication
@EntityScan("com.walmal")
public class WalmalApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalmalApplication.class, args);
    }
}
```

- [ ] **Step 2: Create application.yml**

```yaml
spring:
  application:
    name: walmal

  datasource:
    url: jdbc:postgresql://localhost:5432/walmal
    username: walmal
    password: walmal

  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect

  flyway:
    enabled: true
    locations: classpath:db/migration

  data:
    redis:
      host: localhost
      port: 6379

  rabbitmq:
    host: localhost
    port: 5672
    username: walmal
    password: walmal

  mail:
    host: localhost
    port: 1025

walmal:
  minio:
    url: http://localhost:9000
    access-key: walmal
    secret-key: walmal123

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always

springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui.html
```

- [ ] **Step 3: Create basic application context test**

```java
package com.walmal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.minio.MinioClient;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
        "org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration"
})
@Testcontainers
class WalmalApplicationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("walmal_test")
        .withUsername("walmal")
        .withPassword("walmal");

    @MockBean
    private MinioClient minioClient;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("walmal.minio.url", () -> "http://localhost:9000");
        registry.add("walmal.minio.access-key", () -> "test");
        registry.add("walmal.minio.secret-key", () -> "test1234");
    }

    @Test
    void should_loadApplicationContext_when_started() {
        // Context loads successfully = pass
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -pl walmal-app`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add walmal-app/src/
git commit -m "feat(app): add WalmalApplication with EntityScan, application.yml, and context test"
```

---

## Task 15: Docker Compose

**Files:**
- Create: `docker-compose.yml`

- [ ] **Step 1: Create docker-compose.yml**

```yaml
services:
  postgres:
    image: postgres:15
    container_name: walmal-postgres
    environment:
      POSTGRES_DB: walmal
      POSTGRES_USER: walmal
      POSTGRES_PASSWORD: walmal
    ports:
      - "5432:5432"
    volumes:
      - walmal-postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U walmal"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7
    container_name: walmal-redis
    ports:
      - "6379:6379"
    volumes:
      - walmal-redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  rabbitmq:
    image: rabbitmq:3-management
    container_name: walmal-rabbitmq
    environment:
      RABBITMQ_DEFAULT_USER: walmal
      RABBITMQ_DEFAULT_PASS: walmal
    ports:
      - "5672:5672"
      - "15672:15672"
    volumes:
      - walmal-rabbitmq-data:/var/lib/rabbitmq
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "check_port_connectivity"]
      interval: 10s
      timeout: 5s
      retries: 5

  minio:
    image: minio/minio
    container_name: walmal-minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: walmal
      MINIO_ROOT_PASSWORD: walmal123
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - walmal-minio-data:/data
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  walmal-postgres-data:
  walmal-redis-data:
  walmal-rabbitmq-data:
  walmal-minio-data:
```

- [ ] **Step 2: Verify Docker Compose is valid**

Run: `docker compose config`
Expected: Outputs resolved YAML with no errors

- [ ] **Step 3: Start services and verify health**

Run: `docker compose up -d && docker compose ps`
Expected: All 4 services running and healthy

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: add Docker Compose with PostgreSQL, Redis, RabbitMQ, and MinIO"
```

---

## Task 16: Flyway Migration — audit_log Table

**Files:**
- Create: `walmal-app/src/main/resources/db/migration/V1__common_create_audit_log.sql`

- [ ] **Step 1: Create migration**

```sql
CREATE TABLE audit_log (
    id          UUID PRIMARY KEY,
    table_name  VARCHAR(255) NOT NULL,
    record_id   UUID NOT NULL,
    action      VARCHAR(50) NOT NULL,
    old_value   JSONB,
    new_value   JSONB,
    performed_by VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_created_at ON audit_log (created_at);
CREATE INDEX idx_audit_log_table_record ON audit_log (table_name, record_id);
```

- [ ] **Step 2: Verify migration runs**

Run: `docker compose up -d postgres && mvn flyway:migrate -pl walmal-app -Dflyway.url=jdbc:postgresql://localhost:5432/walmal -Dflyway.user=walmal -Dflyway.password=walmal`
Expected: Successfully applied 1 migration

- [ ] **Step 3: Commit**

```bash
git add walmal-app/src/main/resources/db/migration/
git commit -m "feat: add V1 Flyway migration for audit_log table"
```

---

## Task 17: Full Build Verification

- [ ] **Step 1: Start Docker Compose services**

Run: `docker compose up -d`
Expected: All services healthy

- [ ] **Step 2: Run full Maven build with tests**

Run: `mvn clean verify`
Expected: BUILD SUCCESS — all modules compile, all tests pass

- [ ] **Step 3: Start the application and verify health**

Run: `mvn spring-boot:run -pl walmal-app &`
Wait 15 seconds for startup, then:
Run: `curl -s http://localhost:8080/actuator/health | head -20`
Expected: `{"status":"UP"}` with component details for db, redis, rabbit

Then stop the application:
Run: `kill %1` (or the background PID)

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "feat: complete Infrastructure & Common Services (Step 1 of 9)"
```

---

## Cleanup

- [ ] **Remove old placeholder files**

```bash
git rm src/.gitkeep docs/architecture/.gitkeep docs/specs/.gitkeep docs/adr/.gitkeep
git commit -m "chore: remove placeholder .gitkeep files"
```
