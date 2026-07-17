# ADR-6: POS Module Design

**Date**: 2026-05-21
**Status**: Accepted
**Module**: walmal-pos (Build Order Step 6 of 9)
**Authors**: Backend Architect Agent

---

## Context

The POS module bridges the physical retail channel with the walmal platform. A POS
terminal operates in one of two modes:

- **Online mode**: the terminal has network access. Barcode scans resolve against the
  Product module in real time, stock is checked against Inventory, and a completed
  sale is persisted by the Order module exactly as a web order — but tagged as an
  in-store transaction.
- **Offline mode**: network is absent. The terminal records sales locally and queues
  them. When connectivity returns, the terminal POSTs a batch of offline sale payloads
  to `POST /api/v1/pos/sync`. The POS module processes each payload sequentially,
  creates POS sale and sale-item records, and calls the Inventory module's conflict
  resolution routine for each line item. This is the most architecturally complex flow
  in the walmal platform.

Upstream dependencies: Product (variant lookup, pricing), Inventory (stock check,
conflict resolution), Order (online sale creation and optional receipt display).
Downstream consumers of POS events: Notification module (binds `pos.sale.completed`,
`pos.sale.synced`, `pos.sync.conflict.resolved` to trigger customer or operator alerts).

The module's most critical design decisions are:
1. Transaction boundary for `recordOnlineSale()` — what happens when the `pos_sale`
   INSERT fails after `OrderCreationService.createOrder()` has already committed.
2. Transaction boundary for `submitOfflineSync()` — single monolithic transaction vs.
   per-item transactions and the failure semantics of each.
3. ISP review of `PosSaleService` — whether online recording and read queries belong
   on the same interface.
4. `pos_sync_queue.sale_data JSONB` — flexible opaque payload vs. structured columns,
   and the trade-off for MVP.

This ADR records the decisions for all four questions plus the full domain model,
service interface design, sync algorithm, event contracts, audit log requirements, and
the `pos-sync-specialist` delegation boundary.

---

## Decision Drivers

1. Module boundary integrity: Product, Inventory, Order, and Auth are referenced by
   UUID only. No cross-module JOINs in business logic. No Repository bean from any
   upstream module may be injected into any POS class.
2. ISP compliance: Three public service interfaces are defined, each consumed by
   exactly one consumer group (API layer). No consumer sees methods it does not need.
3. DIP compliance: `DomainEventPublisher`, `CacheService`, and `AuditService` are the
   only infrastructure paths permitted in the `application/` layer. `RabbitTemplate`
   and `RedisTemplate` must never appear there.
4. Offline sync correctness: The conflict resolution algorithm in the Inventory module
   (`resolveConflict()`) is the authoritative decision point. The POS module's
   responsibility is to prepare the inputs correctly and record the outcome faithfully.
5. Audit compliance: `deactivateTerminal()` and any FAILED status mutation on
   `pos_sync_queue` are destructive operations. Both must write to `audit_log` before
   the DB mutation executes, per CLAUDE.md.
6. POS specialist boundary: `pos-sync-specialist` owns the sync algorithm specification.
   This ADR documents the algorithm's integration points — the sequencing of calls and
   the recording of outcomes — but defers algorithm internals to the specialist agent.
7. Flyway migration number: V6 (follows V1 infrastructure, V2 auth, V3 product,
   V4 inventory, V5 order).

---

## Considered Options

### Flag 1: Transaction boundary for `recordOnlineSale()` — the orphaned order risk

**The problem**

`recordOnlineSale()` calls `OrderCreationService.createOrder()` first, then inserts
a `pos_sales` row. `OrderCreationService.createOrder()` is itself `@Transactional` and
commits its own transaction (it includes a payment step whose outcome must be persisted
before control returns). By the time `createOrder()` returns the `orderId`, the order
row in `order_orders` already exists in the database. If the subsequent `pos_sales`
INSERT fails (e.g. a constraint violation on the POS side), the order exists without a
corresponding POS sale record. The POS terminal has no knowledge of the orphaned order.

**Option A: Make `recordOnlineSale()` @Transactional — wraps only the POS INSERT**

