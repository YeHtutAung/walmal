# Transactional Outbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the at-most-once afterCommit publish in `RabbitDomainEventPublisher` with a transactional outbox (at-least-once delivery).

**Architecture:** Every `publish()` serializes the event with the existing `jsonMessageConverter` bean and inserts a row into `outbox_events` inside the caller's transaction. A 1-second `@Scheduled` relay locks pending rows (`FOR UPDATE SKIP LOCKED`), sends raw JSON to RabbitMQ, deletes on success, and halts the batch on failure (order-preserving retry, cap 60 → FAILED).

**Tech Stack:** Spring Boot 3, spring-amqp, JdbcTemplate (no JPA entity), Flyway, JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-07-09-transactional-outbox-design.md`

**Working rules for all tasks:**
- Repo: `C:/YHA/006_Claude_Workspace/walmal`, branch `main` (user-approved; no worktree)
- Maven from repo root. ALWAYS include `-am`: plain `-pl walmal-infrastructure` compiles against a stale walmal-common in `~/.m2` and produces phantom "does not override" errors
- Single-test runs need `-Dsurefire.failIfNoSpecifiedTests=false`
- TDD: write test → run and see it FAIL → implement → see it PASS → commit

---

### Task 1: V15 migration

**Files:**
- Create: `walmal-app/src/main/resources/db/migration/V15__create_outbox_events.sql`

- [ ] **Step 1: Write the migration**

```sql
-- Transactional outbox for domain events (see docs/superpowers/specs/2026-07-09-transactional-outbox-design.md).
-- Rows are written in the business transaction and deleted by OutboxRelay after
-- successful publish. status: PENDING (awaiting delivery) | FAILED (60 attempts
-- exhausted; operator recovery: UPDATE outbox_events SET status='PENDING', attempts=0 WHERE status='FAILED').
CREATE TABLE outbox_events (
    id          UUID PRIMARY KEY,
    exchange    VARCHAR(100) NOT NULL,
    routing_key VARCHAR(100) NOT NULL,
    payload     TEXT NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts    INT          NOT NULL DEFAULT 0,
    last_error  TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE status = 'PENDING';
```

- [ ] **Step 2: Verify Flyway accepts it (app context boots with migration)**

Run: `cd C:/YHA/006_Claude_Workspace/walmal && ./mvnw -pl walmal-app -am -DskipTests clean package -q`
Expected: BUILD SUCCESS (migration syntax is validated at runtime in Task 6; this confirms packaging)

- [ ] **Step 3: Commit**

```bash
git add walmal-app/src/main/resources/db/migration/V15__create_outbox_events.sql
git commit -m "feat(outbox): add V15 outbox_events table"
```

---

### Task 2: OutboxRepository + row record

**Files:**
- Create: `walmal-infrastructure/src/main/java/com/walmal/infrastructure/messaging/OutboxEventRow.java`
- Create: `walmal-infrastructure/src/main/java/com/walmal/infrastructure/messaging/OutboxRepository.java`

No unit tests for this class: it is thin JDBC (each method one statement, no branching) and mocking JdbcTemplate would only re-assert SQL strings. It is exercised by the publisher/relay unit tests through its interface (mocked) and verified for real in Task 6's live drill. Just write it, compile, commit.

- [ ] **Step 1: Write `OutboxEventRow.java`**

```java
package com.walmal.infrastructure.messaging;

import java.util.UUID;

/**
 * One pending row from {@code outbox_events} as selected by
 * {@link OutboxRepository#lockPendingBatch}.
 */
public record OutboxEventRow(
        UUID id,
        String exchange,
        String routingKey,
        String payload,
        int attempts
) {}
```

- [ ] **Step 2: Write `OutboxRepository.java`**

```java
package com.walmal.infrastructure.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * JDBC access to the {@code outbox_events} table (V15).
 *
 * <p>Deliberately not a JPA entity: the outbox is infrastructure plumbing, and
 * plain SQL keeps {@code FOR UPDATE SKIP LOCKED} and the partial index on
 * PENDING rows explicit.</p>
 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a PENDING row. Default REQUIRED propagation: joins the caller's
     * transaction when one is active (rollback removes the row — preserving the
     * "no publish on rollback" guarantee), otherwise runs in its own.
     */
    @Transactional
    public void insert(UUID id, String exchange, String routingKey, String payload) {
        jdbc.update(
                "INSERT INTO outbox_events (id, exchange, routing_key, payload) VALUES (?, ?, ?, ?)",
                id, exchange, routingKey, payload);
    }

    /**
     * Locks and returns the oldest PENDING rows. Must be called inside an active
     * transaction (the relay tick) for {@code FOR UPDATE SKIP LOCKED} to hold.
     */
    public List<OutboxEventRow> lockPendingBatch(int limit) {
        return jdbc.query(
                "SELECT id, exchange, routing_key, payload, attempts FROM outbox_events " +
                "WHERE status = 'PENDING' ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> new OutboxEventRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("exchange"),
                        rs.getString("routing_key"),
                        rs.getString("payload"),
                        rs.getInt("attempts")),
                limit);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM outbox_events WHERE id = ?", id);
    }

    /**
     * Records a failed send attempt. When {@code exhausted} is true the row is
     * parked as FAILED and no longer selected by {@link #lockPendingBatch}.
     */
    public void recordFailure(UUID id, int attempts, String lastError, boolean exhausted) {
        jdbc.update(
                "UPDATE outbox_events SET attempts = ?, last_error = ?, status = ? WHERE id = ?",
                attempts, lastError, exhausted ? "FAILED" : "PENDING", id);
    }
}
```

- [ ] **Step 3: Compile**

Run: `cd C:/YHA/006_Claude_Workspace/walmal && ./mvnw -pl walmal-infrastructure -am -DskipTests compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add walmal-infrastructure/src/main/java/com/walmal/infrastructure/messaging/OutboxEventRow.java \
        walmal-infrastructure/src/main/java/com/walmal/infrastructure/messaging/OutboxRepository.java
