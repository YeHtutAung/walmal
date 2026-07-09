-- =============================================================================
-- Migration : V7__warehouse_create_tables.sql
-- Module    : walmal-warehouse
-- Date      : 2026-05-21
-- Description:
--   Creates three tables owned exclusively by the walmal-warehouse module:
--     warehouse_fulfillments   — fulfillment state machine per confirmed order
--     warehouse_fulfillment_lines — per-item picking record (insert-only)
--     warehouse_shipments         — shipment metadata created on SHIPPED transition
--
-- Ownership rules:
--   All three tables belong to walmal-warehouse. No other module may JOIN to these
--   tables. Cross-module UUIDs (order_id, variant_id, location_id) are stored with
--   NO FK constraints — application layer validates existence via service interfaces.
--
-- Cross-module UUID columns (no FK by design):
--   warehouse_fulfillments.order_id      → order_orders.id     (walmal-order)
--   warehouse_fulfillments.user_id       → auth_users.id       (walmal-auth)
--   warehouse_fulfillment_lines.variant_id → product_variants.id (walmal-product)
--   warehouse_fulfillment_lines.location_id → inventory_locations.id (walmal-inventory)
--
-- Intra-module FK constraints (same bounded context — FKs allowed):
--   warehouse_fulfillment_lines.fulfillment_id → warehouse_fulfillments.id
--   warehouse_shipments.fulfillment_id         → warehouse_fulfillments.id
--   Both use ON DELETE RESTRICT (never hard-delete fulfillments).
--
-- State machine (warehouse_fulfillments.status):
--   PENDING → PICKING → PACKED → SHIPPED
--   PENDING → CANCELLED  (only from PENDING or PICKING)
--   PICKING → CANCELLED
--   (PACKED and SHIPPED are non-cancellable)
--
-- Audit log compliance (APPLICATION-LAYER CONCERN, not enforced in SQL):
--   The following operations MUST write to audit_log BEFORE executing:
--     - cancelFulfillment() — UPDATE status → CANCELLED
--     - WRITE_OFF adjustStock() call for any discrepancy (qty_picked < qty_requested)
--   Enforcement is in WarehouseFulfillmentServiceImpl.
--
-- Insert-only note (warehouse_fulfillment_lines):
--   Lines are seeded at fulfillment creation and updated only for quantity_picked
--   during PICKING phase. No rows are ever deleted.
-- =============================================================================


-- =============================================================================
-- TABLE: warehouse_fulfillments
-- One row per confirmed order. Created when order.confirmed event is received.
-- =============================================================================
CREATE TABLE warehouse_fulfillments (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id         UUID         NOT NULL UNIQUE,
    user_id          UUID         NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                                  CHECK (status IN ('PENDING', 'PICKING', 'PACKED', 'SHIPPED', 'CANCELLED')),
    shipping_address TEXT         NOT NULL,
    notes            TEXT,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Partial indexes for operational queue queries
CREATE INDEX idx_warehouse_fulfillments_pending
    ON warehouse_fulfillments (created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_warehouse_fulfillments_picking
    ON warehouse_fulfillments (created_at)
    WHERE status = 'PICKING';


-- =============================================================================
-- TABLE: warehouse_fulfillment_lines
-- One row per order line item within a fulfillment. Updated during PICKING.
-- =============================================================================
CREATE TABLE warehouse_fulfillment_lines (
    id                 UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    fulfillment_id     UUID    NOT NULL
                               REFERENCES warehouse_fulfillments(id) ON DELETE RESTRICT,
    variant_id         UUID    NOT NULL,
    location_id        UUID    NOT NULL,
    sku_snapshot       VARCHAR(100) NOT NULL,
    quantity_requested INT     NOT NULL CHECK (quantity_requested > 0),
    quantity_picked    INT     NOT NULL DEFAULT 0 CHECK (quantity_picked >= 0),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_warehouse_fulfillment_lines_fulfillment
    ON warehouse_fulfillment_lines (fulfillment_id);


-- =============================================================================
-- TABLE: warehouse_shipments
-- Created only on PACKED → SHIPPED transition. One-to-one with fulfillment.
-- =============================================================================
CREATE TABLE warehouse_shipments (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    fulfillment_id  UUID         NOT NULL UNIQUE
                                 REFERENCES warehouse_fulfillments(id) ON DELETE RESTRICT,
    carrier         VARCHAR(100) NOT NULL,
    tracking_number VARCHAR(255) NOT NULL,
    shipped_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);


-- =============================================================================
-- Development seed data
-- One PENDING fulfillment for integration tests (order_id matches V5 seed PENDING order)
-- =============================================================================
INSERT INTO warehouse_fulfillments (id, order_id, user_id, status, shipping_address, version, created_at, updated_at)
VALUES (
    'f0000000-0000-0000-0000-000000000001',
    'c0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000001',
    'PENDING',
    '{"line1":"1 Main St","line2":null,"city":"Springfield","country":"US","postalCode":"12345"}',
    0,
    NOW(),
    NOW()
);

INSERT INTO warehouse_fulfillment_lines (id, fulfillment_id, variant_id, location_id, sku_snapshot, quantity_requested, quantity_picked, created_at)
VALUES (
    'd0000000-0000-0000-0000-000000000001',
    'f0000000-0000-0000-0000-000000000001',
    gen_random_uuid(),
    gen_random_uuid(),
    'SKU-SEED-001',
    2,
    0,
    NOW()
);
