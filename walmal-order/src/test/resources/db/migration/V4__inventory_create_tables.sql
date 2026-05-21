-- =============================================================================
-- Migration : V4__inventory_create_tables.sql
-- Module    : walmal-inventory (Inventory Module)
-- Date      : 2026-05-20
-- Description:
--   Creates the four tables owned exclusively by the walmal-inventory module:
--     inventory_locations, inventory_stock, inventory_reservations,
--     inventory_movements
--
-- Table ownership note:
--   ALL four tables in this migration are owned by walmal-inventory.
--   No other module may JOIN to these tables or declare FK constraints that
--   reference columns in these tables. Cross-module references (e.g., an Order
--   table referencing a reservation, or a Warehouse table referencing a
--   location) must store the UUID value only, with NO FK constraint declared
--   in the referencing table.
--
-- Cross-module UUID reference note:
--   The following columns store UUIDs that logically refer to entities in
--   other modules. No FK constraints are declared on these columns by design —
--   enforcing cross-module referential integrity at the DB layer would couple
--   bounded contexts and prevent independent module evolution.
--
--     inventory_stock.variant_id         → product_variants.id  (walmal-product)
--     inventory_reservations.order_id    → order_orders.id      (walmal-order)
--     inventory_reservations.variant_id  → product_variants.id  (walmal-product)
--     inventory_movements.variant_id     → product_variants.id  (walmal-product)
--     inventory_movements.reference_id   → reservation / transfer / order UUID
--                                          (any module — nullable, polymorphic)
--
--   Application-layer validation via InventoryService is the enforcement
--   mechanism for cross-module reference integrity.
--
-- Intra-module FK note:
--   FK constraints from inventory_stock, inventory_reservations, and
--   inventory_movements to inventory_locations ARE declared here because
--   all four tables belong to the same bounded context.
--
-- updated_at management note:
--   updated_at has no database-level trigger on any table in this migration.
--   It is maintained by the JPA @PreUpdate lifecycle callback in each entity.
--   Direct SQL executed outside the ORM (e.g., hotfix scripts, data migrations)
--   MUST manually set updated_at = NOW() to keep the column accurate.
--   inventory_movements is insert-only and has no updated_at column (see below).
--
-- Status / type columns note:
--   All status and movement_type columns are stored as VARCHAR with CHECK
--   constraints rather than PostgreSQL ENUM types. This avoids the
--   ALTER TYPE ADD VALUE limitation in PostgreSQL, which cannot be rolled back
--   inside a transaction and causes Flyway repair issues. To add new values in
--   a future migration, drop and recreate the CHECK constraint — no type
--   surgery needed.
--
-- Optimistic locking note (inventory_stock.version):
--   The version column on inventory_stock is used by JPA @Version for
--   optimistic locking. On every UPDATE the ORM appends
--   "AND version = :current_version" to the WHERE clause and increments
--   version by 1. A stale read raises ObjectOptimisticLockingFailureException,
--   which the InventoryServiceImpl must catch and retry. Never set or modify
--   this column manually in application code.
--
-- insert-only note (inventory_movements):
--   inventory_movements is an append-only audit trail of every stock change.
--   No row in this table is ever UPDATE'd or DELETE'd after insert.
--   No updated_at column exists on this table by design. Any DELETE or
--   destructive UPDATE against this table is an architecture violation.
--   All stock mutations must produce a corresponding INSERT here before the
--   mutation is applied to inventory_stock.
--
-- Buffer location note (is_buffer_location):
--   Rows with is_buffer_location = TRUE represent warehouse buffer stock pools
--   used during POS offline sync conflict resolution. When a POS sync reveals
--   that the same variant was sold simultaneously offline (POS) and online (web)
--   and stock is exhausted, the conflict resolver draws from the buffer location
--   to fulfil the web order before considering cancellation. The flag is
--   intentionally a boolean rather than a separate table to keep the location
--   model flat; only one or very few locations are expected to be buffer pools.
--
-- Audit log compliance note:
--   Any DELETE from inventory_locations, inventory_stock, or
--   inventory_reservations, and any destructive UPDATE (e.g., zeroing
--   available_quantity as a correction), MUST write to the audit_log table
--   BEFORE execution. This is a platform-wide architecture rule with no
--   exceptions.
-- =============================================================================


