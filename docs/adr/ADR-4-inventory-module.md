# ADR-4: Inventory Module Design

**Date**: 2026-05-20
**Status**: Accepted
**Module**: walmal-inventory (Build Order Step 4)
**Authors**: Backend Architect Agent

---

## Context

The Inventory module is the single source of truth for stock levels across all locations
in the walmal platform. It owns every stock count, every reservation lifecycle, every
stock movement record, and the catalogue of physical locations (stores and warehouses).

Upstream, the Product module (Step 3) owns the variant master data. Inventory references
variants by UUID only — it never queries product tables. Downstream, Order (Step 5) calls
`InventoryReservationService` to lock stock at order creation, confirm it on payment, and
release it on cancellation. POS (Step 6) calls `InventoryQueryService` for real-time
stock checks. Warehouse (Step 7) calls `InventoryAdjustmentService` to post receipts,
adjustments, and transfers.

The module's most complex responsibility is offline POS sync conflict resolution: when a
POS terminal has been operating offline and resynchronises, its sales may conflict with
web reservations created against the same stock. The resolution algorithm is deterministic,
must not lose data, and must always notify the affected customer via an asynchronous event.

This ADR records the decisions for the domain model, service interface design, concurrency
strategy, reservation state machine, POS conflict resolution flow, caching, and audit log
requirements.

---

## Decision Drivers

1. Module boundary integrity: Order, POS, and Warehouse reference `variant_id` and
   `location_id` as UUID values only. They never query `inventory_*` tables directly
   and never import any `inventory` Repository bean.
2. ISP compliance: Three consumer groups (Order, POS, Warehouse) have distinct read/write
   needs. Each gets exactly the interface methods it requires and no more.
3. DIP compliance: Redis accessed only via `CacheService`, RabbitMQ only via
   `DomainEventPublisher`, audit trail only via `AuditService`. `RedisTemplate` and
   `RabbitTemplate` are never injected into application-layer classes.
4. Concurrency correctness: Stock adjustment is a high-contention operation. Two concurrent
   reservation requests for the same variant at the same location must not both succeed
   when only one unit remains.
5. POS conflict determinism: The conflict resolution algorithm must be executable without
   human intervention, must document the reason in the reservation row, and must always
   trigger downstream notification.
6. Audit compliance: All destructive stock mutations (reservation release, stock adjustment,
   transfer) write to `audit_log` before the DB mutation executes, per CLAUDE.md.
7. Flyway migration number: V4 (follows V1 infrastructure, V2 auth, V3 product).

---

## Considered Options

### Concurrency Strategy: Optimistic vs. Pessimistic Locking

**Option A: Pessimistic locking (SELECT FOR UPDATE)**
Acquires a row-level lock for the entire duration of the reservation transaction.
Guarantees no concurrent modification but serialises all reservation requests for the
same variant-location pair.
- Rejected for general use: locks degrade throughput under the POS read-heavy workload.
  Acceptable for the POS conflict resolution override path only, where the write is
  deliberate and the quantity check is part of the correctness invariant.

**Option B: Optimistic locking with @Version [SELECTED for reservation path]**
`inventory_stock` carries a `version` column (managed by `@Version`). On concurrent
modification, Hibernate throws `OptimisticLockException`. The caller receives a
`ConcurrencyConflictException` (walmal-common) and can retry with fresh state.
- Accepted: reservation is a write that reads state first, increments reserved quantity,
  and decrements available quantity. Collisions are expected but rare in normal operation.
  Three retries with exponential back-off are sufficient. After three failures the
  reservation request is rejected with HTTP 409 to the Order module, which surfaces a
  user-facing error.

**Option C: Database-level UPDATE with WHERE clause [SELECTED for POS conflict override]**
For POS conflict resolution the intent is an intentional override: the POS sale has
priority and the current available quantity must be atomically decremented below the
web reservation level.
```sql
UPDATE inventory_stock
   SET available_quantity = available_quantity - :delta,
       version = version + 1,
       updated_at = NOW()
 WHERE variant_id = :variantId
   AND location_id = :locationId
   AND available_quantity >= :delta
```
If the WHERE clause matches zero rows, the operation fails cleanly and the BUFFER_EXHAUSTED
path is triggered. No Hibernate entity is reloaded before issuing this update — the WHERE
clause is the guard.

**Decision**: Optimistic locking (`@Version`) for the normal reservation path; direct UPDATE
with WHERE-clause quantity guard for the POS conflict override path.

### Reservation Expiry: Scheduled Poll vs. Database Trigger

**Option A: Database trigger on `expires_at`**
A PostgreSQL trigger fires when a row's `expires_at` passes.
- Rejected: triggers bypass the application layer, skip audit log writes, skip event
  publishing, and are invisible to the service layer. Violates the requirement to publish
  `inventory.reservation.released` with `conflict_reason=EXPIRED`.

**Option B: Scheduled job in application layer [SELECTED]**
A Spring `@Scheduled` method runs periodically (configurable interval, default 60 seconds),
finds `inventory_reservations` rows where `status = 'PENDING' AND expires_at < NOW()`,
and calls `releaseReservation()` for each with `conflictReason=EXPIRED`. This writes
to `audit_log`, updates `inventory_stock`, records an `inventory_movements` row, and
publishes `inventory.reservation.released`.
- Accepted: full application visibility, audit compliance, event publishing, and no
  DB-layer side effects.

### Location Model: Flat Table vs. Hierarchical Locations

**Option A: Hierarchical locations (region → store → shelf)**
Enables fine-grained stock tracking at shelf level.
- Rejected for MVP: Advanced WMS is out of scope. The location model for MVP is flat.
  `inventory_locations` is a flat table. The `is_buffer_location` flag distinguishes
  warehouse buffer stock from regular stock.

**Option B: Flat location table with `is_buffer_location` flag [SELECTED]**
Each location is a single row. `is_buffer_location = TRUE` marks warehouse locations
that hold overflow/safety stock used as a fallback in POS conflict resolution.
`external_reference_id UUID` (no FK) references the store or warehouse UUID from the
POS or Warehouse module. No FK is imposed to maintain module boundary integrity.

