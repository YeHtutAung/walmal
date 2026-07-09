# Guest Order Email Notifications — Design

**Date:** 2026-07-09
**Status:** Approved
**Repo:** walmal (Spring Boot backend)

## Problem

V13 introduced guest checkout (`order_orders.user_id` nullable, `guest_email` added), but the notification pipeline was never adapted. Commit `741ce05` stopped the resulting crash (null `recipient_id` NOT NULL violation + RabbitMQ redelivery loop) by skipping notifications when `userId` is null. Consequence: guest buyers receive **no** confirmation, shipping, or cancellation emails, even though we capture their email at checkout.

Verification also uncovered a second latent bug: `warehouse_fulfillments.user_id` is NOT NULL (V7) and `FulfillmentOrder.userId` is `@Column(nullable = false)`, so fulfillment creation crashes for guest orders — guest orders currently cannot be fulfilled at all.

## Scope

Guest orders receive **email-only** notifications for:

1. Order confirmation (`order.confirmed`)
2. Shipping (`warehouse.fulfillment.shipped`)
3. Cancellation (`order.cancelled`) — includes POS-conflict cancellations via the existing `buildCancellationNote()` text

Explicitly out of scope:

- The separate `pos.sync.conflict.resolved` email for guests. It fires alongside `order.cancelled` for the same order; guests would get two emails about one cancellation. The guest guard in `handlePosSyncConflictResolved` stays.
- IN_APP notifications for guests (no account to view them).
- `handleStockLow` / `handleUserRegistered` (staff/registered-user only; cannot receive null userId).

## Design

### 1. Guest email lookup — cross-module query service

The order/warehouse event messages do not carry the guest email, and adding it would touch multiple publishers in different modules. Instead, follow the established cross-module pattern (`StaffNotificationQueryService` from auth): the order module exposes a query service consumed by the notification module.

```java
public interface GuestOrderQueryService {
    /**
     * @return the guest email for the order, or empty if the order does not
     * exist, is a registered-user order, or has no guest email recorded.
     */
    Optional<String> findGuestEmailByOrderId(UUID orderId);
}
```

Implementation queries `SELECT guest_email FROM order_orders WHERE id = ? AND user_id IS NULL`. The `AND user_id IS NULL` predicate guarantees a registered-user order returns `Optional.empty()` by construction — this contract sits next to the exact null-handling pattern that caused the original crash, so it is explicit and tested.

Rejected alternative: adding `guestEmail` to `OrderConfirmedMessage`, `OrderCancelledMessage`, `FulfillmentShippedMessage` — message-schema churn across three publishers, and the warehouse module does not know the email.

### 2. Persistence — V14 migration

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

`NotificationLog` entity gains `recipientEmail` (nullable, length 320) and a constructor for guest logs; `recipientId` becomes nullable. `FulfillmentOrder.userId` becomes `@Column(nullable = true)`.

Guest notifications are logged for audit parity with user notifications. `recipient_email` is format-validated upstream: `CreateOrderRequest.guestEmail` carries `@Email` and the controller rejects null/blank for guest checkout, so only validated addresses reach the log.

### 3. Service layer

New method on `NotificationService`:

```java
void sendGuestEmailNotification(String recipientEmail, String subject, String body,
                                String triggerEvent, UUID referenceId);
```

EMAIL-only. Persists a `NotificationLog` with `recipientEmail` set and `recipientId` null, then dispatches through the email channel with the guest address as the recipient, using the same try/catch → `markSent()` / `markFailed()` structure as `sendNotification`.

**Idempotency / redelivery safety (verified):** channel-send failures are caught and recorded as `NotificationStatus.FAILED` without rethrowing, so the RabbitMQ listener acks and there is no redelivery — a transient SMTP failure yields exactly one FAILED row and zero duplicate emails. (The original loop happened because the constraint violation occurred in the log save *before* the try block; with the V14 constraint satisfied, that save succeeds.) Deliverability failures are visible as FAILED rows in `notification_log`, same as existing user-email failures.

### 4. Handlers (`NotificationEventHandlerServiceImpl`)

For `handleOrderConfirmed`, `handleOrderCancelled`, `handleFulfillmentShipped`:

```
if userId != null  → current behavior (EMAIL + IN_APP via userId)
else               → guestOrderQueryService.findGuestEmailByOrderId(orderId)
                       present → sendGuestEmailNotification(email, same subject/body, ...)
                       empty   → debug-log skip (defensive: pre-V13 data, deleted orders)
```

`handlePosSyncConflictResolved` keeps the existing guest skip.

**Shipping-path dependency (verified):** `FulfillmentShippedEvent` always carries `orderId` (`fulfillment.getOrderId()`), so the lookup works regardless of the event's `userId`; the V14 warehouse change above is what makes guest fulfillments creatable in the first place.

## Testing

TDD throughout (failing tests first):

- **Handlers (unit, Mockito):** for each of the three handlers with null userId — exactly one guest email with expected subject/body, no IN_APP, no `sendNotification(UUID, ...)` call; lookup returns empty → no interaction with `NotificationService`.
- **`GuestOrderQueryService` (unit + repository test):** guest order → email; registered-user order → empty (even with guest_email hypothetically set, via the `user_id IS NULL` predicate); unknown orderId → empty.
- **`NotificationServiceImpl` (unit):** guest path persists log with null recipientId + email set; channel failure → FAILED, no rethrow.
- **DB constraint (integration):** raw `JdbcTemplate` insert with both `recipient_id` and `recipient_email` NULL is rejected by `chk_notification_recipient` at the DB layer.
- **Fulfillment (integration):** creating a fulfillment for a guest order (null userId) succeeds after V14.
- **E2E (walmal-store):** existing guest checkout tests pass; after JAR rebuild, MailHog receives the guest confirmation email.

## Affected modules

- `walmal-order`: `GuestOrderQueryService` + impl + repository query
- `walmal-notification`: `NotificationService`/impl, `NotificationLog`, handlers, email channel recipient handling
- `walmal-warehouse`: `FulfillmentOrder.userId` nullable
- `walmal-app`: V14 migration