`@Transactional` on `recordOnlineSale()` covers only the POS module's own INSERTs
(`pos_sales`, `pos_sale_items`). The call to `OrderCreationService.createOrder()`
happens inside this transaction but in a separate nested transactional context (Spring
`REQUIRES_NEW` or default `REQUIRED`, depending on the Order module's propagation).
Because `createOrder()` commits its own transaction before returning, the POS
`@Transactional` cannot roll it back. Rolling back the POS transaction does not undo
the committed order row.

This is the same result as Option B — the annotation provides no cross-module
rollback capability and conveys false safety to the reader.

**Option B: Accept the distributed write gap — record the orphaned order as a known
risk, mitigate with compensation [SELECTED]**

The Order module and the POS module are independent transactional contexts. No JTA
global transaction manager is in scope for MVP. Cross-module rollback is not achievable
without a saga pattern or outbox, which are post-MVP concerns.

Decision: mark `recordOnlineSale()` with `@Transactional` for the POS INSERTs only
(ensuring `pos_sales` and `pos_sale_items` are atomically inserted together). The call
to `createOrder()` is made before the POS INSERT block and its outcome is captured in
a local variable.

Mitigation for the orphaned order case:
- If the POS INSERT fails after `createOrder()` returns, the order exists as a CONFIRMED
  or PENDING row in `order_orders` with no matching `pos_sales` record. The POS
  terminal receives an HTTP 5xx response and must retry.
- On retry, the terminal calls `recordOnlineSale()` again. This must not create a
  duplicate order. Mitigation: the POS controller accepts an idempotency key header
  (`X-Idempotency-Key`) per terminal session per sale. Before calling `createOrder()`,
  `PosSaleServiceImpl` checks `CacheService` for a cached `{idempotencyKey} → saleId`
  mapping (TTL 24 hours). If present, the cached sale ID is returned immediately.
- If the POS INSERT failed on the first attempt and the cache entry was not written
  (because the INSERT happened after the cache write), an operator reconciliation
  query against `order_orders.shipping_address LIKE '%POS IN-STORE%'` plus no matching
  `pos_sales.online_order_id` identifies orphaned orders. This is an MVP operational
  concern, documented in runbooks.
- A future ADR can introduce an outbox table to make this atomic.

`@Transactional` annotation placement: on `PosSaleServiceImpl.recordOnlineSale()`, with
`propagation = REQUIRED`. The POS INSERT block is the critical section. The Order call
precedes the transaction boundary; if it throws, no POS state is written.

**Alternative considered: call `createOrder()` inside the POS transaction**

If `OrderCreationService.createOrder()` uses `propagation = REQUIRED` (default), calling
it from within a POS `@Transactional` method would enlist it in the same transaction —
but only if they share the same `EntityManager` / `PlatformTransactionManager`. In a
modular monolith with a single datasource this is technically true. However, relying on
this coupling is an architecture violation: it creates an implicit dependency on the
Order module's transactional propagation setting, which the POS module does not own and
cannot control. This option is rejected. The explicit separation — Order call first,
then POS INSERT in its own `@Transactional` — preserves module independence.

---

### Flag 2: Transaction boundary for `submitOfflineSync()` — per-item vs. single transaction

**Option A: Single `@Transactional` wrapping the entire sync batch**

All `pos_sync_queue` row creations, all `pos_sale` + `pos_sale_items` INSERTs, all
`resolveConflict()` calls, and all status updates happen in one transaction.

Consequence: if item 47 of a 100-item batch fails (e.g. a constraint violation or a
`resolveConflict()` exception), the entire batch rolls back. All 46 successfully
processed items are lost. The terminal must resubmit the full batch. This is
unacceptable for large batches and violates the principle that a sync operation should
be maximally idempotent.

Rejected.

**Option B: Per-item transactions [SELECTED]**

Each offline sale payload is processed in its own `@Transactional` method (extracted
to a helper with `propagation = REQUIRES_NEW` to ensure a new transaction for each item,
even when called from within the outer non-transactional `submitOfflineSync()` method).
`submitOfflineSync()` itself is NOT annotated `@Transactional`.

Sequence for each item:
1. Persist `pos_sync_queue` row (status=PENDING) — in the per-item transaction.
2. Create `pos_sale` (sale_mode=OFFLINE, sync_status=PENDING) and `pos_sale_items` — same per-item transaction.
3. For each line item call `resolveConflict()` — this is a cross-module call that commits its own transaction (same single-datasource enlisted context).
4. Update `pos_sale.sync_status` → SYNCED or CONFLICT_RESOLVED — same per-item transaction.
5. Update `pos_sync_queue.status` → PROCESSED — same per-item transaction.
6. Publish events via `DomainEventPublisher`.

If step 3 throws for one line item, the per-item transaction rolls back: `pos_sale`,
`pos_sale_items`, and the `pos_sync_queue` PENDING row for that item are rolled back. The
`pos_sync_queue` row reverts to not-yet-persisted. The failure is recorded in the
`SyncResultDto` returned to the terminal (field: `failedItems`, containing the raw
payload and the failure reason). The terminal operator can resubmit failed items.

If the `pos_sync_queue` persisted row should survive a processing failure (for
operational visibility), the sequence can be split further: persist the queue row in
a separate `REQUIRES_NEW` transaction first, then attempt processing in a second
`REQUIRES_NEW` transaction. On failure, update `pos_sync_queue.status=FAILED` and set
`failure_reason`. This two-phase approach is the selected design:

**Two-phase per-item processing:**
- Phase 1 (REQUIRES_NEW): Persist `pos_sync_queue` row (status=PENDING). This always
  commits, giving operators visibility into what was submitted.
- Phase 2 (REQUIRES_NEW): Create `pos_sale`, `pos_sale_items`, call `resolveConflict()`,
  update `pos_sale.sync_status`, update `pos_sync_queue.status=PROCESSED`.
  On exception: update `pos_sync_queue.status=FAILED, failure_reason=...` (in a third
  REQUIRES_NEW on the catch path — because Phase 2 rolled back).

Audit log note: setting `pos_sync_queue.status=FAILED` is a destructive mutation (it
permanently marks a sync as unprocessable). `AuditService.log()` must be called before
this UPDATE.

`pos_sync_queue` rows are NEVER hard-deleted. Failed rows remain with `status=FAILED`
and `failure_reason` populated. This satisfies the audit trail requirement and allows
operator resubmission or manual resolution.

**Batch idempotency (implemented 2026-07-17):** offline queues are retry-prone —
a terminal that loses connectivity mid-response resubmits the whole batch. The
device-generated `localId` is now a first-class column on `pos_sync_queue`
(migration V16), and `submitOfflineSync()` skips any payload whose `localId`
this terminal has already PROCESSED (counting it as an idempotent `synced`, not
reprocessing it — so stock is never double-decremented and a conflict is never
re-resolved). The partial unique index `ux_pos_sync_processed_local_id` on
`(terminal_id, local_id) WHERE status = 'PROCESSED'` enforces at-most-once
processing at the DB layer as a concurrency backstop. This closes the gap
between this ADR's "maximally idempotent" intent and the earlier
implementation, which echoed `localId` into logs only and reprocessed every
resubmitted payload.

The known MVP limitation (large batches risk HTTP timeout) is acknowledged. No background
job is introduced for MVP. The HTTP timeout risk is mitigated by:
- Setting a generous read timeout on the sync endpoint (configurable,
  `pos.sync.request-timeout-ms`, default 120000ms).
- Returning a `SyncResultDto` that includes a count of processed, conflicted, and failed
  items — allowing partial success to be surfaced to the terminal even if some items
  failed.
- Documenting in the API contract that large batches (> 100 items) are not supported in
  MVP. The OpenAPI spec for `POST /api/v1/pos/sync` includes a `maxItems: 100`
  constraint in the request body description.

---

### Flag 3: ISP review — is `PosSaleService` doing too much?

`PosSaleService` as initially defined exposes:
- `recordOnlineSale()` — a write operation that calls Order, Inventory, and Product modules.
- `getSale()` — a read by primary key.
- `listSalesByTerminal()` — a paginated read query.

**The ISP concern**: the API layer is the only consumer of all three. No other module
calls `PosSaleService`. The interface groups a write operation and two read operations
on the same entity. Is this an SRP/ISP violation?

**Analysis**: ISP applies at the module boundary — interfaces are segregated by
consumer, not by operation type. Since the API layer is the sole consumer of all three
methods, splitting them into `PosSaleWriteService` and `PosSaleQueryService` would
create two interfaces consumed by the same consumer class (`PosSaleController`). This
adds classes without adding value. The split is only warranted if a second consumer
exists that needs only the read methods.

**Decision: keep `PosSaleService` as a unified interface for MVP.**

The read methods (`getSale()`, `listSalesByTerminal()`) are lightweight projections and
belong on the same interface as the write method for the same reason `OrderQueryService`
and read methods coexist in prior modules: there is one consumer, and the methods are
cohesive around the `PosSale` entity. If a second consumer (e.g. a Reporting module)
is introduced post-MVP, the read methods should be extracted to a `PosSaleQueryService`
at that point.

SRP note on the implementation: `PosSaleServiceImpl` has one primary responsibility
("manage POS sale lifecycle"). The online sale flow's multi-step coordination is
comparable to `OrderCreationServiceImpl`'s orchestration of Product + Inventory +
Payment — it is a valid single responsibility (coordinating the online sale workflow).

---

### Flag 4: `pos_sync_queue.sale_data JSONB` vs. structured columns

**Option A: Structured columns in `pos_sync_queue`**

Each field of the offline payload (terminal_id, sold_at, sale_mode, items array, etc.)
has its own column in `pos_sync_queue`. The items array would require a sibling table
(`pos_sync_queue_items`).

Pros: standard relational model; queries on individual fields are straightforward;
constraint enforcement at column level.

Cons: the `pos_sync_queue` table is a transient buffer — rows are either PROCESSED or
FAILED and are never joined in business queries. Structuring the raw payload adds schema
complexity for a table whose purpose is to hold the payload until it is processed. Any
change to the payload format (new device firmware adds a field) requires a migration.

**Option B: JSONB opaque payload [SELECTED]**

The raw offline payload from the device is stored as JSONB in `sale_data`. The
`PosSyncServiceImpl` deserialises it into `OfflineSalePayload` (a Java record) using
`ObjectMapper`. After processing, the payload is no longer queried by the application
— the `pos_sale` and `pos_sale_items` rows are the canonical records.

Pros:
- Device payload evolution (new fields added by firmware updates) requires no migration.
- The queue table is slim: status columns + the opaque blob. Operator tooling can
  inspect the raw payload directly in the DB for debugging.
- JSONB allows future indexed queries on specific payload fields (e.g. `sale_data->>'terminalId'`)
  without a schema migration.

Cons:
- No column-level constraints on payload fields. Validation is entirely in application
  code (`OfflineSalePayload` deserialization + bean validation on the record fields).
- If the device sends a malformed payload, the FAILED row's `failure_reason` must
  carry enough detail for operator resolution. The `ObjectMapper` deserialization
  error message is written to `failure_reason`.

**Decision**: JSONB for `sale_data`. The table is a transient buffer, not a query
target. Application-layer validation (`@Valid` on `OfflineSalePayload` + explicit
field checks in `PosSyncServiceImpl`) provides the constraint enforcement that column
definitions would otherwise give.

**Validation rule**: `OfflineSalePayload` must pass `@Valid` bean validation before
Phase 1 (queue persistence) proceeds. If validation fails, the item is rejected with
a validation error in `SyncResultDto.failedItems` and no queue row is written.

---

## Decision

### Module Identity

- **Base package**: `com.walmal.pos`
- **API base path**: `/api/v1/pos`
- **Flyway migration**: `V6__pos_create_tables.sql`
- **RabbitMQ exchange**: `pos.exchange` (type: direct, durable: true)
- **Build order position**: Step 6 of 9

---

### Owned Tables

All four tables are owned exclusively by `walmal-pos`. No other module may JOIN against
them or inject any of their Repository beans.

#### `pos_terminals`

```
id           UUID         PRIMARY KEY DEFAULT gen_random_uuid()
name         VARCHAR(200) NOT NULL
location_id  UUID         NOT NULL
             -- cross-module ref to inventory_locations.id — NO FK declared
status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
             CHECK (status IN ('ACTIVE', 'INACTIVE'))
last_seen_at TIMESTAMPTZ
created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()

INDEX (status)
INDEX (location_id)
```

`location_id` is a plain UUID column. No `REFERENCES inventory_locations(id)` is
declared. Cross-module FK constraints violate the module boundary rule. The Inventory
module owns `inventory_locations` and its data lifecycle. POS holds the UUID as an
opaque reference.

`last_seen_at` is updated by the POS module each time a terminal successfully
completes a sync or an online sale. It is not updated during offline operation.

#### `pos_sales`

```
id               UUID          PRIMARY KEY DEFAULT gen_random_uuid()
terminal_id      UUID          NOT NULL REFERENCES pos_terminals(id)
                 -- intra-module FK — both tables owned by POS
online_order_id  UUID
                 -- nullable cross-module ref to order_orders.id — NO FK declared
sold_at          TIMESTAMPTZ   NOT NULL
                 -- device clock for offline; server clock for online
total_amount     NUMERIC(12,2) NOT NULL CHECK (total_amount >= 0)
currency         VARCHAR(3)    NOT NULL
sale_mode        VARCHAR(10)   NOT NULL CHECK (sale_mode IN ('ONLINE', 'OFFLINE'))
sync_status      VARCHAR(25)   NOT NULL DEFAULT 'N_A'
                 CHECK (sync_status IN ('N_A', 'PENDING', 'SYNCED',
                                        'CONFLICT_RESOLVED', 'FAILED'))
cashier_id       UUID
                 -- nullable cross-module ref to auth_users.id — NO FK declared
created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()

INDEX (terminal_id)
INDEX (sale_mode, sync_status)
INDEX (sold_at)
INDEX (cashier_id) WHERE cashier_id IS NOT NULL
```

`sync_status = 'N_A'` is the value for online sales — they are never in a sync queue.
`online_order_id` is null for offline sales; populated for online sales with the UUID
returned by `OrderCreationService.createOrder()`.

`cashier_id` is nullable to allow anonymous or system-generated sales in future. For
MVP, it is always the authenticated `AuthenticatedPrincipal.userId()` passed in from the
POS controller.

#### `pos_sale_items`

```
id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid()
sale_id               UUID          NOT NULL REFERENCES pos_sales(id)
                      -- intra-module FK — both tables owned by POS
variant_id            UUID          NOT NULL
                      -- cross-module ref to product_variants.id — NO FK declared
product_name_snapshot VARCHAR(500)  NOT NULL
sku_snapshot          VARCHAR(100)  NOT NULL
quantity              INT           NOT NULL CHECK (quantity > 0)
price_at_sale         NUMERIC(12,2) NOT NULL CHECK (price_at_sale >= 0)
currency              VARCHAR(3)    NOT NULL
subtotal              NUMERIC(12,2) NOT NULL CHECK (subtotal >= 0)
location_id           UUID          NOT NULL
                      -- cross-module ref to inventory_locations.id — NO FK declared
created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW()

INDEX (sale_id)
INDEX (variant_id)
```

`pos_sale_items` has no `updated_at` — it is insert-only. No service ever calls `save()`
on an existing `PosServiceItem` entity. The snapshot fields (`product_name_snapshot`,
`sku_snapshot`, `price_at_sale`) are copied from the Product module at sale time and are
immutable thereafter. `subtotal` = `quantity * price_at_sale`, computed by the service
before INSERT and stored for denormalized display performance.

`location_id` records the inventory location from which the item was deducted (or
attempted to be deducted for offline sales). It is the POS terminal's store location
for all items in the same sale. Stored per-item to preserve the record if location
assignments change after the sale.

#### `pos_sync_queue`

```
id             UUID        PRIMARY KEY DEFAULT gen_random_uuid()
terminal_id    UUID        NOT NULL REFERENCES pos_terminals(id)
               -- intra-module FK — both tables owned by POS
sale_data      JSONB       NOT NULL
               -- raw offline payload from device; deserialized to OfflineSalePayload
submitted_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
processed_at   TIMESTAMPTZ           -- null until PROCESSED
status         VARCHAR(20) NOT NULL DEFAULT 'PENDING'
               CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED'))
failure_reason TEXT                  -- null unless FAILED
created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()

INDEX (terminal_id, status)
INDEX (status, submitted_at)
```

Rows in `pos_sync_queue` are NEVER hard-deleted. PROCESSED rows remain as an audit
trail. FAILED rows remain for operator investigation and manual resubmission.

The `(status, submitted_at)` index supports operator queries for aged FAILED items
and for dashboard displays of sync health.

---

### Cross-Module UUID References (no FK on any of these)

| Column | References | Rationale |
|---|---|---|
| `pos_terminals.location_id` | `inventory_locations.id` | Inventory owns its locations; POS holds the UUID as an opaque reference |
| `pos_sales.online_order_id` | `order_orders.id` | Order owns its rows; nullable link for receipt display only |
| `pos_sales.cashier_id` | `auth_users.id` | Auth owns its users; POS stores for audit display |
| `pos_sale_items.variant_id` | `product_variants.id` | Product owns variants; POS stores for snapshot and movement tracking |
| `pos_sale_items.location_id` | `inventory_locations.id` | Same as terminal location_id above |

---

### POS IN-STORE Shipping Address Sentinel

Online POS sales passed to `OrderCreationService.createOrder()` require a `ShippingAddress`.
For in-store transactions, physical delivery is not applicable. A sentinel value is used:

```
line1      = "POS IN-STORE"
line2      = null
city       = PosTerminal.name  (the store name as registered in pos_terminals)
country    = "SG"              (configurable via pos.instore-sentinel.country, default "SG")
postalCode = "000000"
```

This sentinel allows:
- `order_orders.shipping_address` to satisfy its NOT NULL constraint.
- Operator queries to identify in-store orders by `shipping_address->>'line1' = 'POS IN-STORE'`.
- Orphaned order detection: orders with `shipping_address->>'line1' = 'POS IN-STORE'`
  and no matching `pos_sales.online_order_id` row indicate a failed POS INSERT after
  a successful `createOrder()` call.

The sentinel country default is `"SG"`. The value is configurable via
`@Value("${pos.instore-sentinel.country:SG}")` in `PosConfig`. This is not hard-coded
in the service implementation; the service receives it through constructor injection.

---

### Online POS Sale Flow

Sequence within `PosSaleServiceImpl.recordOnlineSale()`:

1. Check idempotency cache: `CacheService.get("pos:sale:idem:{idempotencyKey}")`.
   If present, return the cached `PosSaleDto` immediately — no downstream calls made.
2. `ProductCatalogService.isVariantActive(variantId)` — reject the entire sale if any
   variant is inactive (throw `BusinessRuleException`).
3. `ProductCatalogService.findVariantBySku(sku)` and
   `ProductPricingService.getPriceForVariant(variantId)` — capture name, SKU, and
   price snapshot per item. Compute subtotal per item and total_amount for the sale.
4. `InventoryQueryService.checkAvailability(variantId, quantity)` — reject the entire
   sale if any item is unavailable (throw `BusinessRuleException`). Online POS does not
   proceed on insufficient stock.
5. Call `OrderCreationService.createOrder(cashierId, items, sentinelShippingAddress,
   currency)` — returns `orderId`. From this point the Order module has committed.
6. Begin `@Transactional(propagation = REQUIRED)` POS INSERT block:
   a. Insert `pos_sale` (sale_mode=ONLINE, sync_status=N_A, online_order_id=orderId,
      sold_at=Instant.now() [server clock], cashier_id=cashierId).
   b. Insert `pos_sale_items` for each line item with snapshot fields and subtotals.
7. Write idempotency cache: `CacheService.put("pos:sale:idem:{idempotencyKey}", saleId,
   TTL 24h)`.
8. Publish `pos.sale.completed` via `DomainEventPublisher`.
9. Return `PosSaleDto`.

If step 5 throws: no POS state is written; the caller receives the Order module's
exception (surfaced as an appropriate HTTP status).

If step 6 throws after step 5 committed: the orphaned order mitigation applies (see
Flag 1 decision). The HTTP response is 5xx; the terminal should retry with the same
idempotency key.

If step 6 commits but step 7 or 8 fails: the sale is created; the cache miss on retry
results in duplicate `createOrder()` call. This is prevented by writing the idempotency
entry (step 7) inside the `@Transactional` block before event publish. If the cache
write fails, the sale is still committed and the terminal retry will re-execute the
full flow — the operator reconciliation query handles the duplicate order detection.

---

### Offline POS Sync Flow (pos-sync-specialist boundary)

The sync algorithm is the domain of `pos-sync-specialist`. This section documents the
integration contract: what `PosSyncServiceImpl` calls, in what order, and how it records
the outcome.

`submitOfflineSync(terminalId, payloads)` outer method is NOT `@Transactional`.
It iterates the payload list and delegates each item to `processSingleOfflineItem()`,
which uses `propagation = REQUIRES_NEW` to isolate each item's DB state.

#### Per-item two-phase processing:

**Phase 1 — Queue persistence (REQUIRES_NEW)**
1. Validate `OfflineSalePayload` fields (bean validation). On failure: add to
   `SyncResultDto.failedItems` with reason; skip to next item. No queue row written.
2. Insert `pos_sync_queue` (status=PENDING, sale_data=payload serialized as JSONB,
   terminal_id=terminalId). Commit.

**Phase 2 — Processing (REQUIRES_NEW)**
For each item whose Phase 1 committed:
1. Insert `pos_sale` (sale_mode=OFFLINE, sync_status=PENDING, terminal_id=terminalId,
   sold_at=payload.soldAt [device clock], cashier_id=payload.cashierId,
   total_amount=sum of line item subtotals, currency=payload.currency).
   The `posSaleId` (the new UUID) is captured for use in conflict resolution calls.
2. Insert `pos_sale_items` for each line item with snapshot fields, quantity, price,
   subtotal, and location_id=terminalLocationId.
3. For each line item:
   Call `InventoryReservationService.resolveConflict(
       posSaleId, item.variantId, terminalLocationId, item.quantity,
       payload.soldAt, null)`.
   The `webOrderId` argument is always `null` for MVP. The `resolveConflict()`
   implementation performs an internal lookup of any conflicting web reservation by
   variant and location — the POS module does not pre-fetch or pass the web order ID.
4. After all line items are processed:
   - If any `resolveConflict()` call returned a conflict outcome (POS_PRIORITY or
     BUFFER_EXHAUSTED — the POS module detects this via the published event or via a
     return value from `resolveConflict()`): set `pos_sale.sync_status=CONFLICT_RESOLVED`.
   - Else: set `pos_sale.sync_status=SYNCED`.
5. Update `pos_sync_queue.status=PROCESSED, processed_at=Instant.now()`.
6. Publish `pos.sale.synced` or `pos.sync.conflict.resolved` via `DomainEventPublisher`.
7. Commit Phase 2 transaction.

**Phase 2 failure handling (catch block, REQUIRES_NEW)**
If Phase 2 throws any exception:
1. Phase 2 transaction is rolled back: `pos_sale`, `pos_sale_items` rows are lost.
   `pos_sync_queue.status` remains PENDING (since Phase 2 rolled back before updating it).
2. In a new `REQUIRES_NEW` transaction: Write `AuditService.log()` (AuditAction.STATUS_CHANGE
   on `pos_sync_queue` before mutation). Update `pos_sync_queue.status=FAILED,
   failure_reason=exception.getMessage()`. Commit.
3. Add to `SyncResultDto.failedItems` with the raw payload and failure reason.

Note on `resolveConflict()` return contract: the current `InventoryReservationService`
signature (ADR-4) returns `void`. The POS module cannot directly inspect the conflict
outcome from the return value. Two options:

**Option A**: change `resolveConflict()` to return a `ConflictResolutionResult` record
(outcome enum: NO_CONFLICT, POS_PRIORITY, BUFFER_EXHAUSTED).

**Option B**: POS inspects `pos_sale.sync_status` by querying the published event
outcome — but the event is async and cannot be read synchronously.

**Decision: Option A — `resolveConflict()` returns `ConflictResolutionResult`.**
This is a minor addition to the Inventory module's interface. It does not add a new
method; it changes the return type from `void` to a result record. The Inventory ADR
(ADR-4) must be updated to reflect this. `ConflictResolutionResult` is defined in
`walmal-inventory`'s `application/` package:

```
record ConflictResolutionResult(ConflictOutcome outcome)
enum ConflictOutcome { NO_CONFLICT, POS_PRIORITY, BUFFER_EXHAUSTED }
```

The POS module inspects the `ConflictOutcome` from the result list to determine
whether `sync_status` should be SYNCED or CONFLICT_RESOLVED. A sale is
CONFLICT_RESOLVED if any line item returned POS_PRIORITY or BUFFER_EXHAUSTED.

#### Sync result DTO contract:

`SyncResultDto` fields:
- `terminalId: UUID`
- `submittedCount: int`
- `processedCount: int`
- `conflictResolvedCount: int`
- `failedCount: int`
- `failedItems: List<SyncFailureDetail>`
  - `payload: OfflineSalePayload` — the original payload
  - `reason: String` — human-readable failure reason

---

### Public Service Interfaces

#### `PosTerminalService` (cross-module: API layer only)

ISP rationale: terminal management (register, deactivate, query) is consumed only by
the API layer. No other module needs terminal lifecycle operations.

```
registerTerminal(name: String, locationId: UUID) → UUID
  Creates a pos_terminals row (status=ACTIVE).
  Returns the new terminal UUID.
  No audit log required — INSERT is not destructive.

deactivateTerminal(terminalId: UUID, actorId: UUID) → void
  Write AuditService.log() before mutation (AuditAction.STATUS_CHANGE on pos_terminals).
  Set pos_terminals.status=INACTIVE.
  Idempotent: if already INACTIVE, return without error or audit log write.

getTerminal(terminalId: UUID) → PosTerminalDto
  Read pos_terminals by primary key.
  Throw ResourceNotFoundException if absent.
  No caching — terminal data changes infrequently; read volume is low.
```

#### `PosSaleService` (cross-module: API layer only)

ISP rationale: sale creation and query are all consumed by the POS API layer. No other
module injects `PosSaleService`. The interface is unified for MVP (see Flag 3 decision).

```
recordOnlineSale(terminalId: UUID, items: List<PosSaleLineItem>,
                 cashierId: UUID, currency: String,
                 idempotencyKey: String) → PosSaleDto
  Executes the online POS sale flow (see Online POS Sale Flow section).
  Throws BusinessRuleException on inactive variant or insufficient stock.
  Throws ResourceNotFoundException if the terminal does not exist.

getSale(saleId: UUID) → PosSaleDto
  Read pos_sales + pos_sale_items by primary key.
  Throw ResourceNotFoundException if absent.
  Cached at key pos:sale:{saleId} with TTL 10 minutes.
  Cache is not evicted after creation — pos_sales is immutable after INSERT.

listSalesByTerminal(terminalId: UUID, pageable: Pageable) → Page<PosSaleSummaryDto>
  Paginated read of pos_sales for a terminal, ordered by sold_at DESC.
  Not cached — real-time pagination over mutable query results.
```

#### `PosSyncService` (cross-module: API layer only)

ISP rationale: sync is a separate concern from sale creation and terminal management.
The sync endpoint receives offline batches; no other module calls these methods.

```
submitOfflineSync(terminalId: UUID,
                  payloads: List<OfflineSalePayload>) → SyncResultDto
  Executes the two-phase per-item offline sync flow (see Offline POS Sync Flow).
  Non-transactional at the outer level; per-item REQUIRES_NEW transactions inside.
  Returns full summary including processedCount, conflictResolvedCount, failedItems.

getSyncStatus(terminalId: UUID) → SyncStatusDto
  Aggregates pos_sync_queue rows for the terminal:
    pendingCount, processedCount, failedCount,
    lastProcessedAt (max processed_at where status=PROCESSED),
    oldestFailedAt (min submitted_at where status=FAILED, or null).
  Not cached — real-time operational status for operator dashboards.
```

---

### Interfaces Consumed from Upstream Modules

The POS module injects the following interfaces. It never imports any Repository bean
from any other module.

| Interface | Source Module | Package | Methods Used by POS |
|---|---|---|---|
| `OrderCreationService` | walmal-order | `com.walmal.order.application` | `createOrder()` |
| `OrderQueryService` | walmal-order | `com.walmal.order.application` | `getOrder()` — optional, for receipt display |
| `ProductCatalogService` | walmal-product | `com.walmal.product.application` | `findVariantBySku()`, `isVariantActive()` |
| `ProductPricingService` | walmal-product | `com.walmal.product.application` | `getPriceForVariant()` |
| `InventoryQueryService` | walmal-inventory | `com.walmal.inventory.application` | `checkAvailability()`, `getStockLevel()` |
| `InventoryReservationService` | walmal-inventory | `com.walmal.inventory.application` | `resolveConflict()` |

`InventoryAdjustmentService` is NOT consumed by POS. Stock adjustments (receipts,
corrections, transfers) are a Warehouse-only concern (Step 7).

`OrderFulfillmentService` is NOT consumed by POS. POS sales do not trigger order
fulfillment — the Order module's own payment flow handles CONFIRMED status.

---

### Offline POS Sync Conflict Resolution Algorithm

This section is the canonical specification for `pos-sync-specialist`. The algorithm is
implemented in `InventoryReservationService.resolveConflict()` (walmal-inventory), called
by `PosSyncServiceImpl` per line item during offline sync. The POS module orchestrates
the call sequence; the resolution logic lives in Inventory.

The algorithm is fully specified in ADR-4, Section "POS Conflict Resolution Design". The
POS module's obligations are:

1. Pass `posSaleId` — the UUID of the `pos_sales` row created in Phase 2 step 1. This
   serves as the `reference_id` in `inventory_movements` rows written by `resolveConflict()`.
2. Pass `variantId` — from the offline sale line item.
3. Pass `locationId` — the POS terminal's `inventory_locations` UUID (sourced from
   `pos_terminals.location_id`).
