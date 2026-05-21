# ADR-5: Order Module Design

**Date**: 2026-05-21
**Status**: Accepted
**Module**: walmal-order (Build Order Step 5)
**Authors**: Backend Architect Agent

---

## Context

The Order module is the transaction core of walmal. It transforms a customer's
intent to buy into a persisted, audited order record, orchestrates inventory
reservation, collects payment via a gateway stub, and signals downstream modules
(Warehouse, Notification) through RabbitMQ events.

Upstream, the Product module (Step 3) owns variant master data and pricing. The
Inventory module (Step 4) owns all stock counts and reservation lifecycle. Order
references both by UUID only — it never queries their tables directly. Downstream,
the Warehouse module (Step 7) binds to `order.confirmed` to begin fulfillment.
The Notification module (Step 8) binds to `order.created`, `order.confirmed`,
`order.cancelled`, and `order.fulfilled` to send customer messages. The POS module
(Step 6) calls `OrderCreationService` to persist offline sales as orders.

The most critical design decisions for this module are:
1. Where `PaymentGatewayService` lives (common vs. order-local).
2. How the state machine is enforced given that state transitions can be
   triggered both synchronously (API calls) and asynchronously (inbound
   RabbitMQ events from Inventory).
3. Which operations constitute "destructive" writes requiring an audit log entry.
4. How to prevent a race condition when `cancelOrder()` and an inbound
   `inventory.reservation.released` event arrive concurrently.

This ADR records the decisions for all four questions plus the full domain model,
service interface design, event contracts, caching strategy, and audit log
requirements.

---

## Decision Drivers

1. Module boundary integrity: Product and Inventory own their tables. Order stores
   `user_id`, `variant_id`, and snapshot data as plain columns — no FK declarations
   across module boundaries and no Repository bean imports from other modules.
2. ISP compliance: Three distinct consumer groups (API layer, POS module, Warehouse
   module) have different write needs. Each gets exactly the interface it requires.
3. DIP compliance: `PaymentGatewayService`, `DomainEventPublisher`, `CacheService`,
   and `AuditService` are all accessed through interfaces. No framework or SDK class
   appears in the `application/` layer.
4. State machine safety: Order status transitions must be idempotent under concurrent
   inbound triggers. The same `order.cancelled` outcome must not be reached twice
   with two separate inventory release calls.
5. Price immutability: Once an order is created, `order_items.price_at_purchase`
   must never be recalculated. Product price changes after order creation do not
   affect existing orders.
6. Audit compliance: Status changes on `order_orders` (cancellation, fulfillment)
   are destructive UPDATEs and must write to `audit_log` before execution.
7. Flyway migration number: V5 (follows V1 infrastructure, V2 auth, V3 product,
   V4 inventory).

---

## Considered Options

### Payment Gateway: walmal-common vs. walmal-order/application

**Option A: `PaymentGatewayService` interface lives in `walmal-order/application/`**

The interface is local to the Order module. Only `OrderServiceImpl` ever calls it.
The stub implementation lives in `walmal-infrastructure`, annotated `@Primary`.

Argument for: the payment gateway is consumed by exactly one module (Order). Placing
a cross-cutting interface in `walmal-common` for a single consumer violates ISP at
the module level — `walmal-common` becomes a catch-all rather than a shared kernel of
genuinely platform-wide abstractions.

Argument against: `walmal-common` already hosts `DomainEventPublisher`,
`FileStorageService`, `CacheService`, and `NotificationChannel`. Payment processing
is infrastructure in the same category as message brokering or object storage. The
stub implementation in `walmal-infrastructure` needs a package to implement against
regardless of where the interface lives. If a future POS module ever needs to
process payments independently (post-MVP), the interface is already in common.

**Option B: `PaymentGatewayService` interface lives in `walmal-common` [SELECTED]**

Decision rationale:

The DIP pattern established in CLAUDE.md states: "All infrastructure dependencies
(RabbitMQ, Redis, MinIO, Payment Gateway) accessed through interfaces." The
parenthetical explicitly names Payment Gateway alongside the four infrastructure
abstractions already in `walmal-common`. This is not an accident — payment
processing is infrastructure, not domain logic. Its concrete implementation changes
over the life of the platform (stub → real gateway → potentially multiple gateways).

Placing the interface in `walmal-common` means:
- The stub (`StubPaymentGatewayServiceImpl` in `walmal-infrastructure`) implements
  the same interface location pattern as `RedisCacheService`,
  `RabbitDomainEventPublisher`, and `MinioFileStorageService`.
- If POS ever needs independent payment terminals (future sprint), the interface is
  available without a module restructuring.
- `walmal-order/application/impl/OrderPaymentServiceImpl` imports
  `com.walmal.common.payment.PaymentGatewayService` — the same import style used
  for all other infrastructure interfaces.

The ISP concern (single consumer) is acknowledged but is outweighed by consistency
with the established DIP pattern and the explicit mention in CLAUDE.md. This decision
is revisited if a second consumer never materializes.

**`PaymentResult` record** also lives in `walmal-common` (package
`com.walmal.common.payment`) alongside `PaymentGatewayService`.

Fields: `String paymentReference`, `PaymentStatus status`
`PaymentStatus` enum: `SUCCESS`, `FAILED`, `PENDING`

The stub always returns `PaymentResult(UUID.randomUUID().toString(), SUCCESS)`.

### Order Cancellation Concurrency: Optimistic Lock vs. Status Guard in Application Layer

**Option A: Rely on application-level status check only**

Check `order.status != CANCELLED` before updating. Under concurrent triggers (API
call + RabbitMQ listener firing at the same instant) both threads can pass the check
and both attempt to write CANCELLED, triggering a double `releaseReservation()` call
to Inventory.

Rejected: double-release would decrement `reserved_quantity` twice, leaving
`inventory_stock` in an inconsistent state.

**Option B: Optimistic locking on `order_orders` (`@Version`) [SELECTED]**

A `version BIGINT` column on `order_orders` (managed by JPA `@Version`) provides
the atomicity guard. Whichever thread (API cancelOrder or RabbitMQ listener) commits
first wins. The second thread receives `OptimisticLockException` on its write attempt.