---

## Decision

### Owned Tables

All four tables are owned exclusively by walmal-inventory. No other module may JOIN
against them or inject any of their Repository beans.

#### `inventory_locations`

```
id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid()
name                  VARCHAR(100) NOT NULL
location_type         VARCHAR(30)  NOT NULL CHECK (location_type IN ('STORE', 'WAREHOUSE', 'VIRTUAL'))
is_buffer_location    BOOLEAN      NOT NULL DEFAULT FALSE
external_reference_id UUID
is_active             BOOLEAN      NOT NULL DEFAULT TRUE
created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()

UNIQUE (external_reference_id)  -- plain uniqueness constraint, no FK to another module
INDEX (is_buffer_location)
INDEX (is_active)
```

`external_reference_id` uniquely identifies the location from the perspective of an
external module (POS store UUID, Warehouse UUID). No FK is declared — this is intentional
to preserve module boundary integrity. The UNIQUE constraint prevents duplicate location
registrations for the same external entity.

`is_buffer_location = TRUE` marks locations whose stock is the last resort in POS
conflict resolution (step 2 of the algorithm). A warehouse may have multiple locations,
some buffer and some not.

#### `inventory_stock`

```
id                UUID         PRIMARY KEY DEFAULT gen_random_uuid()
variant_id        UUID         NOT NULL
location_id       UUID         NOT NULL REFERENCES inventory_locations(id)
available_quantity INT          NOT NULL DEFAULT 0 CHECK (available_quantity >= 0)
reserved_quantity  INT          NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0)
low_stock_threshold INT         NOT NULL DEFAULT 10
version           BIGINT       NOT NULL DEFAULT 0
created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()

UNIQUE (variant_id, location_id)
INDEX (variant_id)
INDEX (location_id)
```

`variant_id` is a plain UUID column — no FK to `product_variants`. Module boundary rule.
`UNIQUE (variant_id, location_id)` enforces one stock row per variant per location at
DB level. Application code uses `findByVariantIdAndLocationId()` but the DB constraint
is the ultimate guard against duplicate insertions.

`version` is the optimistic lock version field, managed by JPA `@Version`. Hibernate
increments it on every UPDATE and checks it matches the loaded value before writing.

`low_stock_threshold` is per-variant-per-location and controls when `inventory.stock.low`
is published. Configurable via `InventoryAdjustmentService.updateThreshold()`.

#### `inventory_reservations`

```
id               UUID         PRIMARY KEY DEFAULT gen_random_uuid()
order_id         UUID         NOT NULL
variant_id       UUID         NOT NULL
location_id      UUID         NOT NULL REFERENCES inventory_locations(id)
quantity         INT          NOT NULL CHECK (quantity > 0)
status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                              CHECK (status IN ('PENDING', 'CONFIRMED', 'RELEASED'))
expires_at       TIMESTAMPTZ  NOT NULL
conflict_reason  VARCHAR(30)
                              CHECK (conflict_reason IS NULL OR conflict_reason IN
                                ('POS_PRIORITY', 'BUFFER_EXHAUSTED', 'CANCELLED', 'EXPIRED'))
created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()

INDEX (order_id)
INDEX (variant_id, location_id)
INDEX (status, expires_at)   -- supports the expiry scheduler query
```

`order_id` and `variant_id` are plain UUID columns — no FKs to Order or Product tables.
`conflict_reason` is nullable: it is NULL for PENDING and CONFIRMED reservations; it is
populated only when status transitions to RELEASED, documenting why the stock was freed.

The `(status, expires_at)` index is the primary index for the expiry scheduler query:
`WHERE status = 'PENDING' AND expires_at < NOW()`. It must be partial in production
environments if the PENDING set is small relative to total rows:

```sql
CREATE INDEX idx_inv_reservations_pending_expiry
  ON inventory_reservations (expires_at)
  WHERE status = 'PENDING';
```

#### `inventory_movements`

```
id            UUID         PRIMARY KEY DEFAULT gen_random_uuid()
variant_id    UUID         NOT NULL
location_id   UUID         NOT NULL REFERENCES inventory_locations(id)
movement_type VARCHAR(20)  NOT NULL CHECK (movement_type IN
                ('RECEIPT', 'ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN',
                 'RESERVATION', 'RELEASE', 'SALE'))
quantity_delta INT          NOT NULL
reference_id  UUID                      -- nullable: reservation_id, transfer_id, etc.
performed_by  VARCHAR(100) NOT NULL
created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()

INDEX (variant_id, created_at)
INDEX (location_id, created_at)
INDEX (movement_type, created_at)
INDEX (reference_id) WHERE reference_id IS NOT NULL
```

`inventory_movements` is insert-only. There are NO UPDATE or DELETE operations on this
table — ever. It is a domain audit trail for business-level events, distinct from
`audit_log` (which is the security/compliance trail).

`quantity_delta` is signed: positive for inflows (RECEIPT, RELEASE, TRANSFER_IN),
negative for outflows (RESERVATION, SALE, TRANSFER_OUT). ADJUSTMENT can be either sign.

Flyway migration: `V4__inventory_create_tables.sql`

---

## Package Structure