4. Pass `quantity` — from the offline sale line item.
5. Pass `posSaleTimestamp` — `payload.soldAt` (device clock, provided by the terminal).
6. Pass `webOrderId = null` — always null for MVP. `resolveConflict()` performs its own
   internal lookup of the conflicting web reservation.
7. Inspect the returned `ConflictResolutionResult.outcome` to classify the sale as
   SYNCED or CONFLICT_RESOLVED.
8. Never call `InventoryAdjustmentService` directly. Stock deduction for offline sales
   is always through `resolveConflict()`.

**CLAUDE.md rule 3 (Offline POS Sync Conflict Resolution)** specifies:
- Rule 1: If POS sale timestamp is earlier than web order → POS wins, web order cancelled.
- Rule 2: If stock exhausted and web order is earlier or timestamps are equal → warehouse
  buffer stock wins.
- Rule 3: Always notify the affected customer when their order is cancelled.

Rules 1 and 2 are implemented in `resolveConflict()` (Inventory module). Rule 3 is
satisfied by the `inventory.reservation.released` event published by Inventory, which
the Notification module (Step 8) consumes to send the customer message. The POS module
additionally publishes `pos.sync.conflict.resolved` on `pos.exchange` for POS-specific
downstream consumers (e.g. operator alerts).

---