-- =============================================================================
-- TABLE: inventory_locations
-- Represents a physical or logical stock location (warehouse floor, buffer
-- pool, POS back-room, etc.).
-- external_reference_id is a nullable UUID that links this location to the
-- corresponding entity in another module (e.g., a Warehouse or POS register
-- UUID).  The partial UNIQUE index below enforces uniqueness only among
-- non-NULL values, allowing multiple locations with no external reference.
-- is_buffer_location = TRUE marks this as the warehouse buffer pool used by
-- the POS sync conflict resolver (see buffer location note above).
-- =============================================================================
CREATE TABLE inventory_locations (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name                  VARCHAR(200) NOT NULL,
    external_reference_id UUID,
    is_buffer_location    BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Partial unique index: enforces uniqueness of external_reference_id only when
-- it is non-NULL. Allows multiple rows with external_reference_id IS NULL
-- (locations not yet linked to an external entity).
CREATE UNIQUE INDEX idx_inv_locations_ext_ref
    ON inventory_locations (external_reference_id)
    WHERE external_reference_id IS NOT NULL;

-- Partial index on is_buffer_location: used by the POS sync conflict resolver
-- to locate buffer pool rows quickly. Partial (WHERE TRUE) keeps the index
-- minimal — typically only 1–2 rows qualify.
CREATE INDEX idx_inv_locations_buffer
    ON inventory_locations (is_buffer_location)
    WHERE is_buffer_location = TRUE;

-- Partial index on is_active: supports active-location listing queries without
-- scanning soft-deactivated rows.
CREATE INDEX idx_inv_locations_active
    ON inventory_locations (is_active)
    WHERE is_active = TRUE;


-- =============================================================================
-- TABLE: inventory_stock
-- One row per (variant_id, location_id) pair — enforced by the UNIQUE
-- constraint below. This is the authoritative real-time stock record.
--
-- available_quantity: units physically on hand and not reserved.
-- reserved_quantity:  units locked by active PENDING/CONFIRMED reservations.
-- Total on-hand = available_quantity + reserved_quantity.
--
-- CHECK (available_quantity >= 0) and CHECK (reserved_quantity >= 0) are
-- enforced at the DB layer as a safety net. Application logic must never
-- decrement below zero; if it attempts to, the DB will reject the row.
--
-- version: JPA @Version optimistic locking field (see optimistic locking note).
-- Never read or write this column directly in application code.
-- =============================================================================
CREATE TABLE inventory_stock (
    id                  UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    variant_id          UUID    NOT NULL,
    location_id         UUID    NOT NULL
                                REFERENCES inventory_locations(id) ON DELETE RESTRICT,
    available_quantity  INT     NOT NULL DEFAULT 0
                                CHECK (available_quantity >= 0),
    reserved_quantity   INT     NOT NULL DEFAULT 0
                                CHECK (reserved_quantity >= 0),
    low_stock_threshold INT     NOT NULL DEFAULT 10,
    version             BIGINT  NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_inv_stock_variant_location UNIQUE (variant_id, location_id)
);

-- variant_id index: primary lookup path for "how much stock exists for this
-- variant?" queries across all locations.
CREATE INDEX idx_inv_stock_variant
    ON inventory_stock (variant_id);

-- location_id index: supports "all stock at this location" warehouse queries.
CREATE INDEX idx_inv_stock_location
    ON inventory_stock (location_id);

-- Composite partial index on (variant_id, available_quantity): critical path
-- for the reservation flow — "does this variant have any available stock?"
-- The WHERE clause eliminates zero-stock rows, keeping the index compact.
CREATE INDEX idx_inv_stock_available
    ON inventory_stock (variant_id, available_quantity)
    WHERE available_quantity > 0;


-- =============================================================================
-- TABLE: inventory_reservations
-- Records a hold on stock placed by the Order module when an order is created.
-- Lifecycle: PENDING → CONFIRMED (payment success)
--                    → RELEASED  (order cancelled / payment failed / expired)
--
-- order_id and variant_id are cross-module references — no FK constraint
-- (see cross-module UUID reference note at the top of this file).
-- location_id references inventory_locations within this module — FK declared.
--
-- conflict_reason is only set when status = 'RELEASED' to record why the
-- reservation was released prematurely. NULL means normal lifecycle completion
-- (CONFIRMED or not-yet-released PENDING).
--
-- expires_at: set by the application at reservation creation time (typically
-- NOW() + 15 minutes). The ReservationExpiryJob reads the index
-- idx_inv_reservations_pending_expiry every 60 seconds to find expired
-- PENDING reservations and transition them to RELEASED.
--
-- NOTE TO MODULE-BUILDER:
--   The index idx_inv_reservations_pending_expiry is used by
--   ReservationExpiryJob (scheduled every 60 s). Do NOT drop this index.
--   The job query pattern is:
--     SELECT * FROM inventory_reservations
--      WHERE status = 'PENDING' AND expires_at <= NOW();
-- =============================================================================
CREATE TABLE inventory_reservations (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID        NOT NULL,
    variant_id      UUID        NOT NULL,
    location_id     UUID        NOT NULL
                                REFERENCES inventory_locations(id) ON DELETE RESTRICT,
    quantity        INT         NOT NULL CHECK (quantity > 0),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING', 'CONFIRMED', 'RELEASED')),
    conflict_reason VARCHAR(30)
                    CHECK (conflict_reason IN
                           ('POS_PRIORITY', 'BUFFER_EXHAUSTED', 'CANCELLED', 'EXPIRED')),
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- order_id index: supports "all reservations for this order" lookup, used by
-- the Order module via InventoryReservationService (synchronous call).
CREATE INDEX idx_inv_reservations_order
    ON inventory_reservations (order_id);

-- variant_id index: supports stock recalculation queries that sum reserved
-- quantities across all active reservations for a variant.
CREATE INDEX idx_inv_reservations_variant
    ON inventory_reservations (variant_id);

-- Partial expiry index: used exclusively by ReservationExpiryJob.
-- Partial (WHERE status = 'PENDING') keeps the index tiny — only unresolved
-- reservations are scanned. DO NOT DROP this index.
CREATE INDEX idx_inv_reservations_pending_expiry
    ON inventory_reservations (expires_at)
    WHERE status = 'PENDING';

-- status index: supports admin dashboards and monitoring queries filtered by
-- reservation status (e.g., "all CONFIRMED reservations today").
CREATE INDEX idx_inv_reservations_status
    ON inventory_reservations (status);


-- =============================================================================
-- TABLE: inventory_movements
-- Append-only ledger of every stock quantity change. Provides a full audit
-- trail without relying solely on audit_log JSONB diffs.
--
-- quantity_delta sign convention:
--   positive (+N) = stock added    (RECEIPT, TRANSFER_IN, RELEASE)
--   negative (−N) = stock removed  (SALE, RESERVATION, TRANSFER_OUT,
--                                   ADJUSTMENT when correcting downward)
--
-- movement_type values:
--   RECEIPT      — goods received from supplier / purchase order
--   ADJUSTMENT   — manual stock correction (positive or negative delta)
--   TRANSFER_OUT — stock leaving this location for another location
--   TRANSFER_IN  — stock arriving at this location from another location
--   RESERVATION  — stock quantity moved from available to reserved pool
--   RELEASE      — reserved stock returned to available pool (cancel/expiry)
--   SALE         — confirmed sale; stock permanently decremented
--
-- reference_id: nullable polymorphic reference to the source document
-- (reservation UUID, transfer UUID, order UUID, etc.). The application layer
-- populates this when a source document exists; it may be NULL for manual
-- ADJUSTMENT entries.
--
-- performed_by: login identity (username or service account name) of the actor
-- that triggered the movement. Never blank — enforced NOT NULL.
--
-- INSERT-ONLY: no UPDATE or DELETE is permitted on this table after insert.
-- No updated_at column exists by design. Any attempt to UPDATE or DELETE a
-- row here is an architecture violation and must also be preceded by a write
-- to audit_log (though the operation itself should be rejected by application
-- logic before reaching the DB).
-- =============================================================================
CREATE TABLE inventory_movements (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    variant_id    UUID        NOT NULL,
    location_id   UUID        NOT NULL
                              REFERENCES inventory_locations(id) ON DELETE RESTRICT,
    movement_type VARCHAR(20) NOT NULL
                  CHECK (movement_type IN (
                      'RECEIPT', 'ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN',
                      'RESERVATION', 'RELEASE', 'SALE'
                  )),
    quantity_delta INT         NOT NULL,
    reference_id  UUID,
    performed_by  VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    -- No updated_at: this table is insert-only, rows are never modified.
);

-- variant_id index: supports movement history queries for a given product
-- variant (e.g., "show all movements for SKU-1234").
CREATE INDEX idx_inv_movements_variant
    ON inventory_movements (variant_id);

-- location_id index: supports location-level movement reporting.
CREATE INDEX idx_inv_movements_location
    ON inventory_movements (location_id);

-- Partial reference_id index: supports reverse-lookup queries such as
-- "all movements associated with order X" or "movements for reservation Y".
-- Partial (WHERE reference_id IS NOT NULL) excludes manual adjustments with
-- no reference document, keeping the index compact.
CREATE INDEX idx_inv_movements_reference
    ON inventory_movements (reference_id)
    WHERE reference_id IS NOT NULL;

-- created_at DESC index: supports time-ordered movement history pagination.
-- DESC ordering matches the most common access pattern (recent movements first).
CREATE INDEX idx_inv_movements_created
    ON inventory_movements (created_at DESC);


-- =============================================================================
-- Development seed data
-- WARNING: These rows are for local development only.
--   Do not rely on these fixed UUIDs in production deployments. Integration
--   tests may reference these IDs directly; production environments should
--   populate locations through the Inventory API instead.
--
-- Seed row 1: Main Warehouse — primary stock location, not a buffer pool.
-- Seed row 2: Buffer Stock - Main — buffer pool for POS conflict resolution.
--   is_buffer_location = TRUE on row 2 means the POS sync conflict resolver
--   will draw from this location when available_quantity is exhausted at the
--   primary location and a tie-break is needed.
-- =============================================================================
INSERT INTO inventory_locations (id, name, external_reference_id, is_buffer_location, is_active)
VALUES
    ('L0000000-0000-0000-0000-000000000001', 'Main Warehouse',    NULL, FALSE, TRUE),
    ('L0000000-0000-0000-0000-000000000002', 'Buffer Stock - Main', NULL, TRUE,  TRUE);


-- =============================================================================
-- TODO: Future migrations that may be needed for the inventory module
--
-- V?__inventory_add_pos_sync_conflict_index.sql
--   When the POS sync conflict resolution query pattern is profiled under load,
--   an additional composite index may be needed to support the conflict
--   resolver's variant + location + status combination efficiently:
--     CREATE INDEX idx_inv_reservations_conflict_lookup
--         ON inventory_reservations (variant_id, location_id, status)
--         WHERE status IN ('PENDING', 'CONFIRMED');
--   Add this index only after confirming via EXPLAIN ANALYZE that the existing
--   per-column indexes are insufficient for the POS sync workload.
--
-- V?__inventory_add_transfers_table.sql
--   If multi-step stock transfers between locations require their own lifecycle
--   (DRAFT → IN_TRANSIT → RECEIVED → CANCELLED), create an
--   inventory_transfers table:
--     id              UUID PRIMARY KEY DEFAULT gen_random_uuid()
--     from_location_id UUID NOT NULL REFERENCES inventory_locations(id)
--     to_location_id   UUID NOT NULL REFERENCES inventory_locations(id)
--     variant_id       UUID NOT NULL
--     quantity         INT  NOT NULL CHECK (quantity > 0)
--     status           VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
--                      CHECK (status IN ('DRAFT','IN_TRANSIT','RECEIVED','CANCELLED'))
--     initiated_by     VARCHAR(255) NOT NULL
--     created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
--     updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
--   The inventory_movements table would then record TRANSFER_OUT and
--   TRANSFER_IN rows referencing the transfer UUID as reference_id.
--   This is deferred to post-MVP; for MVP, transfers are handled as paired
--   TRANSFER_OUT / TRANSFER_IN movement entries without a parent record.
--
-- V?__inventory_add_stock_alerts_table.sql
--   If persistent low-stock alerts (beyond the low_stock_threshold comparison
--   done at read time) are required, create an inventory_stock_alerts table to
--   record when a threshold was breached and whether the alert has been
--   acknowledged. For MVP, low-stock events are published via RabbitMQ on the
--   inventory.exchange routing key inventory.stock.low and consumed by the
--   Notification module.
-- =============================================================================