```
walmal-inventory/
  pom.xml

  src/main/java/com/walmal/inventory/
    api/
      InventoryLocationController.java   (@RestController, /api/v1/inventory/locations)
      InventoryStockController.java      (@RestController, /api/v1/inventory/stock)
      InventoryReservationController.java (@RestController, /api/v1/inventory/reservations)
      InventoryMovementController.java   (@RestController, /api/v1/inventory/movements — read-only)
      dto/
        request/
          CreateLocationRequest.java
          ReserveStockRequest.java         (orderId, List<ReservationLineItem>)
          AdjustStockRequest.java          (variantId, locationId, delta, reason)
          TransferStockRequest.java        (variantId, fromLocationId, toLocationId, quantity)
          PosConflictRequest.java          (posSaleId, variantId, locationId, quantity, posSaleTimestamp)
        response/
          LocationResponse.java
          StockLevelResponse.java
          ReservationResponse.java
          MovementResponse.java
          StockAvailabilityResponse.java   (variantId, totalAvailable, locations[])

    domain/
      InventoryLocation.java             (@Entity, table: inventory_locations)
      InventoryStock.java                (@Entity, table: inventory_stock)
      InventoryReservation.java          (@Entity, table: inventory_reservations)
      InventoryMovement.java             (@Entity, table: inventory_movements)
      ReservationStatus.java             (enum: PENDING, CONFIRMED, RELEASED)
      ConflictReason.java                (enum: POS_PRIORITY, BUFFER_EXHAUSTED, CANCELLED, EXPIRED)
      LocationType.java                  (enum: STORE, WAREHOUSE, VIRTUAL)
      MovementType.java                  (enum: RECEIPT, ADJUSTMENT, TRANSFER_OUT, TRANSFER_IN,
                                                RESERVATION, RELEASE, SALE)
      ReservationLineItem.java           (record: variantId UUID, locationId UUID, quantity int)
      event/
        InventoryReservationConfirmedEvent.java   (extends DomainEvent)
        InventoryReservationReleasedEvent.java    (extends DomainEvent)
        InventoryStockLowEvent.java               (extends DomainEvent)
        InventoryStockExhaustedEvent.java         (extends DomainEvent)

    application/
      InventoryReservationService.java   (interface — cross-module: Order)
      InventoryQueryService.java         (interface — cross-module: POS)
      InventoryAdjustmentService.java    (interface — cross-module: Warehouse)
      impl/
        InventoryReservationServiceImpl.java
        InventoryQueryServiceImpl.java
        InventoryAdjustmentServiceImpl.java
        ReservationExpiryJob.java        (@Scheduled — expires PENDING reservations)

    infrastructure/
      InventoryLocationRepository.java   (JpaRepository<InventoryLocation, UUID>)
      InventoryStockRepository.java      (JpaRepository<InventoryStock, UUID>)
      InventoryReservationRepository.java (JpaRepository<InventoryReservation, UUID>)
      InventoryMovementRepository.java   (JpaRepository<InventoryMovement, UUID>)
      listener/
        ProductEventListener.java        (@RabbitListener — product.exchange)

    config/
      InventoryRabbitMQConfig.java       (declares inventory.exchange, queues, bindings,
                                          and product.exchange consumer queue)
      InventoryCacheConfig.java          (cache key constants and TTL values)
      InventoryOpenApiConfig.java        (Springdoc GroupedOpenApi for /inventory/**)

  src/test/java/com/walmal/inventory/
    api/
      InventoryStockControllerTest.java       (@WebMvcTest)
      InventoryReservationControllerTest.java (@WebMvcTest)
      InventoryLocationControllerTest.java    (@WebMvcTest)
    domain/
      ReservationStatusTransitionTest.java
      InventoryStockTest.java
    application/
      InventoryReservationServiceImplTest.java  (Mockito)
      InventoryQueryServiceImplTest.java        (Mockito)
      InventoryAdjustmentServiceImplTest.java   (Mockito)
      PosConflictResolutionTest.java            (Mockito — covers all three conflict branches)
    infrastructure/
      InventoryStockRepositoryTest.java   (@DataJpaTest, Testcontainers)
      InventoryIntegrationTest.java       (@SpringBootTest, Testcontainers)
```

---

## Domain Model Detail

### InventoryLocation (@Entity, table: inventory_locations)

```java
@Entity
@Table(name = "inventory_locations",
       uniqueConstraints = @UniqueConstraint(columnNames = "external_reference_id"))
public class InventoryLocation extends BaseEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", nullable = false, length = 30)
    private LocationType locationType;

    @Column(name = "is_buffer_location", nullable = false)
    private boolean bufferLocation = false;

    @Column(name = "external_reference_id")
    private UUID externalReferenceId;    // no @ManyToOne — plain UUID, cross-module ref

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
```

`externalReferenceId` has no `@ManyToOne` declaration. The UNIQUE DB constraint is
declared at the class level via `@Table(uniqueConstraints = ...)`. No FK to any other
module's table is ever declared on this entity.

### InventoryStock (@Entity, table: inventory_stock)

```java
@Entity
@Table(name = "inventory_stock",
       uniqueConstraints = @UniqueConstraint(columnNames = {"variant_id", "location_id"}))
public class InventoryStock extends BaseEntity {

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;              // plain UUID — no @ManyToOne to product module

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private InventoryLocation location;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity = 0;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity = 0;

    @Column(name = "low_stock_threshold", nullable = false)
    private int lowStockThreshold = 10;

    @Version
    @Column(name = "version", nullable = false)
    private long version = 0;           // optimistic locking — Hibernate manages this

    // Domain guard methods — business logic lives here, not in the service
    public void reserve(int quantity) {
        if (this.availableQuantity < quantity) {
            throw new BusinessRuleException(
                "Insufficient stock: available=" + availableQuantity + ", requested=" + quantity);
        }
        this.availableQuantity -= quantity;
        this.reservedQuantity += quantity;
    }

    public void confirm(int quantity) {
        if (this.reservedQuantity < quantity) {
            throw new BusinessRuleException(
                "Cannot confirm: reserved=" + reservedQuantity + ", confirming=" + quantity);
        }
        this.reservedQuantity -= quantity;
        // available_quantity does not increase — stock has left the system
    }

    public void release(int quantity) {
        if (this.reservedQuantity < quantity) {
            throw new BusinessRuleException(
                "Cannot release: reserved=" + reservedQuantity + ", releasing=" + quantity);
        }
        this.reservedQuantity -= quantity;
        this.availableQuantity += quantity;
    }

    public boolean isBelowLowStockThreshold() {
        return this.availableQuantity <= this.lowStockThreshold && this.availableQuantity > 0;
    }

    public boolean isExhausted() {
        return this.availableQuantity == 0;
    }
}
```