### RabbitMQ Events

#### Exchange declared by this module

`pos.exchange` (type: direct, durable: true)

#### Published by POS module

| Routing Key | Event Class | Trigger | Payload | Downstream Consumers |
|---|---|---|---|---|
| `pos.sale.completed` | `PosSaleCompletedEvent` | Online sale committed in `recordOnlineSale()` | terminalId, saleId, orderId, cashierId, totalAmount, currency, soldAt | Notification module |
| `pos.sale.synced` | `PosSaleSyncedEvent` | Offline item processed with NO_CONFLICT outcome | terminalId, saleId, posSaleTimestamp, lineItemCount | Notification module |
| `pos.sync.conflict.resolved` | `PosSyncConflictResolvedEvent` | Offline item processed with POS_PRIORITY or BUFFER_EXHAUSTED outcome | terminalId, saleId, conflictOutcome (enum), variantId, locationId | Notification module, Operator dashboard |

No events are consumed by the POS module. POS publishes only; it does not bind any
listener queue to any upstream exchange. Upstream outcomes (e.g. inventory stock level
changes) reach POS indirectly through operator dashboards and terminal state queries,
not through RabbitMQ listeners.

#### Event Class Definitions

```
com.walmal.pos.domain.event.PosSaleCompletedEvent extends DomainEvent
  Fields: UUID terminalId, UUID saleId, UUID orderId, UUID cashierId,
          BigDecimal totalAmount, String currency, Instant soldAt

com.walmal.pos.domain.event.PosSaleSyncedEvent extends DomainEvent
  Fields: UUID terminalId, UUID saleId, Instant posSaleTimestamp, int lineItemCount

com.walmal.pos.domain.event.PosSyncConflictResolvedEvent extends DomainEvent
  Fields: UUID terminalId, UUID saleId, ConflictOutcome conflictOutcome,
          UUID variantId, UUID locationId
          -- ConflictOutcome imported from com.walmal.inventory.application
```