git commit -m "feat(outbox): add OutboxRepository JDBC access"
```

---

### Task 3: Rewrite RabbitDomainEventPublisher to write the outbox

**Files:**
- Modify: `walmal-infrastructure/src/main/java/com/walmal/infrastructure/messaging/RabbitDomainEventPublisher.java` (full rewrite)
- Modify: `walmal-infrastructure/src/test/java/com/walmal/infrastructure/messaging/RabbitDomainEventPublisherTest.java` (full rewrite)

Serialization decision (locked by spec): serialize with the **same `MessageConverter` bean** (`jsonMessageConverter`, a `Jackson2JsonMessageConverter`) that `RabbitTemplate` uses today, via `converter.toMessage(event, new MessageProperties())`. Payload bytes are identical to current consumer payloads **by construction** — no ObjectMapper drift possible. The old `TransactionSynchronization` tests are deleted: rollback safety now comes from the DB transaction (row rolls back with the business TX), which cannot be unit-tested with mocks and is covered by the live drill in Task 6.

- [ ] **Step 1: Write the new failing test (replaces the old test file entirely)**

```java
package com.walmal.infrastructure.messaging;

import com.walmal.common.event.DomainEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RabbitDomainEventPublisherTest {

    private final OutboxRepository outboxRepository = mock(OutboxRepository.class);
    private final Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
    private RabbitDomainEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RabbitDomainEventPublisher(outboxRepository, converter);
    }

    @Test
    void should_insertOutboxRow_when_eventPublished() {
        DomainEvent event = new DomainEvent("order.created") {};

        publisher.publish(event);

        verify(outboxRepository).insert(
                any(UUID.class), eq("order.exchange"), eq("order.created"), any(String.class));
    }

    @Test
    void should_useCustomRoutingKey_when_provided() {
        DomainEvent event = new DomainEvent("order.created") {};

        publisher.publish(event, "order.created.priority");

        verify(outboxRepository).insert(
                any(UUID.class), eq("order.exchange"), eq("order.created.priority"), any(String.class));
    }

    @Test
    void should_serializePayloadIdenticallyToRabbitMessageConverter() {
        DomainEvent event = new DomainEvent("order.confirmed") {};
        // What a consumer would receive today if RabbitTemplate serialized the event:
        String expectedJson = new String(
                converter.toMessage(event, new MessageProperties()).getBody(),
                StandardCharsets.UTF_8);

        publisher.publish(event);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository).insert(any(UUID.class), any(), any(), payload.capture());
        // Byte-identical serialization, including timestamp format (spec requirement)
        assertThat(payload.getValue()).isEqualTo(expectedJson);
        assertThat(payload.getValue()).contains("\"eventType\":\"order.confirmed\"");
        assertThat(payload.getValue()).contains("\"timestamp\":");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd C:/YHA/006_Claude_Workspace/walmal && ./mvnw -pl walmal-infrastructure -am test -Dtest=RabbitDomainEventPublisherTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: COMPILE ERROR (constructor `RabbitDomainEventPublisher(OutboxRepository, MessageConverter)` does not exist) — that counts as red.

