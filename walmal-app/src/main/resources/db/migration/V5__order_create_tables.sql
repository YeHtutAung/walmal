-- =============================================================================
-- Migration : V5__order_create_tables.sql
-- Module    : walmal-order (Order Module)
-- Date      : 2026-05-21
-- Description:
--   Creates the two tables owned exclusively by the walmal-order module:
--     order_orders, order_items
--
-- Table ownership note:
--   BOTH tables in this migration are owned by walmal-order.
--   No other module may JOIN to these tables or declare FK constraints that
--   reference columns in these tables. Cross-module references (e.g., an
--   Inventory table referencing an order, or a POS table referencing an order)
--   must store the UUID value only, with NO FK constraint declared in the
--   referencing table.
--
-- Cross-module UUID reference note:
--   The following columns store UUIDs that logically refer to entities in
--   other modules. No FK constraints are declared on these columns by design —
--   enforcing cross-module referential integrity at the DB layer would couple
--   bounded contexts and prevent independent module evolution.
--
--     order_orders.user_id        → auth_users.id            (walmal-auth)
--     order_items.variant_id      → product_variants.id      (walmal-product)
--     order_items.location_id     → inventory_locations.id   (walmal-inventory)
--
--   Application-layer validation via OrderService and its collaborating
--   interfaces (InventoryReservationService, ProductCatalogService,
--   ProductPricingService) is the enforcement mechanism for cross-module
--   reference integrity.
--
-- Intra-module FK note:
--   The FK from order_items.order_id to order_orders.id IS declared here
--   because both tables belong to the same bounded context.
--
-- updated_at management note:
--   updated_at on order_orders has no database-level trigger. It is maintained
--   by the JPA @PreUpdate lifecycle callback in the Order entity. Direct SQL
--   executed outside the ORM (e.g., hotfix scripts, data migrations) MUST
--   manually set updated_at = NOW() to keep the column accurate.
--   order_items is insert-only and has no updated_at column (see below).
--
-- Status / payment_status column note:
--   All status columns are stored as VARCHAR with CHECK constraints rather than
--   PostgreSQL ENUM types. This avoids the ALTER TYPE ADD VALUE limitation in
--   PostgreSQL, which cannot be rolled back inside a transaction and causes
--   Flyway repair issues. To add new status values in a future migration, drop
--   and recreate the CHECK constraint — no type surgery needed.
--   This is the same rationale applied in V3 (product module) and V4
--   (inventory module).
--
-- Optimistic locking note (order_orders.version):
--   The version column on order_orders is used by JPA @Version for optimistic
--   locking. On every UPDATE the ORM appends "AND version = :current_version"
--   to the WHERE clause and increments version by 1. A stale read raises
--   ObjectOptimisticLockingFailureException. This is the concurrency mitigation
--   for simultaneous cancellation: if cancelOrder() and the
--   InventoryEventListener both attempt to set status = 'CANCELLED' at the
--   same time, only one will succeed; the other retries and finds the order
--   already CANCELLED, then exits cleanly. Never set or modify this column
--   manually in application code.
--
-- insert-only note (order_items):
--   order_items is insert-only after order creation. No row in this table is
--   ever UPDATE'd after it is written — the price, quantity, and snapshots are
--   fixed at purchase time. If a customer changes quantity before payment, the
--   Order module cancels the pending order and creates a new one. No updated_at
--   column exists on this table by design.
--
-- Snapshot columns note (order_items):
--   product_name_snapshot, sku_snapshot, and price_at_purchase are copied from
--   the Product module's service interfaces (ProductCatalogService and
--   ProductPricingService) at the moment the order is created. These snapshots
--   decouple the order record from future product changes: if a product name or
--   price changes after an order is placed, the order history remains accurate.
--
-- Audit log compliance note (APPLICATION-LAYER CONCERN — NOT A DB TRIGGER):
--   The following order operations are classified as destructive and MUST write
--   to the audit_log table BEFORE executing the DB mutation. This enforcement
--   is the responsibility of OrderServiceImpl — there are no DB triggers here.
--
--     cancelOrder()                  — UPDATE order_orders SET status = 'CANCELLED'
--                                      (PENDING → CANCELLED, initiated by customer
--                                       or staff)
--     InventoryEventListener         — UPDATE order_orders SET status = 'CANCELLED'
--                                      (PENDING → CANCELLED, triggered by inventory
--                                       event when POS sync conflict resolves to
--                                       cancel the web order)
--     markFulfilled()                — UPDATE order_orders SET status = 'FULFILLED'
--                                      (CONFIRMED → FULFILLED, initiated by
--                                       warehouse/fulfilment workflow)
--
--   Order creation (INSERT into order_orders and order_items) and payment
--   confirmation (additive UPDATE to payment_reference / payment_status) are
--   NOT destructive operations — no audit_log write is required for them.
-- =============================================================================