All events are published via `DomainEventPublisher.publish(event, routingKey)`.
`RabbitTemplate` is never injected into any application-layer class.

---

## Package Structure

```
walmal-pos/
  pom.xml

  src/main/java/com/walmal/pos/
    api/
      PosTerminalController.java        (@RestController, /api/v1/pos/terminals)
      PosSaleController.java            (@RestController, /api/v1/pos/sales)
      PosSyncController.java            (@RestController, /api/v1/pos/sync)
      dto/
        request/
          RegisterTerminalRequest.java  (name, locationId)
          DeactivateTerminalRequest.java (actorId)
          RecordOnlineSaleRequest.java  (terminalId, items[], cashierId, currency,
                                         X-Idempotency-Key from header)
          OfflineSalePayload.java       (terminalId, soldAt, cashierId, currency,
                                         items: List<OfflineSaleLineItem>)
          OfflineSaleLineItem.java      (variantId, sku, quantity, priceAtSale, currency)
          PosSaleLineItem.java          (variantId, sku, quantity — online sale input)
          OfflineSyncRequest.java       (terminalId, payloads: List<OfflineSalePayload>)
        response/
          PosTerminalDto.java           (id, name, locationId, status, lastSeenAt)
          PosSaleDto.java               (id, terminalId, onlineOrderId, soldAt,
                                         totalAmount, currency, saleMode, syncStatus,
                                         cashierId, items: List<PosSaleItemDto>)
          PosSaleSummaryDto.java        (id, terminalId, soldAt, totalAmount, currency,
                                         saleMode, syncStatus)
          PosSaleItemDto.java           (id, variantId, productNameSnapshot, skuSnapshot,
                                         quantity, priceAtSale, currency, subtotal)
          SyncResultDto.java            (terminalId, submittedCount, processedCount,
                                         conflictResolvedCount, failedCount,
                                         failedItems: List<SyncFailureDetail>)
          SyncFailureDetail.java        (payload: OfflineSalePayload, reason: String)
          SyncStatusDto.java            (terminalId, pendingCount, processedCount,
                                         failedCount, lastProcessedAt, oldestFailedAt)

    domain/
      PosTerminal.java                  (@Entity, table: pos_terminals)
      PosSale.java                      (@Entity, table: pos_sales)
      PosSaleItem.java                  (@Entity, table: pos_sale_items)
      PosSyncQueue.java                 (@Entity, table: pos_sync_queue)
      TerminalStatus.java               (enum: ACTIVE, INACTIVE)
      SaleMode.java                     (enum: ONLINE, OFFLINE)
      SyncStatus.java                   (enum: N_A, PENDING, SYNCED, CONFLICT_RESOLVED, FAILED)
      QueueStatus.java                  (enum: PENDING, PROCESSED, FAILED)
      event/
        PosSaleCompletedEvent.java      (extends DomainEvent)
        PosSaleSyncedEvent.java         (extends DomainEvent)
        PosSyncConflictResolvedEvent.java (extends DomainEvent)

    application/
      PosTerminalService.java           (interface — cross-module: API layer only)
      PosSaleService.java               (interface — cross-module: API layer only)
      PosSyncService.java               (interface — cross-module: API layer only)
      impl/
        PosTerminalServiceImpl.java
        PosSaleServiceImpl.java
        PosSyncServiceImpl.java
        PosSyncItemProcessor.java       (internal — handles per-item two-phase processing,
                                         REQUIRES_NEW propagation, failure recording)

    infrastructure/
      PosTerminalRepository.java        (JpaRepository<PosTerminal, UUID>)
      PosSaleRepository.java            (JpaRepository<PosSale, UUID>)
      PosSaleItemRepository.java        (JpaRepository<PosSaleItem, UUID>)
      PosSyncQueueRepository.java       (JpaRepository<PosSyncQueue, UUID>)

    config/
      PosRabbitMQConfig.java            (declares pos.exchange, three routing keys)
      PosCacheConfig.java               (cache key constants and TTL values)
      PosOpenApiConfig.java             (Springdoc GroupedOpenApi for /pos/**)
      PosConfig.java                    (@ConfigurationProperties — sentinel country,
                                         sync timeout, max batch size)

  src/test/java/com/walmal/pos/
    api/
      PosTerminalControllerTest.java    (@WebMvcTest)
      PosSaleControllerTest.java        (@WebMvcTest)
      PosSyncControllerTest.java        (@WebMvcTest)
    domain/
      PosSaleTest.java
      PosSyncQueueTest.java
    application/
      PosTerminalServiceImplTest.java   (Mockito)
      PosSaleServiceImplTest.java       (Mockito — covers online sale flow, idempotency,
                                         orphaned order scenario)
      PosSyncServiceImplTest.java       (Mockito — covers full batch processing,
                                         per-item isolation, FAILED path)
      PosSyncItemProcessorTest.java     (Mockito — covers REQUIRES_NEW isolation,
                                         Phase 1 / Phase 2 separation,
                                         conflict outcome classification)
      OnlineSaleIdempotencyTest.java    (Mockito — verifies cache hit returns cached
                                         response without calling createOrder() again)
      OfflineSyncConflictTest.java      (Mockito — covers POS_PRIORITY and
                                         BUFFER_EXHAUSTED outcomes and sync_status assignment)
    infrastructure/
      PosSaleRepositoryTest.java        (@DataJpaTest, Testcontainers)
      PosIntegrationTest.java           (@SpringBootTest, Testcontainers PostgreSQL + RabbitMQ)
```

