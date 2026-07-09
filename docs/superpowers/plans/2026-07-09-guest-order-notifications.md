# Guest Order Email Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Guest checkout orders (null userId) receive confirmation, shipping, and cancellation emails at their `guest_email` address.

**Architecture:** The notification module looks up the guest email via a new `GuestOrderQueryService` exposed by the order module (same cross-module pattern as `StaffNotificationQueryService`). V14 relaxes `notification_log.recipient_id` (nullable + new `recipient_email` column with CHECK) and `warehouse_fulfillments.user_id` (guest orders are currently unfulfillable). A new `NotificationService.sendGuestEmailNotification` persists an email-keyed log row and sends via the existing email channel, which already does `setTo(notification.recipient())` verbatim — a raw email address works with no channel change.

**Tech Stack:** Spring Boot 3 / JPA / Flyway / RabbitMQ / Mockito / Testcontainers. Spec: `docs/superpowers/specs/2026-07-09-guest-order-notifications-design.md`.

**Conventions:** Tests named `should_X_when_Y` with matching `@DisplayName`. Run unit tests with `./mvnw -pl <module> -am -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false test` from repo root (`C:/YHA/006_Claude_Workspace/walmal`). ALWAYS include `-am` — without it the module compiles against stale sibling artifacts from `~/.m2` and fails with phantom "does not override" errors. Integration tests are `@Tag("integration")` (Testcontainers; require Docker running).

**Verified facts (do not re-derive):**
- `EmailNotificationChannel.send()` (walmal-infrastructure) does `message.setTo(notification.recipient())` — no user-ID resolution. The user path currently passes `recipientId.toString()` (UUID string) as the To address; that pre-existing quirk is OUT OF SCOPE. The guest path passes a real email in the same field and works as-is.
- `NotificationServiceImpl.sendNotification` catches channel exceptions → `markFailed`, no rethrow → RabbitMQ acks, no redelivery. Mirror this in the guest method.
- Latest migration is `V13__order_add_guest_email.sql`; next slot is V14.
- **Test-classpath migrations:** production migrations live ONLY in `walmal-app/src/main/resources/db/migration` and do NOT reach module test classpaths. Each module keeps hand-copied migration files in its own `src/test/resources/db/migration` (auth V1–V2, inventory V1–V4, order V1–V5, pos V1–V6, product V1–V3). `walmal-notification` and `walmal-warehouse` currently have NO test migration dir — their `@Tag("integration")` tests have never been runnable (hidden because surefire excludes group `integration` by default). Order's copy is missing V13, so `OrderIntegrationTest` is currently broken by the committed `guestEmail` entity field (ddl-auto=validate). This plan fixes all three.
- `walmal-notification/pom.xml` currently depends only on `walmal-common` and `walmal-auth`. It needs a new `walmal-order` dependency for `GuestOrderQueryService`. No cycle: walmal-order does not depend on walmal-notification.

---

### Task 1: V14 migration + entity nullability

**Files:**
- Create: `walmal-app/src/main/resources/db/migration/V14__notification_guest_recipients.sql`
- Create: `walmal-notification/src/test/resources/db/migration/` (copies V1–V14 — dir does not exist yet)
- Create: `walmal-warehouse/src/test/resources/db/migration/` (copies V1–V14 — dir does not exist yet)
- Modify: `walmal-notification/src/main/java/com/walmal/notification/domain/NotificationLog.java`
- Modify: `walmal-warehouse/src/main/java/com/walmal/warehouse/domain/FulfillmentOrder.java:36` (`nullable = false` → `nullable = true` on `user_id`)
- Test: `walmal-notification/src/test/java/com/walmal/notification/infrastructure/NotificationIntegrationTest.java`
- Test: `walmal-warehouse/src/test/java/com/walmal/warehouse/infrastructure/WarehouseIntegrationTest.java`

- [ ] **Step 1: Create the missing test-migration sets (pre-V14 baseline)**