-- =============================================================================
-- TABLE: order_orders
-- One row per customer order. This is the aggregate root for the order
-- bounded context.
--
-- status lifecycle:
--   PENDING    — order placed, payment not yet confirmed, stock reserved
--   CONFIRMED  — payment succeeded, reservation promoted to CONFIRMED
--   FULFILLED  — goods shipped / handed over to customer
--   CANCELLED  — order cancelled (by customer, staff, or POS sync conflict)
--
-- payment_status lifecycle:
--   PENDING    — awaiting payment gateway callback
--   SUCCESS    — payment gateway confirmed payment
--   FAILED     — payment gateway reported failure; reservation released
--
-- shipping_address is stored as a JSONB snapshot of the delivery address at
-- the time the order was created. This decouples the order record from any
-- future changes the customer makes to their address book.
--
-- payment_reference is NULL until payment succeeds; it is set by the payment
-- gateway integration and stored here for reconciliation. It is never used as
-- a unique identifier within this platform — use order id for that.
--
-- version: see optimistic locking note in the file header above.
-- =============================================================================
CREATE TABLE order_orders (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID         NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                                    CHECK (status IN ('PENDING', 'CONFIRMED', 'FULFILLED', 'CANCELLED')),
    currency           VARCHAR(3)   NOT NULL,
    total_amount       NUMERIC(12,2) NOT NULL CHECK (total_amount >= 0),
    shipping_address   JSONB        NOT NULL,
    payment_reference  VARCHAR(255),
    payment_status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                                    CHECK (payment_status IN ('PENDING', 'SUCCESS', 'FAILED')),
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- user_id index: primary access pattern for customer order history —
-- "all orders placed by this user", paginated and sorted by created_at DESC.
CREATE INDEX idx_ord_orders_user
    ON order_orders (user_id);

-- status index: supports admin dashboard and fulfilment queue queries filtered
-- by status (e.g., "all CONFIRMED orders awaiting fulfilment").
CREATE INDEX idx_ord_orders_status
    ON order_orders (status);

-- Partial index on PENDING status: used by two distinct background processes:
--   1. ReservationExpiryJob — finds PENDING orders whose inventory reservations
--      have expired so it can cancel the order and release the reservation.
--   2. POS offline sync conflict resolver — scans PENDING web orders when
--      applying a POS sync batch to detect concurrent sale conflicts.
-- Partial (WHERE status = 'PENDING') keeps this index small; only a small
-- fraction of total orders are in PENDING state at any given time.
CREATE INDEX idx_ord_orders_pending
    ON order_orders (status)
    WHERE status = 'PENDING';


-- =============================================================================
-- TABLE: order_items
-- One row per (order_id, variant_id) per order. Insert-only after order
-- creation — rows are never modified. See insert-only note in the file header.
--
-- subtotal is stored (not computed at read time) so that order totals remain
-- stable even if the calculation formula changes in future. The application
-- sets subtotal = price_at_purchase * quantity at insert time.
--
-- location_id is the inventory location UUID that the InventoryReservationService
-- used when reserving stock for this line item. It is stored here so that, if
-- the reservation needs to be released (cancellation, expiry, or POS conflict),
-- the Order module can pass the correct location_id back to
-- InventoryReservationService without needing a cross-module JOIN.
--
-- UNIQUE (order_id, variant_id): enforces one line item per variant per order
-- at the database layer. If a customer adds the same variant twice, the
-- application must update quantity on the existing row — not insert a duplicate.
-- This constraint is named so that the application can catch it by name and
-- return an appropriate error response.
-- =============================================================================
CREATE TABLE order_items (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id              UUID          NOT NULL
                                        REFERENCES order_orders(id) ON DELETE RESTRICT,
    variant_id            UUID          NOT NULL,
    product_name_snapshot VARCHAR(500)  NOT NULL,
    sku_snapshot          VARCHAR(100)  NOT NULL,
    quantity              INT           NOT NULL CHECK (quantity > 0),
    price_at_purchase     NUMERIC(12,2) NOT NULL CHECK (price_at_purchase >= 0),
    currency              VARCHAR(3)    NOT NULL,
    subtotal              NUMERIC(12,2) NOT NULL CHECK (subtotal >= 0),
    location_id           UUID          NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    -- No updated_at: this table is insert-only; rows are never modified after
    -- order creation. Price, quantity, and product snapshots are fixed at the
    -- time the order is placed.

    CONSTRAINT uq_ord_items_order_variant UNIQUE (order_id, variant_id)
);

-- order_id index: primary access pattern — "all line items in this order".
-- Used on every order detail read and during order cancellation to enumerate
-- the reservations that need to be released.
CREATE INDEX idx_ord_items_order
    ON order_items (order_id);

-- variant_id index: reverse lookup — "which orders contain this variant?"
-- Used by the POS sync conflict resolver and by product discontinuation
-- workflows to find open orders that reference a given variant.
CREATE INDEX idx_ord_items_variant
    ON order_items (variant_id);


-- =============================================================================
-- Development seed data
-- WARNING: These rows are for local development only.
--   Do not rely on these fixed UUIDs in production deployments. Integration
--   tests may reference these IDs directly; production environments should
--   populate orders through the Order API instead.
--
-- Seed: one PENDING order for local dev/testing.
--   user_id references the fixed admin UUID seeded in V2__auth_create_tables.sql.
--   No seed order_items are inserted here because no variant UUIDs are seeded
--   in V3__product_create_tables.sql in a way that makes a useful line item.
-- =============================================================================
INSERT INTO order_orders (id, user_id, status, currency, total_amount, shipping_address, payment_status)
VALUES (
    'o0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000001',  -- seeded admin user from V2
    'PENDING',
    'USD',
    99.99,
    '{"line1":"123 Main St","city":"Springfield","country":"US","postalCode":"12345"}',
    'PENDING'
);


-- =============================================================================
-- TODO: Future migrations that may be needed for the order module
--
-- V?__order_add_status_history.sql
--   If a full audit trail of status transitions per order is required beyond
--   what audit_log provides (e.g., for customer-facing order timeline display),
--   create an order_status_history table:
--     id          UUID PRIMARY KEY DEFAULT gen_random_uuid()
--     order_id    UUID NOT NULL  -- no FK constraint; intra-module reference
--                                -- declared if preferred, but lightweight apps
--                                -- may omit it for append-only log tables
--     from_status VARCHAR(20)
--     to_status   VARCHAR(20) NOT NULL
--     reason      VARCHAR(255)
--     changed_by  VARCHAR(255) NOT NULL
--     changed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
--   For MVP, the audit_log table captures old_value/new_value JSONB on each
--   destructive status transition, which is sufficient for compliance and
--   debugging without the overhead of an additional table.
--
-- V?__order_add_payments_table.sql
--   If payment tracking requires its own lifecycle (INITIATED → PROCESSING →
--   SUCCEEDED → REFUNDED) with multi-attempt history, create a dedicated
--   order_payments table:
--     id                UUID PRIMARY KEY DEFAULT gen_random_uuid()
--     order_id          UUID NOT NULL  -- intra-module reference
--     gateway_reference VARCHAR(255)
--     amount            NUMERIC(12,2) NOT NULL CHECK (amount >= 0)
--     currency          VARCHAR(3)    NOT NULL
--     status            VARCHAR(20)   NOT NULL DEFAULT 'INITIATED'
--                       CHECK (status IN ('INITIATED','PROCESSING','SUCCEEDED','FAILED','REFUNDED'))
--     gateway_response  JSONB
--     attempted_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
--     resolved_at       TIMESTAMPTZ
--   For MVP, payment_reference and payment_status columns on order_orders are
--   sufficient given the single-attempt-per-order assumption. Introduce
--   order_payments only when the payment gateway integration requires retry
--   logic or partial-refund tracking.
-- =============================================================================
