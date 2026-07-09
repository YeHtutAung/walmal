# Transactional Outbox for Domain Events — Design

**Date:** 2026-07-09
**Status:** Approved
**Modules:** walmal-infrastructure (publisher + relay), walmal-app (migration)

## Problem

`RabbitDomainEventPublisher` currently defers `rabbitTemplate.convertAndSend` to
`TransactionSynchronization.afterCommit`. If RabbitMQ is unreachable at that moment,
the exception is caught and logged and the event is **lost** (at-most-once). Lost
events mean lost guest emails, missing fulfillments, and missed order cancellations —
with only an ERROR log line as evidence.

## Goal

At-least-once delivery of every domain event, with the event record committed
atomically with the business transaction that produced it.

## Design

### Flow (outbox-only)

Every `publish()` writes a row to `outbox_events` in the caller's transaction.
A background relay polls the table, publishes to RabbitMQ, and deletes rows on
success. There is no direct-send fast path — one uniform code path.

- Rollback safety: if the business transaction rolls back, the outbox row rolls
  back with it. This preserves the current "no publish on rollback" guarantee.
- Non-transactional callers: the insert runs in its own new transaction
  (`REQUIRED` propagation on the insert method — joins the caller's transaction
  when present, creates one otherwise).
- Latency: events are delivered within ~1 poll interval (1 s). Consumers of
  emails/fulfillments (E2E tests, users) are poll-based and unaffected.

### Schema (Flyway `V15__create_outbox_events.sql`, in walmal-app like all migrations)

```sql
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

`status` values: `PENDING` (awaiting delivery), `FAILED` (retries exhausted,
operator attention required). Successfully sent rows are **deleted**, not marked —
the table stays near-empty in steady state and needs no purge job.

### Publisher (`RabbitDomainEventPublisher`, rewritten)

- Both `publish(DomainEvent)` and `publish(DomainEvent, String routingKey)` now:
  1. Derive the exchange as today (`eventType.split("\\.")[0] + ".exchange"`).
  2. Serialize the event to JSON **at publish time** with the Spring `ObjectMapper`
     (same JSON shape `Jackson2JsonMessageConverter` produces today, so consumer
     payloads are byte-compatible).
  3. Insert an `outbox_events` row (joins the caller's transaction via `REQUIRED`).
- All `TransactionSynchronization` code is removed.
- A serialization failure throws immediately in the caller's transaction (fail
  fast — an unserializable event is a programming error, and failing the business
  transaction is correct because its consumers could never be notified).

### Relay (`OutboxRelay`, new, walmal-infrastructure)

- `@Scheduled(fixedDelay = 1000)` method; `@EnableScheduling` added to the
  infrastructure config if not already active in the app.
- Each tick, in one transaction:
  1. `SELECT * FROM outbox_events WHERE status = 'PENDING'
      ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED`
  2. For each row in order: build a `Message` with the stored payload bytes and
     `contentType=application/json` (no `__TypeId__` header — every listener in
     the system binds to local POJOs via inferred-type conversion, verified live
     2026-07-09), send via `rabbitTemplate.send(exchange, routingKey, message)`.
  3. On success: delete the row.
  4. On send exception: increment `attempts`, set `last_error`, and **stop the
     batch** — later events must not overtake earlier ones (e.g. `order.confirmed`
     before `order.cancelled` for the same order).
  5. If `attempts` reaches 10: set `status='FAILED'`, log at ERROR with the event
     id and routing key. FAILED rows are skipped by the poll query, so one poison
     row cannot block the stream forever; rows failing due to a broker outage all
     recover together when the broker returns (attempts stay well below 10 for
     realistic outages of < ~10 s; longer outages push attempts up once per tick,
     so the cap also bounds retry noise — 10 attempts ≈ 10 s of outage per row
     before it parks as FAILED).

**Cap trade-off (explicit):** a broker outage longer than ~10 s will park rows as
FAILED. Recovery is a one-line SQL `UPDATE outbox_events SET status='PENDING',
attempts=0 WHERE status='FAILED'` by an operator. This is accepted for MVP; the
alternative (exponential backoff / `next_attempt_at`) is deferred as YAGNI.

### Delivery semantics

- **At-least-once:** a crash between step 2 (send) and step 3 (delete) redelivers
  the row on the next tick. All consumers already carry idempotency guards
  (duplicate fulfillment check, PENDING-only cancellation, notification dedupe).
- **Ordering:** preserved per relay batch by `created_at` ordering + halt-on-failure.
  Single app instance today; `SKIP LOCKED` makes multi-instance safe (each row
  processed by exactly one relay) at the cost of cross-instance ordering, which is
  out of scope.

### Persistence access

Plain `JdbcTemplate`/`NamedParameterJdbcTemplate` in walmal-infrastructure — no JPA
entity. The table is infrastructure plumbing, not a domain aggregate; JDBC keeps
`FOR UPDATE SKIP LOCKED` and partial-index behavior explicit.

## Testing

TDD throughout (red → green per test):

1. **Publisher unit tests** (rework `RabbitDomainEventPublisherTest`):
   - publish inserts a row with derived exchange, routing key, JSON payload
   - custom routing key respected
   - JSON payload contains the event fields (shape check against ObjectMapper)
   - no direct `rabbitTemplate` interaction anymore
2. **Relay unit tests** (new `OutboxRelayTest`, mocked JdbcTemplate/RabbitTemplate):
   - pending row → sent with correct exchange/key/contentType → deleted
   - send failure → attempts incremented, last_error set, batch halted (later rows untouched)
   - attempts hitting 10 → status FAILED + ERROR log, row no longer selected
   - empty poll → no broker interaction
3. **Live verification** (after JAR rebuild):
   - guest checkout → confirmation email arrives (regression)
   - broker-outage drill: `docker stop walmal-rabbitmq` → place guest order
     (transaction commits fine, row stays PENDING) → `docker start walmal-rabbitmq`
     → email arrives, outbox row gone
   - full Playwright suite (96 tests) still green

## Out of scope (YAGNI)

- RabbitMQ publisher confirms (broker-receipt guarantee beyond send-exception)
- Multi-instance leader election / cross-instance ordering
- Exponential backoff / `next_attempt_at` scheduling
- SENT-row retention for auditing (`audit_log` already records business mutations)
- Admin/actuator endpoint for FAILED rows (SQL is sufficient for MVP)