- [ ] **Step 3: Rewrite the publisher**

```java
package com.walmal.infrastructure.messaging;

import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Transactional-outbox implementation of {@link DomainEventPublisher}.
 *
 * <p>Publishing writes a row to {@code outbox_events} in the caller's
 * transaction (see {@link OutboxRepository#insert}); {@link OutboxRelay}
 * delivers it to RabbitMQ asynchronously (at-least-once, ~1 s latency).
 * If the business transaction rolls back, the row rolls back with it —
 * consumers never see events for uncommitted state.</p>
 *
 * <p>The event is serialized here with the same {@code jsonMessageConverter}
 * bean the RabbitTemplate previously used, so payloads on the wire are
 * byte-identical to the pre-outbox format.</p>
 *
 * <p>A Jackson serialization failure propagates and fails the business
 * transaction: an unserializable event is a programming error, and committing
 * business state whose consumers can never be notified would be worse.</p>
 */
@Service
public class RabbitDomainEventPublisher implements DomainEventPublisher {

    private final OutboxRepository outboxRepository;
    private final MessageConverter messageConverter;

    public RabbitDomainEventPublisher(OutboxRepository outboxRepository,
                                      MessageConverter messageConverter) {
        this.outboxRepository = outboxRepository;
        this.messageConverter = messageConverter;
    }

    @Override
    public void publish(DomainEvent event) {
        publishInternal(event, event.getEventType());
    }

    @Override
    public void publish(DomainEvent event, String routingKey) {
        publishInternal(event, routingKey);
    }

    private void publishInternal(DomainEvent event, String routingKey) {
        String exchange = deriveExchange(event.getEventType());
        Message message = messageConverter.toMessage(event, new MessageProperties());
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        outboxRepository.insert(UUID.randomUUID(), exchange, routingKey, payload);
    }

    private String deriveExchange(String eventType) {
        String module = eventType.split("\\.")[0];
        return module + ".exchange";
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: same command as Step 2.
Expected: 3/3 PASS

- [ ] **Step 5: Run the full infrastructure module tests (nothing else broke)**

Run: `cd C:/YHA/006_Claude_Workspace/walmal && ./mvnw -pl walmal-infrastructure -am test -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add walmal-infrastructure/src/main/java/com/walmal/infrastructure/messaging/RabbitDomainEventPublisher.java \
        walmal-infrastructure/src/test/java/com/walmal/infrastructure/messaging/RabbitDomainEventPublisherTest.java