The `@Version` field is the sole mechanism for optimistic locking on this entity.
Hibernate increments it automatically on every `save()` and checks the loaded value
matches the current DB value before issuing the UPDATE. No manual version management
is performed in service code.

### InventoryReservation (@Entity, table: inventory_reservations)

```java
@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private UUID orderId;                // plain UUID — no FK to order module

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;             // plain UUID — no FK to product module

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private InventoryLocation location;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status = ReservationStatus.PENDING;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_reason", length = 30)
    private ConflictReason conflictReason;   // null until RELEASED

    // Guard — status machine enforced in entity
    public void confirm() {
        if (this.status != ReservationStatus.PENDING) {
            throw new BusinessRuleException("Only PENDING reservations can be confirmed");
        }
        this.status = ReservationStatus.CONFIRMED;
    }

    public void release(ConflictReason reason) {
        if (this.status == ReservationStatus.RELEASED) {
            throw new BusinessRuleException("Reservation is already RELEASED");
        }
        this.status = ReservationStatus.RELEASED;
        this.conflictReason = reason;
    }
}
```

### InventoryMovement (@Entity, table: inventory_movements)

```java
@Entity
@Table(name = "inventory_movements")
public class InventoryMovement extends BaseEntity {

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private InventoryLocation location;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 20)
    private MovementType movementType;

    @Column(name = "quantity_delta", nullable = false)
    private int quantityDelta;           // signed: positive=inflow, negative=outflow

    @Column(name = "reference_id")
    private UUID referenceId;            // nullable — links to reservation_id, transfer_id, etc.

    @Column(name = "performed_by", nullable = false, length = 100)
    private String performedBy;

    // createdAt from BaseEntity
    // NO updated_at — this entity is insert-only
}
```

`InventoryMovement` has no `@PreUpdate` logic and no service ever calls `save()` on an
existing `InventoryMovement` instance. It is append-only. The `InventoryMovementRepository`
exposes only `save()` and read methods — no `delete()` is exposed or called.

---

## Service Interfaces with Full Method Signatures

### InventoryReservationService (cross-module: consumed by Order)

ISP rationale: Order needs to reserve, confirm, release, and resolve POS conflicts.
It does not need to read raw stock levels or post adjustments.

```java
package com.walmal.inventory.application;

import com.walmal.inventory.domain.ConflictReason;
import com.walmal.inventory.domain.ReservationLineItem;

import java.util.List;
import java.util.UUID;

public interface InventoryReservationService {

    /**
     * Reserves stock for all line items in a single atomic operation.
     * Validates that each variant is active via ProductCatalogService.isVariantActive()
     * before any stock is touched.
     *
     * For each line item: decrements available_quantity, increments reserved_quantity
     * on inventory_stock. Creates an inventory_reservations row with status=PENDING.
     * Writes an inventory_movements row of type RESERVATION for each line.
     *
     * Publishes inventory.stock.low if available_quantity falls to or below
     * low_stock_threshold after reservation.
     * Publishes inventory.stock.exhausted if available_quantity reaches 0.
     *
     * On OptimisticLockException: retries up to 3 times with back-off.
     * After 3 failures: throws ConcurrencyConflictException.
     *
     * @param orderId   the Order module's order UUID
     * @param items     list of (variantId, locationId, quantity) tuples
     * @throws BusinessRuleException        if any variant is inactive or stock is insufficient
     * @throws ConcurrencyConflictException if optimistic lock fails after 3 retries
     */
    void reserveStock(UUID orderId, List<ReservationLineItem> items);

    /**
     * Confirms all PENDING reservations for the given order (called on payment success).
     * Transitions each reservation to CONFIRMED status.
     * Decrements reserved_quantity on inventory_stock (stock has left the system).
     * Writes inventory_movements rows of type SALE for each confirmed reservation.
     * Publishes inventory.reservation.confirmed.
     *
     * @param orderId the Order module's order UUID
     * @throws ResourceNotFoundException if no PENDING reservations exist for orderId
     */
    void confirmReservation(UUID orderId);

    /**
     * Releases all PENDING reservations for the given order.
     * Transitions each reservation to RELEASED status and sets conflict_reason.
     * Increments available_quantity, decrements reserved_quantity on inventory_stock.
     * Writes audit_log entry before each stock mutation.
     * Writes inventory_movements rows of type RELEASE for each released reservation.
     * Publishes inventory.reservation.released (includes conflictReason in payload).
     *
     * @param orderId        the Order module's order UUID
     * @param conflictReason CANCELLED (user-initiated), POS_PRIORITY, BUFFER_EXHAUSTED, EXPIRED
     * @throws ResourceNotFoundException if no PENDING reservations exist for orderId
     */
    void releaseReservation(UUID orderId, ConflictReason conflictReason);

    /**
     * Resolves a POS offline sync conflict for a single variant-location pair.
     * Called by POS module when it synchronises an offline sale.
     *
     * Algorithm (see POS Conflict Resolution section for full detail):
     * 1. If posSaleTimestamp < web reservation createdAt: POS wins.
     *    Release web reservation with POS_PRIORITY.
     * 2. Else if available stock >= posQuantity: deduct stock directly (SALE movement).
     * 3. Else: check buffer locations.
     *    a. If buffer stock >= remaining delta: deduct from buffer (SALE movement).
     *    b. Else: release web reservation with BUFFER_EXHAUSTED.
     *
     * Writes audit_log before any destructive stock mutation.
     * Publishes inventory.reservation.released if a web reservation is cancelled.
     * Publishes inventory.stock.low or inventory.stock.exhausted as appropriate.
     *
     * @param posSaleId         UUID of the POS sale record (for reference_id in movements)
     * @param variantId         the variant being sold
     * @param locationId        the POS store's inventory location
     * @param quantity          units sold offline
     * @param posSaleTimestamp  when the POS sale was recorded (offline device clock)
     * @param webOrderId        the conflicting web order UUID (nullable — null if no conflict)
     */
    void resolveConflict(UUID posSaleId, UUID variantId, UUID locationId,
                         int quantity, Instant posSaleTimestamp, UUID webOrderId);
}
```