---

## Domain Model Notes

### PosTerminal (@Entity, table: pos_terminals)

No `@Version` column. Terminal registration and deactivation are low-frequency
operations with no concurrent modification risk. `deactivateTerminal()` guards against
double-deactivation by checking the current status before writing.

### PosSale (@Entity, table: pos_sales)

No `@Version` column. POS sales are append-only at creation; `sync_status` is updated
once (PENDING → SYNCED or CONFLICT_RESOLVED) and never reverted. The per-item
`REQUIRES_NEW` transaction boundary prevents concurrent updates to the same `PosSale`
row because each sale is created within a single item's processing phase.

### PosSaleItem (@Entity, table: pos_sale_items)

No `updated_at`. No `@Version`. Insert-only. The `PosSaleItemRepository` exposes
`save()` and read methods only. No service ever calls `save()` on an existing
`PosSaleItem` instance.

### PosSyncQueue (@Entity, table: pos_sync_queue)

`sale_data` is mapped as `@Column(columnDefinition = "jsonb")` and stored/retrieved
as a `String`. The `PosSyncItemProcessor` uses `ObjectMapper` to deserialize it to
`OfflineSalePayload`. No JPA type converter for JSONB is required — the raw JSON
string is sufficient since `sale_data` is only read for debugging and resubmission,
not for business queries.

---

## Caching Strategy

All caching uses `CacheService` (DIP). `RedisTemplate` is never injected into
application-layer classes.

| Cache Key | Content | TTL | Eviction Trigger |
|---|---|---|---|
| `pos:sale:idem:{idempotencyKey}` | `PosSaleDto` | 24 hours | None — expires naturally |
| `pos:sale:{saleId}` | `PosSaleDto` | 10 minutes | None — sales are immutable after creation |

`listSalesByTerminal()` is not cached. Paginated terminal sale history changes with
every new sale and would require per-terminal cache invalidation. The read volume for
this endpoint is low (operator review use case) and does not warrant the invalidation
complexity.

`getSyncStatus()` is not cached. It aggregates live queue counts for an operator
dashboard; stale counts would mislead operators investigating sync failures.

---

## Audit Log Requirements

Per CLAUDE.md: all destructive DB operations write to `audit_log` before execution.
`AuditService.log(AuditEntry)` must be called before the corresponding DB mutation.

| Operation | Method | AuditAction | Table Audited | Timing |
|---|---|---|---|---|
| Deactivate terminal | `deactivateTerminal()` | `STATUS_CHANGE` | `pos_terminals` | Before `status=INACTIVE` UPDATE |
| Mark sync queue FAILED | `PosSyncItemProcessor` catch block | `STATUS_CHANGE` | `pos_sync_queue` | Before `status=FAILED` UPDATE |

Operations that are NOT destructive and do not require an audit log entry:
- `registerTerminal()` — INSERT, not destructive.
- `pos_sales` INSERT — append-only, not destructive.
- `pos_sale_items` INSERT — append-only, not destructive.
- `pos_sync_queue` PENDING INSERT — Phase 1 persists the raw payload; this is not a
  destructive operation.
- `pos_sync_queue.status=PROCESSED` UPDATE — the processing outcome is recorded
  positively; this is not destructive to the audit trail.
- Online sale creation — same rationale as order creation in ADR-5.

`AuditEntry` fields for terminal deactivation:
- `tableName`: `"pos_terminals"`
- `recordId`: `terminalId`
- `action`: `AuditAction.STATUS_CHANGE`
- `oldValue`: `{"status": "ACTIVE"}`
- `newValue`: `{"status": "INACTIVE"}`
- `performedBy`: `actorId.toString()`

`AuditEntry` fields for sync queue FAILED:
- `tableName`: `"pos_sync_queue"`
- `recordId`: `queueRowId`
- `action`: `AuditAction.STATUS_CHANGE`
- `oldValue`: `{"status": "PENDING"}`
- `newValue`: `{"status": "FAILED", "failure_reason": "..."}`
- `performedBy`: `"system:pos-sync-processor"`

---

## Concurrency

No `@Version` optimistic lock on any POS table. The rationale:

- `pos_terminals`: write operations (register, deactivate) are rare and sequential
  per terminal. No concurrent modification scenario exists.
- `pos_sales` and `pos_sale_items`: insert-only. No concurrent update path.
- `pos_sync_queue`: the per-item `REQUIRES_NEW` transactions are sequential within
  the sync request. No concurrent update to the same queue row is possible within
  a single sync call. Concurrent sync calls from the same terminal are prevented
  by the terminal-level idempotency check at the controller level (future: distributed
  lock on `terminalId` during sync processing, if contention is observed in production).