git commit -m "feat(outbox): publisher writes outbox rows instead of afterCommit send"
```

---

### Task 4: OutboxRelay

**Files:**
- Create: `walmal-infrastructure/src/main/java/com/walmal/infrastructure/messaging/OutboxRelay.java`
- Test: `walmal-infrastructure/src/test/java/com/walmal/infrastructure/messaging/OutboxRelayTest.java`

Note: `@Scheduled` + `@Transactional` on the same method is safe — the scheduler
invokes through the Spring proxy, so the transaction advice applies. The send
exception is caught **inside** the loop so the tick transaction still commits
(spec requirement: otherwise the attempts increment and earlier deletes would
roll back and the cap could never be reached).

- [ ] **Step 1: Write the failing tests**

```java
package com.walmal.infrastructure.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private RabbitTemplate rabbitTemplate;
    @InjectMocks private OutboxRelay relay;

    private OutboxEventRow row(UUID id, int attempts) {
        return new OutboxEventRow(id, "order.exchange", "order.confirmed",
                "{\"eventType\":\"order.confirmed\"}", attempts);
    }

    @Test
    void should_sendAndDeleteInOrder_when_pendingRowsExist() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(outboxRepository.lockPendingBatch(anyInt())).thenReturn(List.of(row(id1, 0), row(id2, 0)));

        relay.relayPendingEvents();

        InOrder inOrder = inOrder(rabbitTemplate, outboxRepository);
        inOrder.verify(rabbitTemplate).send(eq("order.exchange"), eq("order.confirmed"), any(Message.class));
        inOrder.verify(outboxRepository).delete(id1);
        inOrder.verify(rabbitTemplate).send(eq("order.exchange"), eq("order.confirmed"), any(Message.class));
        inOrder.verify(outboxRepository).delete(id2);
        verify(outboxRepository, never()).recordFailure(any(), anyInt(), any(), anyBoolean());
    }

    @Test
    void should_sendPayloadAsJsonBytes() {
        UUID id = UUID.randomUUID();
        when(outboxRepository.lockPendingBatch(anyInt())).thenReturn(List.of(row(id, 0)));

        relay.relayPendingEvents();

        org.mockito.ArgumentCaptor<Message> captor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(any(), any(), captor.capture());
        Message sent = captor.getValue();
        assertThat(new String(sent.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"eventType\":\"order.confirmed\"}");
        assertThat(sent.getMessageProperties().getContentType())
                .isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
    }

    @Test
    void should_recordFailureAndHaltBatch_when_sendFails() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(outboxRepository.lockPendingBatch(anyInt())).thenReturn(List.of(row(id1, 0), row(id2, 0)));
        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).send(any(), any(), any(Message.class));

        relay.relayPendingEvents();

        verify(outboxRepository).recordFailure(eq(id1), eq(1), contains("broker down"), eq(false));
        verify(outboxRepository, never()).delete(any());
        // Batch halted: second row never attempted (ordering preserved)
        verify(rabbitTemplate, times(1)).send(any(), any(), any(Message.class));
    }

    @Test
    void should_parkRowAsFailed_when_attemptsReachCap() {
        UUID id = UUID.randomUUID();
        when(outboxRepository.lockPendingBatch(anyInt())).thenReturn(List.of(row(id, 59)));
        doThrow(new AmqpException("still down"))
                .when(rabbitTemplate).send(any(), any(), any(Message.class));

        relay.relayPendingEvents();

        verify(outboxRepository).recordFailure(eq(id), eq(60), contains("still down"), eq(true));
    }

    @Test
    void should_doNothing_when_noPendingRows() {
        when(outboxRepository.lockPendingBatch(anyInt())).thenReturn(List.of());

        relay.relayPendingEvents();

        verifyNoInteractions(rabbitTemplate);
        verify(outboxRepository, never()).delete(any());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd C:/YHA/006_Claude_Workspace/walmal && ./mvnw -pl walmal-infrastructure -am test -Dtest=OutboxRelayTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: COMPILE ERROR (`OutboxRelay` does not exist) — red.

- [ ] **Step 3: Write `OutboxRelay.java`**

```java
package com.walmal.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * Polls {@code outbox_events} and delivers pending domain events to RabbitMQ.
 *
 * <p>Each tick runs in one transaction: lock the oldest PENDING rows
 * ({@code FOR UPDATE SKIP LOCKED} — multi-instance safe), send each as raw
 * JSON, delete on success. A send failure is caught (so the tick still
 * commits its deletes and the attempts increment), the failure is recorded,
 * and the batch halts so later events never overtake earlier ones.</p>

 * <p>After {@link #MAX_ATTEMPTS} failures (~1 minute of continuous broker
 * outage at one attempt per tick) a row is parked as FAILED and skipped
 * thereafter. Operator recovery:
 * {@code UPDATE outbox_events SET status='PENDING', attempts=0 WHERE status='FAILED'}.</p>
 *
 * <p>Messages are sent without a {@code __TypeId__} header: every listener in
 * the system binds to local POJO records via inferred-type Jackson conversion
 * (verified live 2026-07-09).</p>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    static final int MAX_ATTEMPTS = 60;
    static final int BATCH_SIZE = 100;

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    public OutboxRelay(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void relayPendingEvents() {
        for (OutboxEventRow row : outboxRepository.lockPendingBatch(BATCH_SIZE)) {
            try {
                rabbitTemplate.send(row.exchange(), row.routingKey(), toMessage(row));
                outboxRepository.delete(row.id());
            } catch (RuntimeException e) {
                int attempts = row.attempts() + 1;
                boolean exhausted = attempts >= MAX_ATTEMPTS;
                outboxRepository.recordFailure(row.id(), attempts, e.getMessage(), exhausted);
                if (exhausted) {
                    log.error("Outbox event {} ({} -> {}) FAILED after {} attempts: {}",
                            row.id(), row.exchange(), row.routingKey(), attempts, e.getMessage());
                } else {
                    log.warn("Outbox send failed (attempt {}/{}) for event {} ({} -> {}): {}",
                            attempts, MAX_ATTEMPTS, row.id(), row.exchange(), row.routingKey(),
                            e.getMessage());
                }
                return; // halt batch: preserve event ordering
            }
        }
    }

    private Message toMessage(OutboxEventRow row) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        return new Message(row.payload().getBytes(StandardCharsets.UTF_8), props);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: same command as Step 2.
Expected: 5/5 PASS

- [ ] **Step 5: Commit**

```bash
git add walmal-infrastructure/src/main/java/com/walmal/infrastructure/messaging/OutboxRelay.java \
        walmal-infrastructure/src/test/java/com/walmal/infrastructure/messaging/OutboxRelayTest.java
git commit -m "feat(outbox): add OutboxRelay scheduled publisher"
```

---

### Task 5: Move @EnableScheduling to infrastructure; full build

**Files:**
- Modify: `walmal-infrastructure/src/main/java/com/walmal/infrastructure/config/InfrastructureAutoConfiguration.java`
- Modify: `walmal-inventory/src/main/java/com/walmal/inventory/config/InventoryConfig.java`

Rationale (spec): `@EnableScheduling` currently lives only in `InventoryConfig`;
infrastructure must not depend on another module to activate its relay. Moving
(not duplicating) keeps a single declaration site. `ReservationExpiryJob` in
inventory keeps working — scheduling is enabled globally once declared anywhere.

- [ ] **Step 1: Add to `InfrastructureAutoConfiguration`**

Add import `org.springframework.scheduling.annotation.EnableScheduling;` and the
annotation, with a comment:

```java
@Configuration
@ComponentScan("com.walmal.infrastructure")
@EnableJpaRepositories("com.walmal.infrastructure")
@EnableScheduling  // activates OutboxRelay (and module jobs, e.g. inventory's ReservationExpiryJob)
public class InfrastructureAutoConfiguration {
```

- [ ] **Step 2: Remove from `InventoryConfig` and fix its Javadoc**

New file content:

```java
package com.walmal.inventory.config;

import org.springframework.context.annotation.Configuration;

/**
 * Top-level Spring configuration for the walmal-inventory module.
 *
 * <p>{@code @EnableScheduling} (which activates {@code ReservationExpiryJob})
 * is declared once, in {@code InfrastructureAutoConfiguration}
 * (walmal-infrastructure) — it must NOT be duplicated here.</p>
 *
 * <p>{@code @EnableMethodSecurity} is already declared in {@code AuthSecurityConfig}
 * (walmal-auth). It must NOT be duplicated here.</p>
 */
@Configuration
public class InventoryConfig {
    // Intentionally minimal — beans registered via component scan.
}
```

- [ ] **Step 3: Full build with all module tests**

Run: `cd C:/YHA/006_Claude_Workspace/walmal && ./mvnw clean verify -q`
Expected: BUILD SUCCESS, all module test suites green (order/warehouse/notification tests that mock `DomainEventPublisher` are unaffected — the interface is unchanged)

- [ ] **Step 4: Commit**

```bash
git add walmal-infrastructure/src/main/java/com/walmal/infrastructure/config/InfrastructureAutoConfiguration.java \
        walmal-inventory/src/main/java/com/walmal/inventory/config/InventoryConfig.java
git commit -m "feat(outbox): move @EnableScheduling to infrastructure config"
```

---

### Task 6: Live verification (JAR rebuild, broker-outage drill, E2E)

No new source files. This validates what unit tests cannot: Flyway V15, real
`FOR UPDATE SKIP LOCKED`, rollback semantics, and end-to-end delivery.

Prereqs: Docker services healthy (`docker ps`); if the engine is down:
`wsl --shutdown`, start Docker Desktop, `cd C:/YHA/006_Claude_Workspace/walmal && docker compose up -d --wait`
(poll `docker exec walmal-postgres pg_isready -U walmal` rather than trusting compose health flags).

- [ ] **Step 1: Rebuild the app JAR**

Run: `cd C:/YHA/006_Claude_Workspace/walmal && ./mvnw -pl walmal-app -am -DskipTests clean package -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Start the backend and confirm V15 applied**

Run (background): `cd C:/YHA/006_Claude_Workspace/walmal && java -Dspring.profiles.active=test -jar walmal-app/target/walmal-app-0.1.0-SNAPSHOT.jar > /tmp/backend-outbox.log 2>&1`
Wait for `curl -s http://localhost:8080/actuator/health` → `UP`, then:
`docker exec walmal-postgres psql -U walmal -d walmal -t -c "SELECT count(*) FROM outbox_events"`
Expected: `0` (table exists, empty)

- [ ] **Step 3: Regression — guest confirmation email still arrives**

```bash
curl -s -X DELETE http://localhost:8025/api/v1/messages
curl -s -X POST http://localhost:8080/api/v1/orders -H "Content-Type: application/json" -d '{"items":[{"variantId":"20000000-0000-0000-0000-000000000006","locationId":"a0000000-0000-0000-0000-000000000001","quantity":1}],"shippingAddress":{"line1":"1 Outbox St","city":"Testville","postalCode":"10001","country":"US"},"currency":"USD","guestEmail":"guest-outbox@test.com"}'
sleep 5
curl -s http://localhost:8025/api/v2/messages | grep -c "guest-outbox@test.com"
docker exec walmal-postgres psql -U walmal -d walmal -t -c "SELECT count(*) FROM outbox_events"
```

Expected: MailHog grep ≥ 1 (confirmation email arrived within ~1 s relay latency + email send); outbox count `0` (rows delivered and deleted)

- [ ] **Step 4: Broker-outage drill (the reason this feature exists)**

```bash
docker stop walmal-rabbitmq
curl -s -X POST http://localhost:8080/api/v1/orders -H "Content-Type: application/json" -d '{"items":[{"variantId":"20000000-0000-0000-0000-000000000006","locationId":"a0000000-0000-0000-0000-000000000001","quantity":1}],"shippingAddress":{"line1":"2 Outbox St","city":"Testville","postalCode":"10001","country":"US"},"currency":"USD","guestEmail":"guest-outage@test.com"}'
docker exec walmal-postgres psql -U walmal -d walmal -c "SELECT status, attempts, routing_key FROM outbox_events"
```

Expected: order creation returns 201 (business TX unaffected by broker outage — this was IMPOSSIBLE before); rows PENDING with attempts climbing, backend log shows `Outbox send failed (attempt N/60)` warnings.

```bash
docker start walmal-rabbitmq
sleep 30
curl -s http://localhost:8025/api/v2/messages | grep -c "guest-outage@test.com"
docker exec walmal-postgres psql -U walmal -d walmal -t -c "SELECT count(*) FROM outbox_events"
```

Expected: email for guest-outage@test.com present (event survived the outage); outbox empty. Note: rabbitmq restart may take ~20 s to accept connections; if attempts were consumed during the outage they stay < 60 for a drill this short.

- [ ] **Step 5: Full E2E suite**

Run: `cd C:/YHA/006_Claude_Workspace/walmal-store && npx playwright test` (config auto-starts its own backend — stop the Step 2 backend first to free port 8080)
Expected: 96/96 pass

- [ ] **Step 6: Final commit (if any doc/log tweaks) and report**

No code changes expected in this task; report verification evidence.