### InventoryQueryService (cross-module: consumed by POS)

ISP rationale: POS needs real-time stock reads for its barcode scan flow. It does not
need to post adjustments or manage reservations. All methods are read-only and cacheable.

```java
package com.walmal.inventory.application;

import com.walmal.inventory.api.dto.response.StockAvailabilityResponse;
import com.walmal.inventory.api.dto.response.StockLevelResponse;

import java.util.UUID;

public interface InventoryQueryService {

    /**
     * Returns the stock level for a specific variant at a specific location.
     * Result is cached at key inventory:stock:{variantId}:{locationId} with TTL 30 seconds.
     * Cache is evicted on every stock mutation for this variant-location pair.
     *
     * @throws ResourceNotFoundException if no stock record exists for the combination
     */
    StockLevelResponse getStockLevel(UUID variantId, UUID locationId);

    /**
     * Returns true if available_quantity >= requested quantity at any active location.
     * Aggregates across all non-buffer locations for the variant.
     * Result is cached at key inventory:availability:{variantId} with TTL 60 seconds.
     * Cache is evicted on every stock mutation for this variantId.
     *
     * @param variantId the variant UUID
     * @param quantity  the units the POS intends to sell
     */
    boolean checkAvailability(UUID variantId, int quantity);

    /**
     * Returns available and reserved quantities aggregated across all active locations
     * for the given variant, including a per-location breakdown.
     * Supports the POS stock-check screen.
     */
    StockAvailabilityResponse getAggregatedAvailability(UUID variantId);
}
```

### InventoryAdjustmentService (cross-module: consumed by Warehouse)

ISP rationale: Warehouse needs to post stock receipts, make corrections, and transfer
stock between locations. It does not need reservation lifecycle management or read-only
stock queries.

```java
package com.walmal.inventory.application;

import java.util.UUID;

public interface InventoryAdjustmentService {

    /**
     * Applies a signed quantity delta to a variant-location stock row.
     * Positive delta: stock inflow (receipt, correction upward).
     * Negative delta: stock outflow (write-off, correction downward).
     *
     * Writes audit_log before applying a negative delta (destructive).
     * Writes an inventory_movements row (RECEIPT for positive, ADJUSTMENT for either sign).
     * Evicts cache keys for this variant and location after mutation.
     * Publishes inventory.stock.low if applicable after positive adjustment (restocking).
     * Publishes inventory.stock.exhausted if available_quantity reaches 0 after negative delta.
     *
     * @param variantId   the variant being adjusted
     * @param locationId  the location being adjusted
     * @param delta       signed integer — negative values decrement available_quantity
     * @param reason      human-readable reason for the adjustment (stored in audit_log)
     * @param performedBy username of the warehouse operator performing the adjustment
     * @throws BusinessRuleException if applying delta would make available_quantity negative
     */
    void adjustStock(UUID variantId, UUID locationId, int delta,
                     String reason, String performedBy);

    /**
     * Transfers stock from one location to another.
     * Atomic: decrements source available_quantity and increments destination
     * available_quantity in a single transaction.
     *
     * Writes audit_log with AuditAction.UPDATE for the source location decrement.
     * Writes two inventory_movements rows: TRANSFER_OUT (source), TRANSFER_IN (destination).
     * Evicts cache for both source and destination variant-location pairs.
     *
     * @param variantId      the variant being transferred
     * @param fromLocationId source location
     * @param toLocationId   destination location
     * @param quantity       units to transfer (must be positive)
     * @param performedBy    username of the warehouse operator
     * @throws BusinessRuleException        if source has insufficient available stock
     * @throws ResourceNotFoundException    if either location does not exist or is inactive
     */
    void transferStock(UUID variantId, UUID fromLocationId, UUID toLocationId,
                       int quantity, String performedBy);

    /**
     * Updates the low stock threshold for a variant at a specific location.
     * The threshold determines when inventory.stock.low is published.
     *
     * @param variantId  the variant
     * @param locationId the location
     * @param threshold  new threshold value (must be >= 0)
     * @param performedBy username authorising the change
     */
    void updateLowStockThreshold(UUID variantId, UUID locationId,
                                 int threshold, String performedBy);
}
```

### Internal: ReservationExpiryJob (same module, not cross-module interface)

```java
// In application/impl/ReservationExpiryJob.java
@Component
public class ReservationExpiryJob {

    /**
     * Runs on a configurable schedule (default: every 60 seconds).
     * Finds all PENDING reservations where expires_at < NOW().
     * For each: calls releaseReservation(orderId, EXPIRED).
     * Uses DistributedLockService to prevent duplicate execution in
     * multi-instance deployments.
     */
    @Scheduled(fixedDelayString = "${inventory.reservation.expiry-job-interval-ms:60000}")
    public void expireStaleReservations() { ... }
}
```

---

## Reservation State Machine

```
                    reserveStock()
STOCK AVAILABLE ─────────────────────────► RESERVED (status=PENDING)
                                               │
                           confirmReservation() │  releaseReservation()
                                               │  (with ConflictReason)
                                               ▼                ▼
                                          CONFIRMED          RELEASED
                                       (stock leaves       (stock returns
                                         system)            to available)
```

State transition rules enforced in `InventoryReservation.confirm()` and `.release()`:
- `confirm()` is only valid from PENDING. Throws `BusinessRuleException` if called on
  CONFIRMED or RELEASED.
- `release()` is only valid from PENDING or CONFIRMED. Throws `BusinessRuleException`
  if called on an already RELEASED reservation.
- CONFIRMED reservations may be released only in exceptional circumstances (e.g. post-payment
  fulfillment failure). The `conflictReason` in that case is `CANCELLED`.

### Stock mutations per transition

| Transition | available_quantity | reserved_quantity |
|---|---|---|
| reserveStock() | -quantity | +quantity |
| confirmReservation() | unchanged | -quantity |
| releaseReservation() | +quantity | -quantity |

---

## POS Conflict Resolution Design

### Trigger