The losing thread catches `OptimisticLockException`, reloads the order, and if the
status is now CANCELLED, it short-circuits without calling `releaseReservation()`
again. This is correct because the winning thread already performed the release.

The listener (`inventory.reservation.released`) already has an explicit guard:
skip if `order.status == CANCELLED`. The `@Version` check makes this guard
race-free — the status seen after a successful lock acquisition is authoritative.

**Option C: Pessimistic locking (SELECT FOR UPDATE)**

Guarantees no concurrent transition. Serialises all status updates for the same
order row.

Rejected: order status transitions are rare per order (at most a handful over the
order's lifetime). Pessimistic locking is unnecessarily heavy for this access pattern
and degrades throughput on the order read path if the same transaction is used.

### Order Items Mutability

`order_items` rows are inserted once at order creation and are never updated
afterward. The `price_at_purchase`, `product_name_snapshot`, and `sku_snapshot`
columns are set from the Product module at creation time and become immutable. No
service method ever calls `save()` on an existing `OrderItem` entity.

The quantity field on an order item is also immutable post-creation. If a customer
changes quantity, that is a new order workflow (cancel + create), not an update —
post-MVP UX concern, out of scope for MVP.

---

## Decision

### Owned Tables

Both tables are owned exclusively by walmal-order. No other module may JOIN against
them or inject any of their Repository beans.

#### `order_orders`

```
id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid()
user_id             UUID          NOT NULL
status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                                  CHECK (status IN ('PENDING', 'CONFIRMED',
                                                    'FULFILLED', 'CANCELLED'))
total_amount        NUMERIC(12,2) NOT NULL CHECK (total_amount >= 0)
currency            VARCHAR(3)    NOT NULL DEFAULT 'USD'
shipping_address    JSONB         NOT NULL
payment_reference   VARCHAR(255)
payment_status      VARCHAR(20)   CHECK (payment_status IN ('SUCCESS', 'FAILED', 'PENDING'))
cancellation_reason VARCHAR(255)
version             BIGINT        NOT NULL DEFAULT 0
created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()

INDEX (user_id)
INDEX (status)
INDEX (status, created_at)
```

`user_id` is a plain UUID column — no FK to `auth_users`. Module boundary rule.

`shipping_address` is stored as JSONB to avoid a separate normalised address table
for MVP. The JSON structure is `{ street, city, state, postalCode, country }`.
JSONB allows future indexed querying (e.g. by country) without a schema migration.

`payment_reference` and `payment_status` are nullable at order creation; populated
on payment attempt. A NULL `payment_reference` combined with `status = PENDING`
indicates payment has not yet been attempted.

`version` is the JPA `@Version` field used for optimistic concurrency control on
status transitions. The database `DEFAULT 0` and the `@Version` annotation on the
entity cooperate: Hibernate reads the version at load time and increments it on
every UPDATE, failing with a `StaleObjectStateException` (mapped to
`OptimisticLockException`) if another thread has already committed a newer version.

`cancellation_reason` is populated when `status` transitions to `CANCELLED`.
It records either the user-provided reason (API-initiated cancel) or the
`conflictReason` from the `inventory.reservation.released` event payload.

#### `order_items`

```
id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid()
order_id              UUID          NOT NULL REFERENCES order_orders(id)
variant_id            UUID          NOT NULL
quantity              INT           NOT NULL CHECK (quantity > 0)
price_at_purchase     NUMERIC(10,2) NOT NULL CHECK (price_at_purchase >= 0)
currency              VARCHAR(3)    NOT NULL DEFAULT 'USD'
product_name_snapshot VARCHAR(255)  NOT NULL
sku_snapshot          VARCHAR(100)  NOT NULL
location_id           UUID          NOT NULL
created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW()

INDEX (order_id)
INDEX (variant_id)
```

`variant_id` is a plain UUID — no FK to `product_variants`. Module boundary rule.

`location_id` is the inventory location UUID from which stock will be reserved.
It is a plain UUID — no FK to `inventory_locations`. Stored here so that the
`order.confirmed` event payload can carry `locationId` per item, enabling the
Warehouse module to route fulfillment to the correct location without querying
Inventory directly.

`price_at_purchase` and `currency` are copied from `ProductPricingService` at order
creation and are immutable thereafter. Changing the product price after order
creation does not affect these columns.

`product_name_snapshot` and `sku_snapshot` are copied from `ProductCatalogService`
at order creation. They allow order detail display and receipts even if the product
is later renamed or its SKU is retired.

`order_items` has no `updated_at` column — the table is insert-only. No service
ever issues an UPDATE on this table.

Flyway migration: `V5__order_create_tables.sql`

---

## Package Structure

```
walmal-order/
  pom.xml

  src/main/java/com/walmal/order/
    api/
      OrderController.java           (@RestController, /api/v1/orders)
      dto/
        request/
          CreateOrderRequest.java    (userId, List<OrderLineItemRequest>, shippingAddress)
          OrderLineItemRequest.java  (variantId, locationId, quantity)
          CancelOrderRequest.java    (orderId, actorId, reason)
        response/
          OrderSummaryResponse.java  (orderId, userId, status, totalAmount, currency, createdAt)
          OrderDetailResponse.java   (OrderSummaryResponse + List<OrderItemResponse>)
          OrderItemResponse.java     (variantId, sku, productName, quantity, priceAtPurchase, currency)
          OrderStatusResponse.java   (orderId, status)

    domain/
      Order.java                     (@Entity, table: order_orders)
      OrderItem.java                 (@Entity, table: order_items)
      OrderStatus.java               (enum: PENDING, CONFIRMED, FULFILLED, CANCELLED)
      ShippingAddress.java           (@Embeddable or plain record — serialised to JSONB)
      event/
        OrderCreatedEvent.java       (extends DomainEvent)
        OrderConfirmedEvent.java     (extends DomainEvent)
        OrderCancelledEvent.java     (extends DomainEvent)
        OrderFulfilledEvent.java     (extends DomainEvent)

    application/
      OrderCreationService.java      (interface — cross-module: API layer, POS)
      OrderQueryService.java         (interface — cross-module: API layer, Warehouse, Notification)
      OrderFulfillmentService.java   (interface — cross-module: Warehouse only)
      impl/
        OrderCreationServiceImpl.java
        OrderQueryServiceImpl.java
        OrderFulfillmentServiceImpl.java
        OrderPaymentOrchestrator.java  (internal — coordinates payment + inventory confirm)

    infrastructure/
      OrderRepository.java           (JpaRepository<Order, UUID>)
      OrderItemRepository.java       (JpaRepository<OrderItem, UUID>)
      listener/
        InventoryEventListener.java  (@RabbitListener — inventory.exchange)

    config/
      OrderRabbitMQConfig.java       (declares order.exchange, queues, bindings,
                                      and inventory.exchange consumer queue)
      OrderCacheConfig.java          (cache key constants and TTL values)
      OrderOpenApiConfig.java        (Springdoc GroupedOpenApi for /orders/**)

  src/test/java/com/walmal/order/
    api/
      OrderControllerTest.java       (@WebMvcTest)
    domain/
      OrderStatusTransitionTest.java
      OrderTest.java
    application/
      OrderCreationServiceImplTest.java  (Mockito)
      OrderQueryServiceImplTest.java     (Mockito)
      OrderFulfillmentServiceImplTest.java (Mockito)
      OrderPaymentOrchestratorTest.java  (Mockito — covers payment success + failure paths)
      ConcurrentCancellationTest.java    (Mockito — covers race between API cancel and
                                          inventory.reservation.released listener)
    infrastructure/
      OrderRepositoryTest.java       (@DataJpaTest, Testcontainers)
      OrderIntegrationTest.java      (@SpringBootTest, Testcontainers PostgreSQL + RabbitMQ)
```

---

## Domain Model Detail

### Order (@Entity, table: order_orders)

```java
@Entity
@Table(name = "order_orders")
public class Order extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;                     // plain UUID — no FK to auth module

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "shipping_address", nullable = false, columnDefinition = "jsonb")
    private String shippingAddress;          // serialised JSON via ObjectMapper

    @Column(name = "payment_reference", length = 255)
    private String paymentReference;        // nullable until payment attempted

    @Column(name = "payment_status", length = 20)
    private String paymentStatus;           // nullable until payment attempted

    @Column(name = "cancellation_reason", length = 255)
    private String cancellationReason;      // nullable until cancelled

    @Version
    @Column(name = "version", nullable = false)
    private long version = 0;               // optimistic lock for concurrent cancel

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL,
               fetch = FetchType.LAZY, orphanRemoval = false)
    private List<OrderItem> items = new ArrayList<>();

    // --- State machine guard methods ---

    public void confirm(String paymentReference) {
        if (this.status != OrderStatus.PENDING) {
            throw new BusinessRuleException(
                "Order can only be confirmed from PENDING. Current: " + this.status);
        }
        this.status = OrderStatus.CONFIRMED;
        this.paymentReference = paymentReference;
        this.paymentStatus = "SUCCESS";
    }

    public void cancel(String reason) {
        if (this.status == OrderStatus.FULFILLED) {
            throw new BusinessRuleException("Cannot cancel a FULFILLED order");
        }
        if (this.status == OrderStatus.CANCELLED) {
            throw new BusinessRuleException("Order is already CANCELLED");
        }
        // CONFIRMED -> CANCELLED is blocked for MVP
        if (this.status == OrderStatus.CONFIRMED) {
            throw new BusinessRuleException(
                "Cancelling a CONFIRMED order is not supported in MVP");
        }
        this.status = OrderStatus.CANCELLED;
        this.cancellationReason = reason;
    }

    public void markFulfilled() {
        if (this.status != OrderStatus.CONFIRMED) {
            throw new BusinessRuleException(
                "Order can only be fulfilled from CONFIRMED. Current: " + this.status);
        }
        this.status = OrderStatus.FULFILLED;
    }

    public boolean isCancellable() {
        return this.status == OrderStatus.PENDING;
    }

    public boolean isCancelled() {
        return this.status == OrderStatus.CANCELLED;
    }
}
```

All state machine transitions are enforced in the entity. The service layer calls
the guard method and catches `BusinessRuleException`. No service class replicates
the state logic — it delegates entirely to the entity.

### OrderItem (@Entity, table: order_items)

```java
@Entity
@Table(name = "order_items")
public class OrderItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;                  // plain UUID — no FK to product module

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "price_at_purchase", nullable = false, precision = 10, scale = 2)
    private BigDecimal priceAtPurchase;     // immutable after creation

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "product_name_snapshot", nullable = false, length = 255)
    private String productNameSnapshot;     // immutable after creation

    @Column(name = "sku_snapshot", nullable = false, length = 100)
    private String skuSnapshot;             // immutable after creation

    @Column(name = "location_id", nullable = false)
    private UUID locationId;                // plain UUID — no FK to inventory module
}
```

`OrderItem` has no `updated_at` column and exposes no setter for `priceAtPurchase`,
`productNameSnapshot`, or `skuSnapshot`. These fields are set once in the
constructor/builder. `OrderItemRepository` exposes `save()` and read methods only —
no update path is called by any service.

---

## Service Interfaces

### OrderCreationService (cross-module: consumed by API layer and POS)

ISP rationale: API layer and POS both need to create orders and cancel pending orders.
They do not need order query methods or fulfillment signaling.

```java
package com.walmal.order.application;

import com.walmal.order.domain.OrderLineItem;
import java.util.List;
import java.util.UUID;

public interface OrderCreationService {

    /**
     * Creates a new order for the given user with the provided line items.
     *
     * Execution sequence:
     * 1. For each line item: call ProductCatalogService.isVariantActive() — reject
     *    the entire order if any variant is inactive.
     * 2. For each line item: call ProductPricingService.getPriceForVariant() and
     *    ProductCatalogService.findVariantBySku() — copy price + name + SKU to item
     *    snapshot fields. Throw BusinessRuleException if any variant has no price.
     * 3. Compute totalAmount = sum of (quantity * price_at_purchase) per line item.
     * 4. Persist the Order entity with status=PENDING.
     * 5. Call InventoryReservationService.reserveStock(orderId, reservationItems).
     *    If reserveStock() throws, the Order transaction rolls back — no PENDING
     *    order row is left without a corresponding reservation.
     * 6. Call PaymentGatewayService.processPayment(orderId, totalAmount, currency).
     *    On SUCCESS: call order.confirm(paymentReference), then
     *    InventoryReservationService.confirmReservation(orderId),
     *    then publish order.confirmed.
     *    On FAILURE: call order.cancel("PAYMENT_FAILED"), then
     *    InventoryReservationService.releaseReservation(orderId, CANCELLED),
     *    then publish order.cancelled.
     * 7. Publish order.created regardless of payment outcome (downstream modules
     *    may want to record the attempt).
     *
     * @param userId        UUID of the placing user (from AuthenticatedPrincipal)
     * @param lineItems     list of (variantId, locationId, quantity) tuples
     * @param shippingAddress serialised shipping address
     * @return the newly created order UUID
     * @throws BusinessRuleException if any variant is inactive or has no price
     * @throws ConcurrencyConflictException if inventory reservation fails after retries
     */
    UUID createOrder(UUID userId, List<OrderLineItem> lineItems, String shippingAddress);

    /**
     * Cancels a PENDING order.
     *
     * Execution sequence:
     * 1. Load the Order by orderId. Throw ResourceNotFoundException if absent.
     * 2. Write audit_log: AuditAction.STATUS_CHANGE on order_orders before mutation.
     * 3. Call order.cancel(reason) — throws BusinessRuleException if not PENDING.
     * 4. Save the Order entity (optimistic lock protects against concurrent cancel).
     *    On OptimisticLockException: reload — if status is already CANCELLED, return
     *    quietly (idempotent). If status is still PENDING, retry once. If the second
     *    attempt also fails, throw ConcurrencyConflictException.
     * 5. Call InventoryReservationService.releaseReservation(orderId, CANCELLED).
     * 6. Publish order.cancelled.
     *
     * @param orderId  the order to cancel
     * @param actorId  UUID of the user or system actor requesting the cancellation
     * @param reason   human-readable cancellation reason (stored in cancellation_reason)
     * @throws ResourceNotFoundException if the order does not exist
     * @throws BusinessRuleException if the order is not in a cancellable state
     */
    void cancelOrder(UUID orderId, UUID actorId, String reason);
}
```

### OrderQueryService (cross-module: consumed by API layer, Warehouse, Notification)

ISP rationale: Query consumers need to read order state for display (API layer),
routing decisions (Warehouse), and customer notifications (Notification). They have
no write need.

```java
package com.walmal.order.application;

import com.walmal.order.api.dto.response.OrderDetailResponse;
import com.walmal.order.api.dto.response.OrderSummaryResponse;
import com.walmal.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.UUID;

public interface OrderQueryService {

    /**
     * Returns full order details including line items.
     * Result is cached at key order:detail:{orderId} with TTL 5 minutes.
     * Cache is evicted on any status change to this order.
     *
     * @throws ResourceNotFoundException if the order does not exist
     */
    OrderDetailResponse getOrder(UUID orderId);

    /**
     * Returns a paginated list of order summaries for the given user, newest first.
     * Not cached — real-time pagination for user order history.
     *
     * @throws ResourceNotFoundException if the user has no orders (returns empty page,
     *         not an exception)
     */
    Page<OrderSummaryResponse> listOrdersByUser(UUID userId, Pageable pageable);

    /**
     * Returns the current status of an order.
     * Lightweight: reads only the status column.
     * Used by Warehouse to determine if fulfillment is still relevant.
     *
     * @throws ResourceNotFoundException if the order does not exist
     */
    OrderStatus getOrderStatus(UUID orderId);
}
```

### OrderFulfillmentService (cross-module: consumed by Warehouse only)

ISP rationale: Warehouse needs exactly one write operation — marking an order
fulfilled. It does not need creation or query methods. Segregating this interface
means the Warehouse module's compile-time dependency on the Order module is minimal:
a single method signature. Adding new query or creation methods cannot break the
Warehouse build.

```java
package com.walmal.order.application;

import java.util.UUID;

public interface OrderFulfillmentService {

    /**
     * Transitions a CONFIRMED order to FULFILLED.
     *
     * Execution sequence:
     * 1. Load the Order by orderId.
     * 2. Write audit_log: AuditAction.STATUS_CHANGE on order_orders before mutation.
     * 3. Call order.markFulfilled() — throws BusinessRuleException if not CONFIRMED.
     * 4. Save the Order entity.
     * 5. Evict order:detail:{orderId} from cache.
     * 6. Publish order.fulfilled.
     *
     * NOTE: Inventory is not called here. Inventory stock was decremented at
     * confirmReservation() time (payment success). No further inventory action
     * is required for fulfillment in MVP.
     *
     * @param orderId the order to mark as fulfilled
     * @throws ResourceNotFoundException if the order does not exist
     * @throws BusinessRuleException if the order is not in CONFIRMED status
     */
    void markFulfilled(UUID orderId);
}
```

### Internal: OrderLineItem record (input to OrderCreationService)

Not a cross-module interface — an input value type used by `OrderCreationService`
and `OrderController` within the same module.

```java
// In com.walmal.order.domain
public record OrderLineItem(
    UUID variantId,
    UUID locationId,
    int quantity
) {}
```

---

## Cross-Module Dependencies (consumed interfaces)

The Order module injects the following interfaces from upstream modules. It never
imports a Repository bean from any of them.

| Interface | Source Module | Used In | Methods Called |
|---|---|---|---|
| `ProductCatalogService` | walmal-product | `OrderCreationServiceImpl` | `isVariantActive()`, `findVariantBySku()` |
| `ProductPricingService` | walmal-product | `OrderCreationServiceImpl` | `getPriceForVariant()` |
| `InventoryReservationService` | walmal-inventory | `OrderCreationServiceImpl`, `OrderCreationServiceImpl.cancelOrder()` | `reserveStock()`, `confirmReservation()`, `releaseReservation()` |
| `PaymentGatewayService` | walmal-common | `OrderCreationServiceImpl` | `processPayment()` |
| `DomainEventPublisher` | walmal-common | All three service impls | `publish()` |
| `CacheService` | walmal-common | `OrderQueryServiceImpl` | `get()`, `put()`, `evict()` |
| `AuditService` | walmal-common | `OrderCreationServiceImpl`, `OrderFulfillmentServiceImpl` | `log()` |

`PaymentGatewayService` minimum interface (in `walmal-common`):

```java
package com.walmal.common.payment;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGatewayService {

    /**
     * Processes payment for the given order.
     *
     * @param orderId   the order UUID (used as idempotency key by the gateway)
     * @param amount    the total to charge
     * @param currency  ISO 4217 currency code
     * @return PaymentResult with reference and status
     */
    PaymentResult processPayment(UUID orderId, BigDecimal amount, String currency);
}
```

`StubPaymentGatewayServiceImpl` in `walmal-infrastructure` is annotated `@Primary`
and `@Profile("!prod")` to prevent accidental use in a future production deployment
before a real gateway is wired.

---

## State Machine

```
                 createOrder()
INTENT ──────────────────────────► PENDING
                                      │
                       payment success │ payment failure / cancelOrder()
                              ┌────────┴───────────────────────────┐
                              ▼                                     ▼
                          CONFIRMED                            CANCELLED
                              │                          (cancellation_reason set)
              markFulfilled() │
                              ▼
                          FULFILLED
```

### Transition rules enforced in the entity

| From | To | Trigger | Guard | Inventory Action |
|---|---|---|---|---|
| (none) | PENDING | `createOrder()` | none | `reserveStock()` |
| PENDING | CONFIRMED | payment SUCCESS | status must be PENDING | `confirmReservation()` |
| PENDING | CANCELLED | payment FAILED | status must be PENDING | `releaseReservation(CANCELLED)` |
| PENDING | CANCELLED | `cancelOrder()` | status must be PENDING | `releaseReservation(CANCELLED)` |
| PENDING | CANCELLED | `inventory.reservation.released` inbound | status must be PENDING; skip if already CANCELLED | none (Inventory already released) |
| CONFIRMED | FULFILLED | `markFulfilled()` | status must be CONFIRMED | none |
| CONFIRMED | CANCELLED | (not supported in MVP) | entity throws BusinessRuleException | n/a |
| FULFILLED | any | (not supported) | entity throws BusinessRuleException | n/a |

### Key invariant

`releaseReservation()` is called by Order only when Order initiates the cancellation
(payment failure, user cancel via API). When the `inventory.reservation.released`
event arrives first, the listener must NOT call `releaseReservation()` — Inventory
has already released the stock. The listener's only responsibility is to transition
the order status to CANCELLED, write the audit log, and publish `order.cancelled`.

---

## RabbitMQ Events

### Exchange declared by this module

`order.exchange` (type: direct, durable: true)

### Published by Order module

| Routing Key | Event Class | Trigger | Payload | Downstream Consumers |
|---|---|---|---|---|
| `order.created` | `OrderCreatedEvent` | End of `createOrder()`, regardless of payment outcome | orderId, userId, items[], totalAmount, currency, createdAt | Notification module |
| `order.confirmed` | `OrderConfirmedEvent` | Payment SUCCESS path in `createOrder()` | orderId, userId, items[] (variantId, locationId, quantity, skuSnapshot), totalAmount, shippingAddress, confirmedAt | Warehouse module, Notification module |
| `order.cancelled` | `OrderCancelledEvent` | Any cancellation path (payment failure, user cancel, inbound inventory event) | orderId, userId, cancellationReason, cancelledAt | Notification module |
| `order.fulfilled` | `OrderFulfilledEvent` | `markFulfilled()` succeeds | orderId, userId, fulfilledAt | Notification module |

`order.confirmed` payload carries `items[]` with per-item `locationId` so the
Warehouse module can route fulfillment tasks to the correct inventory location
without querying the Inventory module.

### Consumed from `inventory.exchange`

`Order` declares a durable queue `order.inventory-events.queue` bound to
`inventory.exchange` with routing key `inventory.reservation.released`.

```
Routing Key                      | Action in InventoryEventListener
---------------------------------|-------------------------------------------------
inventory.reservation.released   | If conflictReason in {POS_PRIORITY,
                                 | BUFFER_EXHAUSTED, EXPIRED}:
                                 |   1. Load order by payload.orderId.
                                 |   2. Acquire optimistic lock (reload entity).
                                 |   3. If order.status == CANCELLED: return — idempotent.
                                 |   4. Write audit_log STATUS_CHANGE before mutation.
                                 |   5. Call order.cancel(conflictReason.name()).
                                 |   6. Save order.
                                 |   7. Publish order.cancelled.
                                 |   NOTE: do NOT call releaseReservation() — Inventory
                                 |         has already performed the release.
```

`inventory.stock.exhausted` is NOT consumed by the Order module. That event has no
`orderId` in its payload — it is a stock-level signal for the Notification and
Warehouse modules, not an order lifecycle signal.

### Event Class Definitions

```
com.walmal.order.domain.event.OrderCreatedEvent extends DomainEvent
  Fields: UUID orderId, UUID userId, List<OrderItemPayload> items,
          BigDecimal totalAmount, String currency, Instant createdAt

com.walmal.order.domain.event.OrderConfirmedEvent extends DomainEvent
  Fields: UUID orderId, UUID userId, List<OrderItemPayload> items,
          BigDecimal totalAmount, String shippingAddress, Instant confirmedAt

com.walmal.order.domain.event.OrderCancelledEvent extends DomainEvent
  Fields: UUID orderId, UUID userId, String cancellationReason, Instant cancelledAt

com.walmal.order.domain.event.OrderFulfilledEvent extends DomainEvent
  Fields: UUID orderId, UUID userId, Instant fulfilledAt

// Shared payload type — internal to order module
record OrderItemPayload(
    UUID variantId,
    UUID locationId,
    int quantity,
    String skuSnapshot
) {}
```

All events are published via `DomainEventPublisher.publish(event, routingKey)`.
`RabbitTemplate` is never injected into any application-layer class.

---

## Concurrent Cancellation Race: Full Analysis and Mitigation

### The race

Two concurrent threads reach `cancelOrder()` or the equivalent cancellation path at
the same instant:

- Thread A: user calls `DELETE /api/v1/orders/{id}` → `OrderCreationService.cancelOrder()`
- Thread B: `InventoryEventListener` processes `inventory.reservation.released`
  (conflictReason = POS_PRIORITY)

Both threads load the `Order` entity with `status = PENDING` and `version = 0`.

Without protection, both threads call `order.cancel(...)`, both call
`OrderRepository.save(order)`, and both call `releaseReservation()` (Thread A only
— Thread B must not call it). The second `save()` would succeed because Hibernate
sees no version conflict if both threads loaded version 0 and both attempt to write
version 1 without checking.

Actually in standard JPA, the second UPDATE would match `WHERE version = 0` but the
first update already set `version = 1`, so the second thread's UPDATE matches zero
rows and Hibernate throws `OptimisticLockException`. This is the `@Version` guard
working correctly.

### Mitigation protocol (in both code paths)

**`cancelOrder()` (API path):**
1. Load order. If already CANCELLED, return idempotent success.
2. Write audit_log.
3. Call `order.cancel(reason)`.
4. Save. On `OptimisticLockException`: reload. If now CANCELLED, return. Else retry once.
5. Call `releaseReservation(orderId, CANCELLED)`.
6. Publish `order.cancelled`.

**`InventoryEventListener` (async path):**
1. Load order by `orderId` from event payload.
2. If `order.status == CANCELLED`: return — guard against the case where the API
   cancel already committed.
3. Write audit_log.
4. Call `order.cancel(conflictReason.name())`.
5. Save. On `OptimisticLockException`: reload. If now CANCELLED, return. Else retry once.
6. Publish `order.cancelled`.
7. Do NOT call `releaseReservation()` — Inventory has already done it.

### Why this is safe

The worst outcome of the race is that both threads attempt to publish `order.cancelled`.
The Notification module consuming that event must be idempotent on duplicate event
delivery (standard RabbitMQ guarantee of at-least-once delivery). The second
`order.cancelled` publish may arrive after the first but produces no visible user
harm if Notification deduplicates by `orderId`.

`releaseReservation()` is called at most once because only the API cancel path calls
it, and on `OptimisticLockException` the API path reloads and short-circuits if the
order is already CANCELLED (meaning the listener won the race and Inventory already
released).

---

## Price Snapshot Strategy

At order creation, for each line item in sequence (before the order is persisted):

1. `ProductCatalogService.isVariantActive(variantId)` — gate check. Fail fast on any
   inactive variant; do not touch Inventory.
2. `ProductCatalogService.findVariantBySku(sku)` — obtains `productNameSnapshot` and
   `skuSnapshot`. The variantId from the request is used directly; `findVariantBySku`
   provides the name and SKU for the snapshot.
3. `ProductPricingService.getPriceForVariant(variantId)` — obtains `priceAtPurchase`
   and `currency`.
4. `OrderItem` is constructed with all snapshot fields populated. The `order_items`
   row is INSERT-only from this point.

After persisting the order, the Product and Pricing services are never called again
for that order. A price change event (`product.price.changed`) from the Product
module is NOT consumed by the Order module — it is irrelevant to existing orders.

Note on the API call ordering: steps 1-3 call Product module services twice per line
item (catalog + pricing). For MVP this is acceptable. If the order creation hot path
shows latency issues under profiling, the Product module can expose a combined
`getVariantForOrder(variantId)` convenience method on `ProductCatalogService` or a
new `OrderVariantService` interface — but that is a future optimisation, not an MVP
decision.

---

## Caching Strategy

All caching uses `CacheService` (DIP). `RedisTemplate` is never injected into
application-layer classes.

| Cache Key | Content | TTL | Eviction Trigger |
|---|---|---|---|
| `order:detail:{orderId}` | `OrderDetailResponse` | 5 minutes | Any status change to this order (confirm, cancel, fulfill) |
| `order:status:{orderId}` | `OrderStatus` | 60 seconds | Any status change |

`listOrdersByUser()` is not cached. User order history is paginated and changes
frequently; the cache invalidation cost (evicting on every order creation or status
change for a given userId) exceeds the read savings for MVP traffic levels.

Order status (`order:status:{orderId}`) is cached separately from the full detail
because Warehouse calls `getOrderStatus()` frequently during fulfillment batch
processing. A 60-second TTL is appropriate — stale status within one minute of a
transition is acceptable; the Warehouse integration test validates that cache eviction
occurs on `markFulfilled()`.

---

## Audit Log Requirements

Per CLAUDE.md: all destructive DB operations write to `audit_log` before execution.
`AuditService.log(AuditEntry)` must be called before the corresponding DB mutation.

`order_items` is insert-only and has no destructive operations — no audit log entry
is required for order item rows.

| Operation | Method | AuditAction | Table Audited | Timing |
|---|---|---|---|---|
| Cancel order | `cancelOrder()` | `STATUS_CHANGE` | `order_orders` | Before `order.cancel()` + `save()` |
| Inbound cancel (inventory event) | `InventoryEventListener` | `STATUS_CHANGE` | `order_orders` | Before `order.cancel()` + `save()` |
| Mark fulfilled | `markFulfilled()` | `STATUS_CHANGE` | `order_orders` | Before `order.markFulfilled()` + `save()` |

Order creation (`createOrder()`) sets `status = PENDING` — this is an INSERT, not a
destructive operation, and does not require an audit log entry.

Payment confirmation (`PENDING → CONFIRMED`) is the expected happy-path transition;
it is not destructive. The `PaymentResult.paymentReference` stored on the order is
an additive write, not a deletion or status reversal.

`AuditEntry` fields for order cancellation:
- `tableName`: `"order_orders"`
- `recordId`: `orderId`
- `action`: `AuditAction.STATUS_CHANGE`
- `oldValue`: JSON of `{"status": "PENDING"}`
- `newValue`: JSON of `{"status": "CANCELLED", "cancellationReason": "..."}`
- `performedBy`: `actorId.toString()` (API path) or `"system:inventory-event-listener"` (async path)

---

## SOLID Compliance

### SRP — One class, one responsibility

| Class | Single Responsibility |
|---|---|
| `OrderCreationServiceImpl` | New order workflow: validation, snapshot, persist, payment, inventory coordination |
| `OrderQueryServiceImpl` | Read-only order queries and cache management |
| `OrderFulfillmentServiceImpl` | CONFIRMED → FULFILLED transition, audit, event publish |
| `OrderPaymentOrchestrator` | Internal helper: execute payment, branch on SUCCESS/FAILED, call confirmReservation or releaseReservation |
| `InventoryEventListener` | Translate `inventory.reservation.released` into order cancellation |

`OrderPaymentOrchestrator` is not a public interface — it is an internal component
in `application/impl/` used by `OrderCreationServiceImpl`. Its single responsibility
is managing the payment call and its two outcome branches so that
`OrderCreationServiceImpl.createOrder()` remains readable. This is an SRP split
within the implementation layer, not a new cross-module interface.

### DIP — Infrastructure via interfaces only

| Infrastructure | Interface Used | Never Used Directly |
|---|---|---|
| RabbitMQ | `DomainEventPublisher` | `RabbitTemplate` |
| Redis | `CacheService` | `RedisTemplate` |
| Audit table | `AuditService` | Direct `JdbcTemplate` or `AuditLogRepository` in service |
| Payment gateway | `PaymentGatewayService` (walmal-common) | Any HTTP client or SDK class |
| Product module | `ProductCatalogService`, `ProductPricingService` (interfaces) | `ProductVariantRepository` or any product bean |
| Inventory module | `InventoryReservationService` (interface) | `InventoryStockRepository` or any inventory bean |

### ISP — Interfaces split by consumer

| Interface | Consumers | Methods |
|---|---|---|
| `OrderCreationService` | API layer, POS module | `createOrder()`, `cancelOrder()` |
| `OrderQueryService` | API layer, Warehouse, Notification | `getOrder()`, `listOrdersByUser()`, `getOrderStatus()` |
| `OrderFulfillmentService` | Warehouse module only | `markFulfilled()` |

The Warehouse module imports only `OrderFulfillmentService` and `OrderQueryService`.
It never sees `cancelOrder()` or `createOrder()`. Adding a new creation method to
`OrderCreationService` cannot break the Warehouse compile.

The Notification module imports only `OrderQueryService` (if it needs to enrich
notification content). If Notification never reads order data directly and relies
solely on event payloads, it imports neither creation nor fulfillment interfaces.

### OCP — Payment strategy is the extension point

`PaymentGatewayService` is the OCP extension point for payment. Adding a new gateway
(Stripe, Adyen) means adding a new `@Component` that implements
`PaymentGatewayService`. `OrderCreationServiceImpl` never changes. The `@Primary`
annotation on `StubPaymentGatewayServiceImpl` is removed and the new implementation
takes its place. No existing class is modified.

### LSP — No subtype violations

No inheritance hierarchies in this module outside `extends BaseEntity`.
`Order.cancel()` does not throw `UnsupportedOperationException` for any state —
it throws `BusinessRuleException` with a descriptive message, which is the
established contract for invalid state transitions across all walmal modules.

---

## SRP Flags in the Three Service Interfaces

### Flag 1: `cancelOrder()` on `OrderCreationService` — minor SRP tension

`OrderCreationService` is named for creation. `cancelOrder()` is a lifecycle mutation,
not a creation operation. Strict SRP would suggest a separate `OrderLifecycleService`
interface with both `cancelOrder()` and any future `amendOrder()` methods.

**Decision: keep `cancelOrder()` on `OrderCreationService` for MVP.**

Rationale: both POS and API layer need `cancelOrder()`. Introducing a third
cross-module interface for one method adds complexity without a clear consumer-driven
reason. The interface name is acknowledged as imprecise; if `cancelOrder()` is joined
by other lifecycle mutations post-MVP (amendments, partial cancellations), this
interface should be renamed `OrderLifecycleService` at that point.

### Flag 2: `createOrder()` orchestrates too many concerns — implementation concern, not interface concern

`createOrder()` coordinates five distinct operations: validation, snapshot capture,
persistence, payment, and inventory confirmation. This looks like an SRP violation
in the implementation, not the interface.

Mitigation: `OrderPaymentOrchestrator` (internal, `application/impl/`) extracts the
payment + inventory confirmation branching into its own class. The remaining steps
in `OrderCreationServiceImpl.createOrder()` are still sequential but each step
delegates to a focused collaborator. The implementation passes SRP because
`OrderCreationServiceImpl`'s single responsibility is "coordinate the new-order
workflow" — orchestration of a multi-step saga is a valid single responsibility.

### Flag 3: `getOrderStatus()` on `OrderQueryService` — no violation

`getOrderStatus()` is a lightweight projection of the same underlying entity as
`getOrder()`. Placing it on a separate interface would create an `OrderStatusService`
consumed only by Warehouse. The Warehouse module already imports `OrderQueryService`
for status checks. No ISP violation: all three methods on `OrderQueryService` are
used by at least one consumer in the query consumer group.

---

## Auth Integration

The Order module does not import any class from walmal-auth.

- JWT validation is handled globally by `JwtAuthenticationFilter` (walmal-auth, wired
  in walmal-app). Order controllers receive an authenticated request automatically.
- Controllers use `@AuthenticationPrincipal AuthenticatedPrincipal principal` to
  obtain `principal.userId()` to pass as `userId` to `createOrder()`, and
  `principal.username()` to pass as `actorId` context to `cancelOrder()`.
- `AuthenticatedPrincipal` is defined in `walmal-common` — safe to import.
- Role-based access:
  - `POST /api/v1/orders`: any authenticated user (`CUSTOMER`, `POS_OPERATOR`)
  - `DELETE /api/v1/orders/{id}`: order owner or `ADMIN`
  - `GET /api/v1/orders`, `GET /api/v1/orders/{id}`: order owner or `ADMIN`

---

## Maven Dependencies

walmal-order depends on:
- `walmal-common` (DIP interfaces: `PaymentGatewayService`, `CacheService`,
  `DomainEventPublisher`, `AuditService`; `BaseEntity`, `AuthenticatedPrincipal`,
  exception types)
- `walmal-product` (for `ProductCatalogService` and `ProductPricingService`
  interface imports only — no Repository beans)
- `walmal-inventory` (for `InventoryReservationService` interface import only —
  no Repository beans)
- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-amqp`
- `springdoc-openapi-starter-webmvc-ui`
- `spring-boot-starter-test` + `testcontainers:postgresql` + `testcontainers:rabbitmq`
  (test scope)

`walmal-infrastructure` is NOT a direct Maven dependency. `walmal-app` wires the
concrete `PaymentGatewayService` (stub), `CacheService`, `DomainEventPublisher`, and
`AuditService` implementations at runtime.

`walmal-order` must be added to the parent `pom.xml` `<modules>` section and to
`walmal-app`'s `<dependencies>` by the module-builder.

---

## Consequences

### Positive

- Module boundary is clean: Product and Inventory are referenced by interface and UUID
  only. No `ProductVariantRepository` or `InventoryStockRepository` bean ever enters
  the Order module's dependency graph.
- `@Version` optimistic locking on `order_orders` makes the concurrent cancellation
  race deterministic. The second committer always loses the optimistic lock and
  reloads to find the order already CANCELLED, producing a correct idempotent outcome.
- `PaymentGatewayService` in `walmal-common` matches the established DIP pattern for
  all infrastructure abstractions. Swapping the stub for a real gateway is a
  walmal-infrastructure change only — no Order module class changes.
- `order_items` as insert-only preserves the price snapshot invariant at the DB level.
  No application code can accidentally mutate `price_at_purchase` after creation.
- ISP-split service interfaces keep the Warehouse module's compile-time dependency on
  Order to a single-method interface (`OrderFulfillmentService`).
- The state machine is enforced in the `Order` entity, not scattered across service
  methods. Any future state transition requirement is added in one place.

### Negative / Risks

- `createOrder()` is a multi-step distributed operation (Product validation +
  Inventory reservation + Payment) within a single database transaction. If payment
  succeeds but `confirmReservation()` throws (e.g. Inventory module bug), the order
  row will be CONFIRMED while inventory is not confirmed. Mitigation: wrap the
  post-payment sequence (`order.confirm()` + `confirmReservation()` + event publish)
  in a transaction. A failure here leaves a CONFIRMED order with PENDING inventory
  reservations. An operator alert (Notification module) should fire on this mismatch;
  resolution is a manual reconciliation step in MVP. A future ADR can introduce a
  saga or outbox pattern.
- `order.confirmed` event carries the full `shippingAddress` as a JSON string. The
  Warehouse module receives this in the event payload. If the address structure
  changes (new field added), both the Order event and the Warehouse listener must be
  updated together. Mitigation: treat `shippingAddress` as an opaque JSON string in
  the event; Warehouse deserialises only the fields it needs.
- Stub payment gateway (`StubPaymentGatewayServiceImpl`) always returns SUCCESS. All
  integration tests that exercise the payment path will pass regardless of actual
  payment logic. The test suite must include a negative test that substitutes a
  FAILED-returning stub to exercise the `PENDING → CANCELLED` path.
- `order:detail:{orderId}` cache has a 5-minute TTL. An order that transitions status
  within this window could serve a stale detail response. Mitigation: explicit cache
  eviction on every status transition in `OrderCreationServiceImpl` and
  `OrderFulfillmentServiceImpl`. The eviction call is part of the DoD checklist.

---

## Definition of Done Checklist

- [ ] `PaymentGatewayService` interface added to `walmal-common` (`com.walmal.common.payment`)
- [ ] `PaymentResult` record and `PaymentStatus` enum added to `walmal-common`
- [ ] `StubPaymentGatewayServiceImpl` added to `walmal-infrastructure`, annotated
      `@Primary` and `@Profile("!prod")`
- [ ] `OrderCreationService` interface defined in `application/`
- [ ] `OrderQueryService` interface defined in `application/`
- [ ] `OrderFulfillmentService` interface defined in `application/`
- [ ] All three implementations complete in `application/impl/`
- [ ] `OrderPaymentOrchestrator` internal component implemented in `application/impl/`
- [ ] `Order` entity mapped to `order_orders` table, `@Version` present on `version` field
- [ ] `OrderItem` entity mapped to `order_items` table, no UPDATE path in any repository usage
- [ ] `Order.cancel()`, `Order.confirm()`, `Order.markFulfilled()` guard methods present
      and enforce valid transitions via `BusinessRuleException`
- [ ] `ShippingAddress` serialised to/from JSONB via `ObjectMapper` in service layer
- [ ] `V5__order_create_tables.sql` Flyway migration applied
- [ ] `OrderController` complete with OpenAPI annotations on all endpoints
- [ ] `OrderRabbitMQConfig` declares `order.exchange`, four routing keys (published),
      and the `order.inventory-events.queue` consumer binding to `inventory.exchange`
- [ ] `InventoryEventListener` handles `inventory.reservation.released` and applies the
      idempotency guard (skip if order already CANCELLED)
- [ ] All four domain events published via `DomainEventPublisher` only
- [ ] `AuditService.log()` called before cancellation and fulfillment status mutations
      (three call sites listed in Audit Log section)
- [ ] `CacheService` used for all cache reads/writes — `RedisTemplate` not in
      application layer
- [ ] No `ProductVariantRepository`, `InventoryStockRepository`, or any other
      cross-module Repository bean imported
- [ ] `ProductCatalogService.isVariantActive()` called before any inventory or price
      lookup in `createOrder()`
- [ ] Price snapshot copied from `ProductPricingService` at creation time; product
      module never called again for an existing order
- [ ] Concurrent cancellation covered by `ConcurrentCancellationTest` — verifies that
      when API cancel and listener fire simultaneously, `releaseReservation()` is
      called exactly once
- [ ] Integration tests pass (`@SpringBootTest` + Testcontainers PostgreSQL + RabbitMQ)
- [ ] `@WebMvcTest` tests for `OrderController`
- [ ] Negative payment test: substitute a FAILED-returning `PaymentGatewayService`
      stub and verify `PENDING → CANCELLED` transition and `releaseReservation()` call
- [ ] `order:detail:{orderId}` and `order:status:{orderId}` cache keys evicted on
      every status change
- [ ] Docker Compose health check passes with `/api/v1/orders` endpoints reachable
- [ ] `walmal-order` added to parent `pom.xml` `<modules>` and `walmal-app`
      `<dependencies>`