The concurrency risk in POS sync is in the Inventory layer — specifically in
`resolveConflict()`. This is handled by the direct UPDATE WHERE clause in Inventory
(ADR-4, Section "Concurrency Strategy"). The POS module relies on `resolveConflict()`
to be concurrency-safe; it does not add additional locking.

---

## Auth Integration

The POS module does not import any class from walmal-auth.

JWT validation is handled globally by `JwtAuthenticationFilter` (walmal-auth, wired in
walmal-app). POS controllers receive an authenticated request automatically.
`AuthenticatedPrincipal` is defined in `walmal-common` — safe to import.

Role-based access:
- `POST /api/v1/pos/terminals` (register): `ADMIN` only.
- `PUT /api/v1/pos/terminals/{id}/deactivate`: `ADMIN` only.
- `GET /api/v1/pos/terminals/{id}`: `ADMIN`, `POS_OPERATOR`.
- `POST /api/v1/pos/sales` (online sale): `POS_OPERATOR` only.
- `GET /api/v1/pos/sales/{id}`: `POS_OPERATOR`, `ADMIN`.
- `GET /api/v1/pos/sales?terminalId=...`: `POS_OPERATOR`, `ADMIN`.
- `POST /api/v1/pos/sync`: `POS_OPERATOR` only.
- `GET /api/v1/pos/sync/status?terminalId=...`: `POS_OPERATOR`, `ADMIN`.

`cashierId` in `recordOnlineSale()` is sourced from `AuthenticatedPrincipal.userId()`.
The POS controller extracts it from the security context and passes it explicitly to the
service — the service does not access `SecurityContextHolder` directly.

---

## SOLID Compliance

### SRP — One class, one responsibility

| Class | Single Responsibility |
|---|---|
| `PosTerminalServiceImpl` | Terminal lifecycle: register, deactivate, query |
| `PosSaleServiceImpl` | Online sale workflow: product validation, stock check, order creation, POS INSERT, idempotency, event publish |
| `PosSyncServiceImpl` | Offline sync orchestration: iterate batch, delegate to processor, aggregate results |
| `PosSyncItemProcessor` | Per-item two-phase transaction processing (REQUIRES_NEW), failure recording, conflict outcome classification |

`PosSyncItemProcessor` is not a public interface — it is an internal helper in
`application/impl/` analogous to `OrderPaymentOrchestrator` in the Order module. Its
single responsibility is managing the `REQUIRES_NEW` transaction boundary and the
three-phase (persist queue, process, record failure) protocol for a single offline item.
Extracting it prevents `PosSyncServiceImpl` from being responsible for both batch
orchestration and per-item transaction management.

### DIP — Infrastructure via interfaces only

| Infrastructure | Interface Used | Never Used Directly |
|---|---|---|
| RabbitMQ | `DomainEventPublisher` | `RabbitTemplate` |
| Redis | `CacheService` | `RedisTemplate` |
| Audit table | `AuditService` | Direct `JdbcTemplate` or `AuditLogRepository` in service |
| Product module | `ProductCatalogService`, `ProductPricingService` (interfaces) | `ProductVariantRepository` or any product infra class |
| Inventory module | `InventoryQueryService`, `InventoryReservationService` (interfaces) | `InventoryStockRepository` or any inventory infra class |
| Order module | `OrderCreationService`, `OrderQueryService` (interfaces) | `OrderRepository` or any order infra class |

### ISP — Interfaces split by consumer

| Interface | Consumer | Methods |
|---|---|---|
| `PosTerminalService` | API layer (PosTerminalController) | `registerTerminal()`, `deactivateTerminal()`, `getTerminal()` |
| `PosSaleService` | API layer (PosSaleController) | `recordOnlineSale()`, `getSale()`, `listSalesByTerminal()` |
| `PosSyncService` | API layer (PosSyncController) | `submitOfflineSync()`, `getSyncStatus()` |

No other module injects any POS service interface. POS is a leaf module in the
synchronous dependency graph — it consumes from upstream modules but nothing downstream
calls into POS services synchronously.

### OCP — Extension points

The `SaleMode` and `SyncStatus` enums are the extension points for future sale types
(e.g. a kiosk mode). Adding a new mode extends the enum without modifying existing
switch logic in `PosSyncItemProcessor` — but existing switch statements that use
`SaleMode` or `SyncStatus` in the application layer must be reviewed for exhaustiveness.
The `PosSyncItemProcessor` processes `sale_mode=OFFLINE` only; new modes must be added
as explicit branches, not silently defaulted. This is a future concern, not an MVP risk.

`ConflictOutcome` (from Inventory module) is also an extension point. Adding a new
outcome (e.g. PARTIAL_BUFFER) requires a new branch in `PosSyncItemProcessor` to
classify `sync_status`. The processor should use an exhaustive switch-expression
(Java 21) so the compiler flags an unhandled new constant at build time.

### LSP — No subtype violations

No inheritance hierarchies in this module outside `extends BaseEntity`.
No class throws `UnsupportedOperationException` for any inherited method. All domain
enums use closed sets with explicit DB CHECK constraints.

---

## Maven Dependencies

`walmal-pos` depends on:
- `walmal-common` (DIP interfaces: `CacheService`, `DomainEventPublisher`, `AuditService`;
  `BaseEntity`, `AuthenticatedPrincipal`, exception types)
- `walmal-product` (for `ProductCatalogService`, `ProductPricingService` interface imports
  only — no Repository beans)
- `walmal-inventory` (for `InventoryQueryService`, `InventoryReservationService` interface
  imports, `ConflictResolutionResult`, `ConflictOutcome` — no Repository beans)
- `walmal-order` (for `OrderCreationService`, `OrderQueryService` interface imports only —
  no Repository beans)
- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-amqp`
- `springdoc-openapi-starter-webmvc-ui`
- `spring-boot-starter-validation` (for `@Valid` on `OfflineSalePayload`)
- `spring-boot-starter-test` + `testcontainers:postgresql` + `testcontainers:rabbitmq`
  (test scope)

`walmal-infrastructure` is NOT a direct Maven dependency. `walmal-app` wires the
concrete `CacheService`, `DomainEventPublisher`, and `AuditService` implementations at
runtime.

`walmal-pos` must be added to the parent `pom.xml` `<modules>` section and to
`walmal-app`'s `<dependencies>` by the module-builder.

---

## Architecture Risks and Flags

### Risk 1: Orphaned order after `recordOnlineSale()` POS INSERT failure

**Severity**: Medium. **Probability**: Low (POS INSERT is a simple local DB write).

An order committed by `OrderCreationService.createOrder()` but without a corresponding
`pos_sales` row leaves the platform in an inconsistent state. The terminal receives
HTTP 5xx and must retry. The idempotency cache prevents duplicate order creation on
retry if the cache write succeeded before the INSERT failed.

Mitigation in place: idempotency key cache (24h TTL), operator reconciliation query,
documentation in runbook. A future outbox pattern would eliminate this risk entirely.

No SOLID violation. This is an inherent distributed write gap in the modular monolith.

### Risk 2: Large offline batch HTTP timeout

**Severity**: Medium (operator experience). **Probability**: Medium (terminal offline
for hours in a busy store).

Processing 100 items sequentially — each invoking Product, Inventory, and recording
POS state — may exceed a 30-second default HTTP timeout. Mitigation: configurable
read timeout (default 120 seconds), documented max batch size of 100 in OpenAPI spec.

No background job for MVP. A future ADR will introduce asynchronous batch processing
with a polling status endpoint (`GET /api/v1/pos/sync/{jobId}/status`).

### Risk 3: `resolveConflict()` return type change from ADR-4

The Inventory module's `InventoryReservationService.resolveConflict()` currently returns
`void` per ADR-4. This ADR requires it to return `ConflictResolutionResult`. This is a
breaking change to the ADR-4 interface specification.

**Action required**: ADR-4 must be updated to reflect the new return type before the
module-builder implements the Inventory module. The `database-designer` for the POS
module should note that `ConflictResolutionResult` and `ConflictOutcome` are added to
`walmal-inventory`'s `application/` package.

This is not a SOLID violation — adding a return type to an existing method signature
is a contractual change, handled in the same module. No module boundary is crossed.

### Risk 4: `pos_sync_queue.sale_data JSONB` validation is entirely application-side

If a terminal firmware bug produces a malformed payload, the only guard is bean
validation on `OfflineSalePayload` at the start of per-item processing. A validation
failure writes a FAILED queue row with a descriptive `failure_reason`. No data is lost
but the terminal operator must investigate the firmware issue.

This risk is accepted for MVP. The JSONB column intentionally trades column-level
constraints for payload schema flexibility.

### Risk 5: ISP — `PosSaleService.getSale()` and `listSalesByTerminal()` read methods

The Flag 3 analysis (above) concluded that keeping read methods on `PosSaleService` is
correct for MVP because there is exactly one consumer (the API layer). If a future
Reporting module needs to query POS sale data, the read methods must be extracted to a
`PosSaleQueryService` interface at that point. The module-builder must not create a
direct dependency from the Reporting module (or any module) to `PosSaleService` for
read purposes — that would silently couple the reporter to the write interface.

---

## Consequences

### Positive

- Module boundary is clean. Product, Inventory, Order, and Auth are referenced by
  interface and UUID only. No upstream Repository bean is ever injected into POS classes.
- Per-item `REQUIRES_NEW` transactions in `PosSyncItemProcessor` ensure that a single
  failed offline item does not roll back successfully processed items. Partial sync
  success is fully supported.
- The two-phase sync processing (Phase 1: queue persists always; Phase 2: processing
  attempt) gives operators full visibility into what was submitted and what failed.
  FAILED queue rows with `failure_reason` are the operational debugging surface.
- `PosSyncItemProcessor` as an internal helper keeps `PosSyncServiceImpl` readable
  (SRP) and makes the per-item transaction logic independently testable.
- JSONB `sale_data` allows device firmware evolution without schema migrations.
- The sentinel `ShippingAddress` allows online POS sales to reuse `OrderCreationService`
  unchanged — no new method or overload is added to the Order module.
- `ConflictResolutionResult` return type from `resolveConflict()` enables the POS
  module to classify sync outcomes (`sync_status`) without coupling to the
  `inventory.reservation.released` RabbitMQ event, which is async.

### Negative / Risks

- The orphaned order risk (Flag 1) is inherent and cannot be eliminated in MVP without
  an outbox pattern. Documented in runbook. Expected frequency: very low.
- The `resolveConflict()` return type change (Risk 3) requires an ADR-4 amendment
  before implementation begins. This is a dependency that the orchestrator must enforce.
- The HTTP timeout risk (Risk 2) limits offline batch size to 100 items in MVP.
  This is a product constraint, not a bug; it must be communicated to the POS
  terminal firmware team.
- `PosSaleService` is a unified interface for MVP. The risk is that future consumers
  may inadvertently depend on the write method (`recordOnlineSale()`) when they only
  need the read methods. The `walmal-orchestrator` and `test-validator` must enforce
  that no module other than `PosSaleController` injects `PosSaleService`.

---

## Definition of Done Checklist

- [ ] `PosTerminalService` interface defined in `application/`
- [ ] `PosSaleService` interface defined in `application/`
- [ ] `PosSyncService` interface defined in `application/`
- [ ] All three implementations complete in `application/impl/`
- [ ] `PosSyncItemProcessor` internal helper implemented with `REQUIRES_NEW` propagation
      and two-phase (queue persist, process) logic
- [ ] `PosTerminal` entity mapped to `pos_terminals` table; no `@Version` column
- [ ] `PosSale` entity mapped to `pos_sales` table; no `@Version` column
- [ ] `PosSaleItem` entity mapped to `pos_sale_items` table; no `updated_at`; no UPDATE path
- [ ] `PosSyncQueue` entity mapped to `pos_sync_queue` table; rows never hard-deleted
- [ ] `V6__pos_create_tables.sql` Flyway migration applied
- [ ] Three controllers complete with OpenAPI annotations on all endpoints
      (`PosTerminalController`, `PosSaleController`, `PosSyncController`)
- [ ] `PosRabbitMQConfig` declares `pos.exchange` and three routing keys (published)
      — no consumer queue declared (POS publishes only)
- [ ] `PosConfig` declares `pos.instore-sentinel.country`, `pos.sync.request-timeout-ms`,
      and `pos.sync.max-batch-size` as configurable properties
- [ ] All three domain events published via `DomainEventPublisher` only
- [ ] `AuditService.log()` called before `deactivateTerminal()` status mutation
- [ ] `AuditService.log()` called before `pos_sync_queue.status=FAILED` mutation
      (in the `PosSyncItemProcessor` catch-path `REQUIRES_NEW` transaction)
- [ ] `CacheService` used for idempotency cache and sale cache — `RedisTemplate` not
      in application layer
- [ ] Idempotency key header (`X-Idempotency-Key`) processed in `PosSaleController`
      and passed to `PosSaleServiceImpl`; cache checked before any downstream call
- [ ] `recordOnlineSale()` calls `createOrder()` before the `@Transactional` POS INSERT
      block begins — not inside the same transaction
- [ ] `InventoryReservationService.resolveConflict()` called with `webOrderId=null`
      for all offline sync items
- [ ] `ConflictResolutionResult` inspected per line item; `sync_status` set to
      CONFLICT_RESOLVED if any item returned POS_PRIORITY or BUFFER_EXHAUSTED
- [ ] Sentinel `ShippingAddress` built from `PosConfig` and `PosTerminal.name`;
      not hard-coded in the service implementation
- [ ] No `ProductVariantRepository`, `InventoryStockRepository`, `OrderRepository`,
      or any other cross-module Repository bean imported
- [ ] `ProductCatalogService.isVariantActive()` called before any stock check or
      price lookup in `recordOnlineSale()`
- [ ] `PosSaleItem` subtotal computed as `quantity * priceAtSale` in service before INSERT;
      not recomputed on read
- [ ] OpenAPI `maxItems: 100` constraint documented on `POST /api/v1/pos/sync` request body
- [ ] Role-based access annotations on all six controller endpoints (ADMIN / POS_OPERATOR)
- [ ] `@WebMvcTest` tests for all three controllers
- [ ] `PosSaleServiceImplTest` covers: happy-path online sale, idempotency cache hit,
      orphaned order scenario (createOrder succeeds, POS INSERT fails), inactive variant rejection
- [ ] `PosSyncServiceImplTest` covers: full batch success, partial batch failure,
      empty batch edge case
- [ ] `PosSyncItemProcessorTest` covers: Phase 1 success / Phase 2 success,
      Phase 1 success / Phase 2 failure (FAILED queue row written),
      POS_PRIORITY conflict outcome → CONFLICT_RESOLVED sync_status,
      BUFFER_EXHAUSTED outcome → CONFLICT_RESOLVED sync_status,
      NO_CONFLICT outcome → SYNCED sync_status
- [ ] `OfflineSyncConflictTest` covers: POS_PRIORITY and BUFFER_EXHAUSTED outcomes
      with correct `pos.sync.conflict.resolved` event published
- [ ] `OnlineSaleIdempotencyTest` verifies: second call with same idempotency key
      returns cached response without invoking `createOrder()` again
- [ ] Integration tests pass (`@SpringBootTest` + Testcontainers PostgreSQL + RabbitMQ)
- [ ] `walmal-pos` added to parent `pom.xml` `<modules>` and `walmal-app` `<dependencies>`
- [ ] ADR-4 amended to reflect `resolveConflict()` return type change to
      `ConflictResolutionResult` and addition of `ConflictOutcome` enum
- [ ] Docker Compose health check passes with `/api/v1/pos` endpoints reachable