The POS module calls `InventoryReservationService.resolveConflict(...)` during its online
sync sequence. This is a synchronous in-process call — the POS module holds the
interface reference to `InventoryReservationService` (dependency injection). No RabbitMQ
listener is used to trigger conflict resolution; RabbitMQ is used only to publish the
outcome.

### Algorithm

```
resolveConflict(posSaleId, variantId, locationId, quantity, posSaleTimestamp, webOrderId)

1. Look up current inventory_stock for (variantId, locationId).

2. If webOrderId is not null:
   Look up the web reservation row (status=PENDING, order_id=webOrderId, variant_id=variantId).
   If webReservation.createdAt > posSaleTimestamp (POS sale is older):
       → POS wins. Release web reservation: releaseReservation(webOrderId, POS_PRIORITY).
       → Deduct POS sale from now-freed stock using direct UPDATE WHERE clause.
       → Write SALE movement for posSaleId.
       → Publish inventory.reservation.released with conflictReason=POS_PRIORITY.
       → Done.

3. Attempt to deduct quantity from primary (non-buffer) stock at locationId:
   Issue UPDATE inventory_stock
     SET available_quantity = available_quantity - :quantity
   WHERE variant_id = :variantId AND location_id = :locationId
     AND available_quantity >= :quantity
   If rowsUpdated = 1:
       → Write SALE movement for posSaleId at locationId.
       → Check and publish inventory.stock.low / inventory.stock.exhausted.
       → Evict cache.
       → Done.

4. Check buffer locations:
   SELECT * FROM inventory_locations WHERE is_buffer_location = TRUE AND is_active = TRUE
   For each buffer location (ordered by available_quantity DESC):
       Issue UPDATE inventory_stock
         SET available_quantity = available_quantity - :quantity
       WHERE variant_id = :variantId AND location_id = :bufferLocationId
         AND available_quantity >= :quantity
       If rowsUpdated = 1:
           → Write SALE movement for posSaleId at bufferLocationId.
           → Publish events. Evict cache.
           → Done.

5. Buffer exhausted:
   → Write audit_log for the impending release (AuditAction.STATUS_CHANGE).
   → Release web reservation: releaseReservation(webOrderId, BUFFER_EXHAUSTED).
   → Publish inventory.reservation.released with conflictReason=BUFFER_EXHAUSTED.
   → Done. (POS sale is recorded as a debt — handled by warehouse reconciliation,
     out of scope for MVP.)
```

### Buffer location lookup

`findBufferLocations()` is an internal query on `InventoryLocationRepository`:

```java
List<InventoryLocation> findByBufferLocationTrueAndActiveTrue();
```

Buffer locations are tried in descending available-quantity order so the algorithm
preferentially drains the fullest buffer first, preserving smaller buffers for subsequent
conflicts.

### Why direct UPDATE and not optimistic lock here

The POS conflict override is a deliberate forced write. The WHERE clause (`available_quantity >= :quantity`) is the atomicity guard. If the clause matches zero rows the algorithm advances to the
next buffer or the BUFFER_EXHAUSTED path. There is no retry loop for this path; the
WHERE-clause failure is a legitimate signal, not a transient concurrency error.

---

## Concurrency Handling Summary

| Operation | Mechanism | On Failure |
|---|---|---|
| `reserveStock()` | `@Version` optimistic lock on `inventory_stock` | Retry 3x; throw `ConcurrencyConflictException` |
| `confirmReservation()` | No lock needed — single row by reservation ID | N/A |
| `releaseReservation()` | No lock needed — single row by reservation ID | N/A |
| `adjustStock()` | `@Version` optimistic lock | Retry 2x; throw `ConcurrencyConflictException` |
| `transferStock()` | `@Version` optimistic lock on both source and destination (same TX) | Retry 2x; throw `ConcurrencyConflictException` |
| POS conflict override | Direct UPDATE WHERE available_quantity >= :quantity | Advance to buffer or BUFFER_EXHAUSTED |
| `expireStaleReservations()` | `DistributedLockService` (Redis) to prevent multi-instance double-expiry | Skip run if lock not acquired |

---

## RabbitMQ Events

### Exchange declared by this module

`inventory.exchange` (type: direct, durable: true)

### Published by Inventory

| Routing Key | Event Class | Trigger | Downstream Consumers |
|---|---|---|---|
| `inventory.reservation.confirmed` | `InventoryReservationConfirmedEvent` | `confirmReservation()` succeeds | Order module |
| `inventory.reservation.released` | `InventoryReservationReleasedEvent` | `releaseReservation()` for any reason, including EXPIRED | Order module, Notification module |
| `inventory.stock.low` | `InventoryStockLowEvent` | `available_quantity <= low_stock_threshold` after any mutation | Warehouse, Notification |
| `inventory.stock.exhausted` | `InventoryStockExhaustedEvent` | `available_quantity` reaches 0 after any mutation | Order, POS |