Copy the current production migrations V1–V13 into the two missing test dirs (extra tables from other modules are harmless — `ddl-auto: validate` only checks entities present in each module's test context):

```bash
mkdir -p walmal-notification/src/test/resources/db/migration walmal-warehouse/src/test/resources/db/migration
cp walmal-app/src/main/resources/db/migration/V{1,2,3,4,5,6,7,8,9,10,11,12,13}__*.sql walmal-notification/src/test/resources/db/migration/
cp walmal-app/src/main/resources/db/migration/V{1,2,3,4,5,6,7,8,9,10,11,12,13}__*.sql walmal-warehouse/src/test/resources/db/migration/
```

- [ ] **Step 2: Run the existing integration tests to establish a green baseline**

These tests have never been runnable (no migrations on their test classpath). Confirm they pass BEFORE adding new tests, so any failure here is a pre-existing issue, not caused by this feature:

```
./mvnw -pl walmal-notification -am -Dtest=NotificationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -DexcludedGroups= test
./mvnw -pl walmal-warehouse -am -Dtest=WarehouseIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -DexcludedGroups= test
```

Expected: PASS. If not, STOP and fix the baseline first (surface to human if unclear).

- [ ] **Step 3: Write failing integration tests**

In `NotificationIntegrationTest` (it already autowires nothing suitable — add `@Autowired JdbcTemplate jdbcTemplate;` next to the existing autowired fields; `JdbcTemplate` import already present):

```java
@Test
void should_rejectInsert_when_bothRecipientIdAndEmailNull() {
    assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO notification_log (id, recipient_id, recipient_email, type, status, subject, body, trigger_event, version, created_at, updated_at) " +
            "VALUES (gen_random_uuid(), NULL, NULL, 'EMAIL', 'PENDING', 's', 'b', 'order.confirmed', 0, now(), now())"))
        .hasMessageContaining("chk_notification_recipient");
}

@Test
void should_allowInsert_when_onlyRecipientEmailSet() {
    int rows = jdbcTemplate.update(
            "INSERT INTO notification_log (id, recipient_id, recipient_email, type, status, subject, body, trigger_event, version, created_at, updated_at) " +
            "VALUES (gen_random_uuid(), NULL, 'guest@example.com', 'EMAIL', 'PENDING', 's', 'b', 'order.confirmed', 0, now(), now())");
    assertThat(rows).isEqualTo(1);
}
```

Add `import static org.assertj.core.api.Assertions.assertThatThrownBy;`.

In `WarehouseIntegrationTest`, add (adapt table/entity usage to the file's existing style — it boots the same Flyway-migrated Postgres container):

```java
@Test
void should_createFulfillment_when_guestOrderHasNullUserId() {
    FulfillmentOrder fulfillment = new FulfillmentOrder(UUID.randomUUID(), null, "1 Main St, City, US 12345");
    FulfillmentOrder saved = fulfillmentOrderRepository.save(fulfillment);
    assertThat(saved.getUserId()).isNull();
}
```

(If the repository field has a different name in the file, use the existing one; if none is autowired, add `@Autowired FulfillmentOrderRepository fulfillmentOrderRepository;`.)

- [ ] **Step 4: Run tests to verify they fail (red)**

Same commands as Step 2. Expected failures against the V13 schema:
- Notification: both new tests fail — `recipient_email` column does not exist.
- Warehouse: new test fails — NOT NULL violation on `user_id` (entity `nullable = false` and/or DB constraint).

- [ ] **Step 5: Write the V14 migration and copy it to the test dirs**

`walmal-app/src/main/resources/db/migration/V14__notification_guest_recipients.sql`:

```sql
-- Guest notifications: recipient identified by email, not user id
ALTER TABLE notification_log
    ALTER COLUMN recipient_id DROP NOT NULL,
    ADD COLUMN recipient_email VARCHAR(320),
    ADD CONSTRAINT chk_notification_recipient
        CHECK (recipient_id IS NOT NULL OR recipient_email IS NOT NULL);

-- Guest orders must be fulfillable: fulfillment has no user account
ALTER TABLE warehouse_fulfillments
    ALTER COLUMN user_id DROP NOT NULL;
```

```bash
cp walmal-app/src/main/resources/db/migration/V14__notification_guest_recipients.sql walmal-notification/src/test/resources/db/migration/
cp walmal-app/src/main/resources/db/migration/V14__notification_guest_recipients.sql walmal-warehouse/src/test/resources/db/migration/
```

- [ ] **Step 6: Apply entity changes**

`NotificationLog.java`:

```java
@Column(name = "recipient_id")          // nullable: guest notifications have no user account
private UUID recipientId;

@Column(name = "recipient_email", length = 320)
private String recipientEmail;          // set only when recipientId is null (guest)
```

Add guest constructor after the existing one:

```java
/** Guest notification: recipient identified by email; recipientId stays null. */
public NotificationLog(String recipientEmail, NotificationType type, String subject,
                       String body, String triggerEvent, UUID referenceId) {
    this.recipientEmail = recipientEmail;
    this.type = type;
    this.subject = subject;
    this.body = body;
    this.triggerEvent = triggerEvent;
    this.referenceId = referenceId;
    this.status = NotificationStatus.PENDING;
}
```

Add getter: `public String getRecipientEmail() { return recipientEmail; }`

`FulfillmentOrder.java:36`: change `@Column(name = "user_id", nullable = false)` to `@Column(name = "user_id")` and update the field comment to note null = guest order.

- [ ] **Step 7: Run the integration tests to verify they pass (green)**

Same commands as Step 2. Expected: PASS (JPA `ddl-auto: validate` also confirms entities match the migrated schema).

- [ ] **Step 8: Commit**

```bash
git add walmal-app/src/main/resources/db/migration/V14__notification_guest_recipients.sql \
        walmal-notification/src/test/resources/db/migration \
        walmal-warehouse/src/test/resources/db/migration \
        walmal-notification/src/main/java/com/walmal/notification/domain/NotificationLog.java \
        walmal-warehouse/src/main/java/com/walmal/warehouse/domain/FulfillmentOrder.java \
        walmal-notification/src/test/java/com/walmal/notification/infrastructure/NotificationIntegrationTest.java \
        walmal-warehouse/src/test/java/com/walmal/warehouse/infrastructure/WarehouseIntegrationTest.java
git commit -m "feat(notification): V14 — email-keyed notification recipients and nullable fulfillment user_id"
```

---

### Task 2: GuestOrderQueryService in walmal-order

**Files:**
- Create: `walmal-order/src/main/java/com/walmal/order/application/GuestOrderQueryService.java`
- Create: `walmal-order/src/main/java/com/walmal/order/application/impl/GuestOrderQueryServiceImpl.java`
- Create: `walmal-order/src/test/resources/db/migration/V13__order_add_guest_email.sql` (copy)
- Modify: `walmal-order/src/main/java/com/walmal/order/infrastructure/OrderRepository.java`
- Test: `walmal-order/src/test/java/com/walmal/order/infrastructure/OrderIntegrationTest.java`

- [ ] **Step 1: Fix order's test-migration set (currently broken baseline)**

Order's test copies stop at V5; the committed `Order.guestEmail` entity field fails `ddl-auto: validate` without V13. Flyway does not require contiguous versions, and V6–V12 touch other modules' tables not validated in this context, so copying V13 alone is correct:

```bash
cp walmal-app/src/main/resources/db/migration/V13__order_add_guest_email.sql walmal-order/src/test/resources/db/migration/
```

Then confirm the existing tests are green again:

```
./mvnw -pl walmal-order -am -Dtest=OrderIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -DexcludedGroups= test
```

Expected: PASS.

- [ ] **Step 2: Write failing integration tests** (in `OrderIntegrationTest`, following its existing style for creating orders; use `orderRepository.save(new Order(...))` with the existing constructors — `new Order(null, "guest@example.com", "USD", total, address)` for guest, `new Order(userId, "USD", total, address)` for registered):

```java
@Test
void should_returnGuestEmail_when_guestOrder() {
    Order guest = orderRepository.save(new Order(null, "guest@example.com", "USD", BigDecimal.TEN, shippingAddress()));
    assertThat(guestOrderQueryService.findGuestEmailByOrderId(guest.getId()))
            .contains("guest@example.com");
}

@Test
void should_returnEmpty_when_registeredUserOrder() {
    Order userOrder = orderRepository.save(new Order(UUID.randomUUID(), "USD", BigDecimal.TEN, shippingAddress()));
    assertThat(guestOrderQueryService.findGuestEmailByOrderId(userOrder.getId())).isEmpty();
}

@Test
void should_returnEmpty_when_orderDoesNotExist() {
    assertThat(guestOrderQueryService.findGuestEmailByOrderId(UUID.randomUUID())).isEmpty();
}
```

Autowire `GuestOrderQueryService guestOrderQueryService;` and reuse/introduce a `shippingAddress()` helper matching how the file already builds `ShippingAddress`.

- [ ] **Step 3: Run to verify compile failure** — `GuestOrderQueryService` doesn't exist yet.

- [ ] **Step 4: Implement**

`GuestOrderQueryService.java`:

```java
package com.walmal.order.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Cross-module read API consumed by the notification module
 * (same pattern as auth's StaffNotificationQueryService).
 */
public interface GuestOrderQueryService {

    /**
     * @return the guest email for the order, or empty if the order does not
     * exist, is a registered-user order, or has no guest email recorded.
     */
    Optional<String> findGuestEmailByOrderId(UUID orderId);
}
```

`OrderRepository.java` — add:

```java
/**
 * Guest email lookup for the notification module. The {@code userId IS NULL}
 * predicate guarantees registered-user orders return empty by construction.
 */
@Query("SELECT o.guestEmail FROM Order o WHERE o.id = :orderId AND o.userId IS NULL AND o.guestEmail IS NOT NULL")
Optional<String> findGuestEmailByOrderId(@Param("orderId") UUID orderId);
```

(Add `org.springframework.data.jpa.repository.Query` / `org.springframework.data.repository.query.Param` / `java.util.Optional` imports as needed.)

`GuestOrderQueryServiceImpl.java`:

```java
package com.walmal.order.application.impl;

import com.walmal.order.application.GuestOrderQueryService;
import com.walmal.order.infrastructure.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class GuestOrderQueryServiceImpl implements GuestOrderQueryService {

    private final OrderRepository orderRepository;

    public GuestOrderQueryServiceImpl(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findGuestEmailByOrderId(UUID orderId) {
        return orderRepository.findGuestEmailByOrderId(orderId);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```
./mvnw -pl walmal-order -am -Dtest=OrderIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -DexcludedGroups= test
```

- [ ] **Step 6: Commit** (include the V13 test-migration copy) — `git commit -m "feat(order): expose GuestOrderQueryService for notification module"`

---

### Task 3: NotificationService.sendGuestEmailNotification

**Files:**
- Modify: `walmal-notification/src/main/java/com/walmal/notification/application/NotificationService.java`
- Modify: `walmal-notification/src/main/java/com/walmal/notification/application/impl/NotificationServiceImpl.java`
- Test: `walmal-notification/src/test/java/com/walmal/notification/application/NotificationServiceImplTest.java`

- [ ] **Step 1: Write failing unit tests** (follow the file's existing mock setup — it mocks `NotificationLogRepository` and the two channels):

```java
@Test
@DisplayName("should_persistEmailKeyedLog_when_guestNotificationSent")
void should_persistEmailKeyedLog_when_guestNotificationSent() {
    service.sendGuestEmailNotification("guest@example.com", "Subj", "Body", "order.confirmed", referenceId);

    ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
    verify(notificationLogRepository, atLeastOnce()).save(captor.capture());
    NotificationLog saved = captor.getValue();
    assertThat(saved.getRecipientId()).isNull();
    assertThat(saved.getRecipientEmail()).isEqualTo("guest@example.com");
    assertThat(saved.getStatus()).isEqualTo(NotificationStatus.SENT);
    verify(emailChannel).send(argThat(n -> n.recipient().equals("guest@example.com")));
    verifyNoInteractions(inAppChannel);
}

@Test
@DisplayName("should_markFailedWithoutRethrow_when_guestEmailChannelThrows")
void should_markFailedWithoutRethrow_when_guestEmailChannelThrows() {
    doThrow(new RuntimeException("smtp down")).when(emailChannel).send(any());

    assertThatCode(() -> service.sendGuestEmailNotification(
            "guest@example.com", "Subj", "Body", "order.confirmed", referenceId))
        .doesNotThrowAnyException();   // no rethrow => RabbitMQ acks, no redelivery loop

    ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
    verify(notificationLogRepository, atLeastOnce()).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
}
```

Add `import static org.assertj.core.api.Assertions.assertThatCode;` (and `ArgumentCaptor`/`doThrow`/`argThat` imports if not already present in the file).

- [ ] **Step 2: Run to verify compile failure** (method doesn't exist):

```
./mvnw -pl walmal-notification -am -Dtest=NotificationServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Implement**

`NotificationService.java` — add:

```java
/**
 * Sends an EMAIL notification to a guest (no user account).
 * The log row is keyed by {@code recipientEmail}; {@code recipientId} stays null.
 */
void sendGuestEmailNotification(String recipientEmail, String subject, String body,
                                String triggerEvent, UUID referenceId);
```

`NotificationServiceImpl.java` — add (mirrors `sendNotification`'s failure handling exactly):

```java
@Override
public void sendGuestEmailNotification(String recipientEmail, String subject, String body,
                                       String triggerEvent, UUID referenceId) {
    NotificationLog log = new NotificationLog(recipientEmail, NotificationType.EMAIL,
            subject, body, triggerEvent, referenceId);
    notificationLogRepository.save(log);

    try {
        emailChannel.send(new Notification(recipientEmail, subject, body,
                Notification.NotificationType.EMAIL));
        log.markSent();
        notificationLogRepository.save(log);
    } catch (Exception e) {
        log.markFailed(e.getMessage());
        notificationLogRepository.save(log);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass** (same command as Step 2).

- [ ] **Step 5: Commit** — `git commit -m "feat(notification): add guest email notification path to NotificationService"`

---

### Task 4: Handler guest branches

**Files:**
- Modify: `walmal-notification/pom.xml` (add `walmal-order` dependency)
- Modify: `walmal-notification/src/main/java/com/walmal/notification/application/impl/NotificationEventHandlerServiceImpl.java`
- Modify: `walmal-notification/src/test/java/com/walmal/notification/infrastructure/NotificationIntegrationTest.java` (stub bean)
- Test: `walmal-notification/src/test/java/com/walmal/notification/application/NotificationEventHandlerServiceImplTest.java`

- [ ] **Step 0: Add the walmal-order dependency**

`walmal-notification/pom.xml` declares only `walmal-common` and `walmal-auth`; without this the `com.walmal.order.application.GuestOrderQueryService` import cannot compile. Add next to the existing `walmal-auth` dependency (same groupId/version pattern):

```xml
<!-- walmal-order: GuestOrderQueryService interface for guest email lookup -->
<dependency>
    <groupId>com.walmal</groupId>
    <artifactId>walmal-order</artifactId>
</dependency>
```

(No `<version>` — the parent pom's `dependencyManagement` already manages `walmal-order`, matching the existing `walmal-auth` entry.)

(No dependency cycle: walmal-order does not depend on walmal-notification.)

- [ ] **Step 1: Update tests (failing first).** Add `@Mock GuestOrderQueryService guestQueryService;` (import `com.walmal.order.application.GuestOrderQueryService`) and pass it as the third constructor arg in `setUp()`. REPLACE the three `should_skipNotifications_when_*ForGuestOrder` tests for confirmed/cancelled/shipped with:

```java
@Test
@DisplayName("should_emailGuest_when_orderConfirmedForGuestOrder")
void should_emailGuest_when_orderConfirmedForGuestOrder() {
    when(guestQueryService.findGuestEmailByOrderId(orderId)).thenReturn(Optional.of("g@x.com"));

    handler.handleOrderConfirmed(orderId, null);

    verify(notificationService).sendGuestEmailNotification(
            eq("g@x.com"), anyString(), anyString(), eq("order.confirmed"), eq(orderId));
    verifyNoMoreInteractions(notificationService);   // no IN_APP, no user-path send
}

@Test
@DisplayName("should_emailGuest_when_orderCancelledForGuestOrder")
void should_emailGuest_when_orderCancelledForGuestOrder() {
    when(guestQueryService.findGuestEmailByOrderId(orderId)).thenReturn(Optional.of("g@x.com"));

    handler.handleOrderCancelled(orderId, null, "POS_PRIORITY");

    verify(notificationService).sendGuestEmailNotification(
            eq("g@x.com"), anyString(), contains("in-store sale was processed earlier"),
            eq("order.cancelled"), eq(orderId));
    verifyNoMoreInteractions(notificationService);
}

@Test
@DisplayName("should_emailGuest_when_fulfillmentShippedForGuestOrder")
void should_emailGuest_when_fulfillmentShippedForGuestOrder() {
    when(guestQueryService.findGuestEmailByOrderId(orderId)).thenReturn(Optional.of("g@x.com"));

    handler.handleFulfillmentShipped(orderId, null, "FedEx", "TRK-001");

    verify(notificationService).sendGuestEmailNotification(
            eq("g@x.com"), anyString(), contains("TRK-001"),
            eq("warehouse.fulfillment.shipped"), eq(orderId));
    verifyNoMoreInteractions(notificationService);
}

@Test
@DisplayName("should_skipNotifications_when_guestEmailMissing")
void should_skipNotifications_when_guestEmailMissing() {
    when(guestQueryService.findGuestEmailByOrderId(orderId)).thenReturn(Optional.empty());

    handler.handleOrderConfirmed(orderId, null);

    verifyNoInteractions(notificationService);
}
```

KEEP `should_skipNotifications_when_posSyncConflictResolvedForGuestOrder` unchanged (POS-conflict email stays skipped for guests — the cancellation email already carries the conflict note). Add `import java.util.Optional;`.

- [ ] **Step 2: Run to verify failures** (constructor arity + missing method behavior):

```
./mvnw -pl walmal-notification -am -Dtest=NotificationEventHandlerServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Implement.** In `NotificationEventHandlerServiceImpl`:

1. Add field + constructor param `GuestOrderQueryService guestOrderQueryService` (import `com.walmal.order.application.GuestOrderQueryService`).
2. Add helper (replaces `isGuest` usage in the three handlers; `isGuest` stays for the POS handler):

```java
/**
 * Guest branch: EMAIL-only to the order's guest_email. Returns true if the
 * event was handled as a guest order (caller must return immediately).
 */
private boolean handledAsGuest(UUID userId, UUID orderId, String subject, String body, String triggerEvent) {
    if (userId != null) return false;
    guestOrderQueryService.findGuestEmailByOrderId(orderId).ifPresentOrElse(
            email -> notificationService.sendGuestEmailNotification(email, subject, body, triggerEvent, orderId),
            () -> log.debug("Skipping {} notification for order {}: no guest email on record", triggerEvent, orderId));
    return true;
}
```

3. In `handleOrderConfirmed`, `handleOrderCancelled`, `handleFulfillmentShipped`: build `subject`/`body` FIRST (move the existing `isGuest` guard), then:

```java
if (handledAsGuest(userId, orderId, subject, body, RK_ORDER_CONFIRMED)) return;
```

(using `emailBody` and the respective RK constant per handler; for shipped, orderId param name is `orderId`, trigger `RK_FULFILLMENT_SHIPPED`). `handlePosSyncConflictResolved` keeps `if (isGuest(...)) return;` as-is.

4. Update `NotificationIntegrationTest.TestInfrastructureConfig` with a stub bean so the Spring context in that test doesn't hit the real order table: `@Bean @Primary GuestOrderQueryService stubGuestOrderQueryService() { return orderId -> Optional.empty(); }`

- [ ] **Step 4: Run module tests to verify all pass:**

```
./mvnw -pl walmal-notification -am test
```

Expected: all unit tests pass, 14 in `NotificationEventHandlerServiceImplTest` (13 existing ± replacements + 1 new). Also re-run the integration test to confirm the new stub bean keeps the context bootable:

```
./mvnw -pl walmal-notification -am -Dtest=NotificationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -DexcludedGroups= test
```

- [ ] **Step 5: Commit**

```bash
git add walmal-notification/pom.xml \
        walmal-notification/src/main/java/com/walmal/notification/application/impl/NotificationEventHandlerServiceImpl.java \
        walmal-notification/src/test/java/com/walmal/notification/application/NotificationEventHandlerServiceImplTest.java \
        walmal-notification/src/test/java/com/walmal/notification/infrastructure/NotificationIntegrationTest.java
git commit -m "feat(notification): send guest orders email notifications via guest_email lookup"
```

---

### Task 5: Full verification + E2E

- [ ] **Step 1: Full backend suite:** `./mvnw test` → BUILD SUCCESS, all modules green.
- [ ] **Step 2: Integration tests for touched modules** (need Docker): `./mvnw -pl walmal-notification,walmal-order,walmal-warehouse -am -DexcludedGroups= test` → green.
- [ ] **Step 3: Rebuild JAR** (CRITICAL — E2E uses the packaged JAR): `./mvnw -pl walmal-app -am -DskipTests clean package`
- [ ] **Step 4: Stop any running backend on :8080, then run E2E from walmal-store:** `cd ../walmal-store && npx playwright test` → expect 96 passed.
- [ ] **Step 5: Verify guest email delivery:** `curl -s http://localhost:8025/api/v2/search?kind=containing&query=order | head -50` (MailHog) — expect guest confirmation email(s) addressed to the guest checkout email used by the E2E tests; and `docker exec walmal-postgres psql -U walmal -d walmal -c "SELECT recipient_email, trigger_event, status FROM notification_log WHERE recipient_id IS NULL ORDER BY created_at DESC LIMIT 5"` — expect SENT rows.
- [ ] **Step 6: Commit any remaining changes** — `git commit -m "test(e2e): verify guest order notification delivery"` (only if files changed).