`InventoryReservationReleasedEvent` includes `conflictReason` in its payload. Notification
module uses this field to compose the appropriate customer message (e.g. "Your order was
cancelled because a POS terminal completed a sale first").

### Consumed from `product.exchange`

Inventory declares a durable queue `inventory.product-events.queue` bound to
`product.exchange` with the routing keys below.

| Routing Key | Event Class | Inventory Action |
|---|---|---|
| `product.created` | `ProductCreatedEvent` | Create `inventory_stock` row with available_quantity=0 at the default warehouse location. Write `inventory_movements` RECEIPT row with delta=0 to establish the audit trail baseline. |
| `product.deactivated` | `ProductDeactivatedEvent` | Set `available_quantity=0` on all stock rows for this variant. Block new reservations by checking `ProductCatalogService.isVariantActive()` at reservation time — the stock rows are not deleted. Write audit_log before each mutation. |

### Event Classes

```
com.walmal.inventory.domain.event.InventoryReservationConfirmedEvent extends DomainEvent
  Fields: UUID orderId, UUID variantId, UUID locationId, int quantity

com.walmal.inventory.domain.event.InventoryReservationReleasedEvent extends DomainEvent
  Fields: UUID orderId, UUID variantId, UUID locationId,
          int quantity, ConflictReason conflictReason

com.walmal.inventory.domain.event.InventoryStockLowEvent extends DomainEvent
  Fields: UUID variantId, UUID locationId, int availableQuantity, int threshold

com.walmal.inventory.domain.event.InventoryStockExhaustedEvent extends DomainEvent
  Fields: UUID variantId, UUID locationId
```

All events are published via `DomainEventPublisher.publish(event, routingKey)`.
`RabbitTemplate` is never injected into application-layer classes.

---

## Upstream Dependency: ProductCatalogService

`InventoryReservationServiceImpl` injects `ProductCatalogService` (the interface from
`walmal-product`). It is called in `reserveStock()` to validate variant activity:

```java
if (!productCatalogService.isVariantActive(lineItem.variantId())) {
    throw new BusinessRuleException("Variant " + lineItem.variantId() + " is not active");
}
```

This is the only cross-module synchronous dependency the Inventory module has. No other
Product module classes are imported.

`TokenValidationService` is NOT an explicit dependency — JWT is handled globally by
`JwtAuthenticationFilter` in walmal-auth, wired in walmal-app.

---

## Caching Strategy

All caching uses `CacheService` (DIP). `RedisTemplate` is never injected into
application-layer classes.

| Cache Key | Content | TTL | Eviction Trigger |
|---|---|---|---|
| `inventory:stock:{variantId}:{locationId}` | `StockLevelResponse` | 30 seconds | Any stock mutation for this variant-location pair |
| `inventory:availability:{variantId}` | `StockAvailabilityResponse` (aggregate) | 60 seconds | Any stock mutation for this variantId across any location |

Cache-aside pattern: read from cache first; on miss, query the DB and populate the cache.

The 30-second TTL on `inventory:stock:{variantId}:{locationId}` is intentionally short
to balance POS read performance with data freshness. For a POS barcode scan the cached
result is acceptable within this window; the stock mutation eviction ensures the next
read after any write is always fresh.

`inventory:availability:{variantId}` is the aggregate path used by `checkAvailability()`.
It is evicted on every write to any stock row for that variantId, regardless of location.

There is deliberately no long-lived cache for reservation state — reservations change on
every order lifecycle event and caching them would require complex invalidation.

---

## Audit Log Requirements

Per CLAUDE.md: all destructive DB operations write to `audit_log` before execution.
`AuditService.log(AuditEntry)` must be called before the corresponding DB mutation.

`inventory_movements` captures the business event trail. `audit_log` captures the
security and compliance trail. These are complementary and both are required.

| Operation | Method | AuditAction | Table Written | Movement Type | Timing |
|---|---|---|---|---|---|
| Release reservation | `releaseReservation()` | `STATUS_CHANGE` | `inventory_reservations` | RELEASE | Before UPDATE status='RELEASED' |
| POS conflict — release web reservation | `resolveConflict()` | `STATUS_CHANGE` | `inventory_reservations` | RELEASE | Before UPDATE status='RELEASED' |
| Negative stock adjustment | `adjustStock()` (negative delta) | `UPDATE` | `inventory_stock` | ADJUSTMENT | Before UPDATE available_quantity |
| Stock transfer (source deduction) | `transferStock()` | `UPDATE` | `inventory_stock` | TRANSFER_OUT | Before decrement on source |
| Deactivate variant stock | `ProductEventListener` (product.deactivated) | `STATUS_CHANGE` | `inventory_stock` | ADJUSTMENT (delta=0, reason=DEACTIVATED) | Before zeroing available_quantity |

Operations that are not destructive and do not require an audit_log entry:
- `reserveStock()`: increments reserved, decrements available — this is not a destructive
  loss of data; the stock is accounted for in reserved_quantity.
- `confirmReservation()`: stock leaves the system but this is the expected outcome of a
  sale. The `inventory_movements` SALE row is the business audit trail.
- Positive stock adjustments: additive, not destructive.
- Stock reads (all `InventoryQueryService` methods).

The `performed_by` field in `AuditEntry` is the username from `AuthenticatedPrincipal`
passed down from the controller. For the scheduled expiry job, `performed_by` is the
system identity string `"system:reservation-expiry-job"`.

---

## SOLID Compliance

### SRP — One class, one responsibility

| Class | Single Responsibility |
|---|---|
| `InventoryReservationServiceImpl` | Reservation lifecycle: reserve, confirm, release, expire, conflict |
| `InventoryQueryServiceImpl` | Read-only stock queries and cache management |
| `InventoryAdjustmentServiceImpl` | Stock mutations: adjust, transfer, threshold management |
| `ReservationExpiryJob` | Scheduled expiry scan only — delegates to `releaseReservation()` |
| `ProductEventListener` | Translate product RabbitMQ events into inventory initialisation/deactivation |

### DIP — Infrastructure via interfaces only

| Infrastructure | Interface Used | Never Used Directly |
|---|---|---|
| RabbitMQ | `DomainEventPublisher` | `RabbitTemplate` |
| Redis | `CacheService` | `RedisTemplate` |
| Redis distributed lock | `DistributedLockService` | `RedissonClient` or `RedisTemplate` |
| Audit table | `AuditService` | Direct `JdbcTemplate` or `AuditLogRepository` |
| Product module | `ProductCatalogService` (interface) | `ProductVariantRepository` or any product infra class |

### ISP — Interfaces split by consumer

| Interface | Consumer | Scope |
|---|---|---|
| `InventoryReservationService` | Order module | Reserve, confirm, release, resolveConflict |
| `InventoryQueryService` | POS module | Read-only stock level and availability queries |
| `InventoryAdjustmentService` | Warehouse module | Adjust, transfer, threshold update |

Order never sees stock query methods. POS never sees reservation lifecycle methods.
Warehouse never sees reservation management or POS conflict resolution.

### OCP — Extensible conflict resolution

The POS conflict resolution algorithm is implemented as a private method sequence in
`InventoryReservationServiceImpl`. The three outcome branches (POS_PRIORITY, buffer
stock deduction, BUFFER_EXHAUSTED) each end with publishing an event. Adding a fourth
outcome (e.g. a partial fulfillment path) extends the method without modifying existing
branch logic.

The `ConflictReason` enum is the extension point — new reasons are added as new enum
constants. Existing `switch` statements on `ConflictReason` in the Notification module
will need to handle the new constant, but the Inventory module's own logic does not change.

### LSP — No subtype violations

No inheritance hierarchies exist in this module other than `extends BaseEntity`
(`@MappedSuperclass`). No subtypes throw `UnsupportedOperationException`.

---

## Auth Integration

The Inventory module does not import any class from walmal-auth.

- JWT validation is handled globally by `JwtAuthenticationFilter` (walmal-auth, wired
  in walmal-app). Inventory controllers receive an authenticated request automatically.
- Controllers use `@AuthenticationPrincipal AuthenticatedPrincipal principal` to obtain
  `principal.username()` for the `performedBy` field in `AuditEntry` and
  `InventoryMovement`.
- `AuthenticatedPrincipal` is defined in walmal-common — safe to import.
- Role-based access:
  - `InventoryStockController`: `ADMIN` and `WAREHOUSE_MANAGER` may read; `WAREHOUSE_MANAGER`
    may post adjustments.
  - `InventoryReservationController`: `ORDER_SERVICE` role (internal service account) only.
  - `InventoryLocationController`: `ADMIN` may create and update locations.

---

## Maven Dependencies

walmal-inventory depends on:
- `walmal-common` (DIP interfaces: `CacheService`, `DomainEventPublisher`, `AuditService`,
  `DistributedLockService`; `BaseEntity`, `AuthenticatedPrincipal`, exception types)
- `walmal-product` (for `ProductCatalogService` interface import only — no Repository beans)
- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-amqp`
- `spring-boot-starter-quartz` or `spring-boot-starter` (for `@Scheduled`)
- `springdoc-openapi-starter-webmvc-ui`
- `spring-boot-starter-test` + `testcontainers:postgresql` + `testcontainers:rabbitmq` (test scope)

walmal-infrastructure is NOT a direct Maven dependency. walmal-app wires the concrete
`CacheService`, `DomainEventPublisher`, `AuditService`, and `DistributedLockService`
implementations at runtime.

`walmal-inventory` must be added to the parent `pom.xml` `<modules>` section and to
`walmal-app`'s `<dependencies>` by the module-builder.

---

## Consequences

### Positive

- Module boundary is clean: Order, POS, and Warehouse reference stock via service
  interfaces. No module queries `inventory_*` tables directly.
- `UNIQUE (variant_id, location_id)` on `inventory_stock` is a DB-level invariant —
  duplicate stock rows for the same variant-location pair are impossible regardless of
  concurrent inserts.
- `@Version` optimistic locking on `inventory_stock` ensures that a concurrent
  reservation on the last available unit will fail and retry rather than silently
  overselling.
- The POS conflict resolution algorithm is deterministic and fully documented in code.
  The `conflict_reason` field on `inventory_reservations` provides a persistent audit
  trail of every forced release.
- `inventory_movements` as an insert-only log gives operations a complete stock movement
  history without touching `audit_log`, keeping the two trails orthogonal.

### Negative / Risks

- Optimistic lock retry adds latency on contested reservations. Mitigation: 3 retries
  with 50ms/100ms/200ms back-off is acceptable for order creation; the POS read path
  (`InventoryQueryService`) never writes and is unaffected.
- The expiry job is a polling mechanism — reservations expire with up to one polling
  interval of lag (default 60 seconds). Mitigation: `expires_at` is set conservatively
  by Order module (e.g. 30 minutes for web orders). The lag is not user-visible.
- `resolveConflict()` may be called in a high-volume POS sync burst after a long offline
  period. Mitigation: the Warehouse reconciliation process (Step 7) is designed to handle
  bulk sync; `resolveConflict()` is per-item. The `DistributedLockService` prevents
  duplicate processing of the same `posSaleId`.
- No price information in inventory — the module stores quantities only. This is correct
  by design: price is owned by walmal-product.

---

## Definition of Done Checklist

- [ ] `InventoryReservationService` interface defined in `application/`
- [ ] `InventoryQueryService` interface defined in `application/`
- [ ] `InventoryAdjustmentService` interface defined in `application/`
- [ ] All three implementations complete in `application/impl/`
- [ ] `ReservationExpiryJob` implemented and wired with `@Scheduled`
- [ ] All four JPA entities mapped to `inventory_*` tables
- [ ] `@Version` present on `InventoryStock.version`
- [ ] `InventoryMovement` has no UPDATE path — insert-only enforced in repository usage
- [ ] `V4__inventory_create_tables.sql` Flyway migration applied
- [ ] Four controllers complete with OpenAPI annotations
- [ ] `InventoryRabbitMQConfig` declares `inventory.exchange`, four routing keys (published),
      and the `inventory.product-events.queue` consumer binding to `product.exchange`
- [ ] `ProductEventListener` handles `product.created` and `product.deactivated`
- [ ] All four domain events published via `DomainEventPublisher` only
- [ ] `AuditService.log()` called before every destructive operation (5 operations listed above)
- [ ] `CacheService` used for all cache reads/writes — `RedisTemplate` not in application layer
- [ ] `DistributedLockService` used in `ReservationExpiryJob` to prevent double-expiry
- [ ] `ProductCatalogService.isVariantActive()` called in `reserveStock()` before any stock touch
- [ ] No `ProductVariantRepository` or any product infrastructure bean imported
- [ ] No cross-module `inventory_*` Repository beans imported by Order, POS, or Warehouse
- [ ] POS conflict resolution all three branches covered by unit tests
- [ ] Integration tests pass (`@SpringBootTest` + Testcontainers PostgreSQL + RabbitMQ)
- [ ] `@WebMvcTest` tests for all four controllers
- [ ] Docker Compose health check passes with inventory endpoints reachable
- [ ] `walmal-inventory` added to parent `pom.xml` `<modules>` and `walmal-app` `<dependencies>`
