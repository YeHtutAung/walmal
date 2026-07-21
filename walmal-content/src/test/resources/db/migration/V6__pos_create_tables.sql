-- =============================================================================
-- Migration : V6__pos_create_tables.sql
-- Module    : walmal-pos (POS Module)
-- Date      : 2026-05-21
-- Description:
--   Creates the four tables owned exclusively by the walmal-pos module:
--     pos_terminals, pos_sales, pos_sale_items, pos_sync_queue
--
-- Table ownership note:
--   ALL four tables in this migration are owned by walmal-pos.
--   No other module may JOIN to these tables or declare FK constraints that
--   reference columns in these tables. Cross-module references (e.g., an
--   Inventory table referencing a POS terminal, or an Order table referencing
--   a POS sale) must store the UUID value only, with NO FK constraint declared
--   in the referencing table.
--
-- Cross-module UUID reference note:
--   The following columns store UUIDs that logically refer to entities in
--   other modules. No FK constraints are declared on these columns by design —
--   enforcing cross-module referential integrity at the DB layer would couple
--   bounded contexts and prevent independent module evolution.
--
--     pos_terminals.location_id      → inventory_locations.id  (walmal-inventory)
--     pos_sales.online_order_id      → order_orders.id         (walmal-order)
--     pos_sales.cashier_id           → auth_users.id           (walmal-auth)
--     pos_sale_items.variant_id      → product_variants.id     (walmal-product)
--     pos_sale_items.location_id     → inventory_locations.id  (walmal-inventory)
--
--   Application-layer validation via PosTerminalService, PosSaleService, and
--   their collaborating interfaces (InventoryReservationService,
--   ProductCatalogService, ProductPricingService) is the enforcement mechanism
--   for cross-module reference integrity.
--
-- Intra-module FK notes:
--   pos_sales.terminal_id    → pos_terminals.id   (same module — FK declared)
--   pos_sale_items.sale_id   → pos_sales.id        (same module — FK declared)
--   pos_sync_queue.terminal_id → pos_terminals.id  (same module — FK declared)
--   ON DELETE RESTRICT is used on all intra-module FKs: a terminal cannot be
--   deleted while it has sales or sync queue entries; a sale cannot be deleted
--   while it has line items. Hard deletes in this module are architecture
--   violations for exactly this reason (see audit log and soft-complete notes).
--
-- updated_at management note:
--   updated_at has no database-level trigger on any table in this migration.
--   It is maintained by the JPA @PreUpdate lifecycle callback in each entity.
--   Direct SQL executed outside the ORM (e.g., hotfix scripts, data migrations)
--   MUST manually set updated_at = NOW() to keep the column accurate.
--   pos_sale_items and pos_sync_queue have specific insert-only /
--   soft-complete semantics — see per-table notes below.
--
-- Status / type columns note:
--   All status and mode columns are stored as VARCHAR with CHECK constraints
--   rather than PostgreSQL ENUM types. This avoids the ALTER TYPE ADD VALUE
--   limitation in PostgreSQL, which cannot be rolled back inside a transaction
--   and causes Flyway repair issues. To add new values in a future migration,
--   drop and recreate the CHECK constraint — no type surgery needed.
--   This is the same rationale applied in V3 (product), V4 (inventory), and
--   V5 (order).
--
-- insert-only note (pos_sale_items):
--   pos_sale_items is insert-only after sale creation. No row in this table is
--   ever UPDATE'd after it is written — price, quantity, and product snapshots
--   are fixed at the moment of sale. No updated_at column exists on this table
--   by design.
--
-- Soft-complete note (pos_sync_queue):
--   Rows in pos_sync_queue are NEVER hard-deleted. Once created, a row is
--   either soft-completed by transitioning to PROCESSED or FAILED status.
--   This preserves the full sync history for audit and conflict resolution
--   purposes. Any DELETE from this table is an architecture violation and must
--   be blocked at the application layer. ON DELETE RESTRICT on the terminal_id
--   FK prevents cascade deletes from pos_terminals. The schema intentionally
--   has no DELETE path — only status updates are permitted after insert.
--
-- Snapshot columns note (pos_sale_items):
--   product_name_snapshot and sku_snapshot are copied from ProductCatalogService
--   at the moment the sale is recorded. price_at_sale is copied from
--   ProductPricingService at the same moment. These snapshots decouple the sale
--   record from future product changes: if a product name, SKU, or price changes
--   after a sale is recorded, the POS history remains accurate.
--
-- sale_mode / online_order_id constraint note (pos_sales):
--   The CHECK constraint chk_pos_sales_mode_order_id enforces the rule that
--   online sales must carry an online_order_id (the corresponding order UUID from
--   walmal-order), and offline sales must not carry one. This prevents ambiguous
--   rows that claim to be ONLINE but carry no order reference, or OFFLINE rows
--   that spuriously reference a web order.
--
-- Audit log compliance note (APPLICATION-LAYER CONCERN — NOT A DB TRIGGER):
--   The following POS operations are classified as destructive and MUST write
--   to the audit_log table BEFORE executing the DB mutation. This enforcement
--   is the responsibility of PosTerminalServiceImpl and PosSaleServiceImpl —
--   there are no DB triggers here.
--
--     deactivateTerminal()           — UPDATE pos_terminals.status = 'INACTIVE'
--                                      (destructive status change; an INACTIVE
--                                       terminal cannot accept sales until
--                                       reactivated, which itself requires an
--                                       audit log entry for traceability)
--     markSyncFailed()               — UPDATE pos_sync_queue.status = 'FAILED'
--                                      (marks a sync entry as permanently failed;
--                                       the offline sale data is abandoned)
--     admin UPDATE pos_sales.sync_status — any manual hotfix correction to a
--                                      sale's sync_status outside the normal
--                                      sync lifecycle (e.g., overriding
--                                      CONFLICT_RESOLVED to SYNCED after
--                                      investigation)
--
--   Insert operations (terminal registration, sale creation, sync queue
--   insertion) and normal sync status transitions (PENDING → PROCESSED) are
--   NOT destructive. No audit_log write is required for them.
-- =============================================================================


-- =============================================================================
-- TABLE: pos_terminals
-- Represents a physical POS register at a store location. A terminal must be
-- registered and ACTIVE before it can record sales or submit sync payloads.
--
-- location_id is a cross-module reference to inventory_locations.id (no FK).
-- The Inventory module's InventoryLocationService is called at application
-- layer to validate the location UUID when a terminal is registered.
--
-- last_seen_at is updated whenever the terminal makes any authenticated API
-- call (sale recording, sync submission, heartbeat). Useful for detecting
-- terminals that have been offline for an extended period.
--
-- status lifecycle:
--   ACTIVE   — terminal is operational and may record sales
--   INACTIVE — terminal has been decommissioned; sales are rejected at the
--              API layer. Deactivation requires an audit_log write (see above).
-- =============================================================================
CREATE TABLE pos_terminals (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(200) NOT NULL,
    location_id  UUID         NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                              CHECK (status IN ('ACTIVE', 'INACTIVE')),
    last_seen_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- location_id index: supports "find all terminals at this store location"
-- queries used by the store management dashboard and POS sync conflict resolver
-- (which needs to know which terminal submitted a conflicting offline sale).
CREATE INDEX idx_pos_terminals_location
    ON pos_terminals (location_id);

-- Partial index on ACTIVE status: used by the terminal listing endpoint and
-- by the POS sync processor when validating that a submitting terminal is still
-- operational. Partial (WHERE status = 'ACTIVE') keeps the index compact —
-- INACTIVE terminals accumulate over time and should not bloat the index.
CREATE INDEX idx_pos_terminals_active
    ON pos_terminals (status)
    WHERE status = 'ACTIVE';


-- =============================================================================
-- TABLE: pos_sales
-- Represents a completed sale recorded by a POS terminal. One row per sale
-- transaction. The sale aggregate root for the POS bounded context.
--
-- sale_mode distinguishes how the sale was initiated:
--   ONLINE  — sale originated from the web store; online_order_id is set.
--             The POS terminal records this after receiving confirmation from
--             the Order module (via PosSaleService interface).
--   OFFLINE — sale recorded on the device while disconnected; online_order_id
--             is NULL. After reconnection, the device submits the payload via
--             pos_sync_queue for server-side validation and conflict resolution.
--
-- sold_at uses the device clock for OFFLINE sales and the server clock for
-- ONLINE sales. The POS sync conflict resolver uses sold_at to determine
-- priority when a device sale and a web order overlap (earlier timestamp wins
-- for the same variant at the same location).
--
-- sync_status lifecycle:
--   N_A               — ONLINE sale; no sync needed. Set at creation.
--   PENDING           — OFFLINE sale queued for sync; not yet processed.
--   SYNCED            — OFFLINE sale successfully validated and applied.
--   CONFLICT_RESOLVED — OFFLINE sale had a stock conflict; conflict resolver
--                       determined the outcome (may have cancelled a web order).
--   FAILED            — OFFLINE sale sync permanently failed (e.g., invalid
--                       payload, unresolvable conflict). Requires audit_log write.
--
-- cashier_id is a cross-module reference to auth_users.id (no FK). Set to the
-- UUID of the logged-in cashier if the terminal enforces per-cashier sessions;
-- NULL when the terminal operates in anonymous / kiosk mode.
-- =============================================================================
CREATE TABLE pos_sales (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    terminal_id     UUID          NOT NULL
                                  REFERENCES pos_terminals(id) ON DELETE RESTRICT,
    online_order_id UUID,
    sold_at         TIMESTAMPTZ   NOT NULL,
    total_amount    NUMERIC(12,2) NOT NULL CHECK (total_amount >= 0),
    currency        VARCHAR(3)    NOT NULL,
    sale_mode       VARCHAR(10)   NOT NULL
                                  CHECK (sale_mode IN ('ONLINE', 'OFFLINE')),
    sync_status     VARCHAR(25)   NOT NULL DEFAULT 'N_A'
                                  CHECK (sync_status IN (
                                      'N_A', 'PENDING', 'SYNCED',
                                      'CONFLICT_RESOLVED', 'FAILED'
                                  )),
    cashier_id      UUID,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    -- Enforce sale_mode / online_order_id coherence:
    --   ONLINE sales must carry the corresponding web order UUID.
    --   OFFLINE sales must not carry a web order reference.
    CONSTRAINT chk_pos_sales_mode_order_id CHECK (
        (sale_mode = 'ONLINE'  AND online_order_id IS NOT NULL) OR
        (sale_mode = 'OFFLINE' AND online_order_id IS NULL)
    )
);

-- terminal_id index: primary access pattern — "all sales for this terminal",
-- used by the terminal history endpoint and by the POS sync conflict resolver
-- when scanning a terminal's recent OFFLINE sales to find overlapping stock.
CREATE INDEX idx_pos_sales_terminal
    ON pos_sales (terminal_id);

-- sold_at DESC index: supports time-ordered sales history queries such as
-- "sales in the last hour" and daily/shift-level sales reporting.
CREATE INDEX idx_pos_sales_sold_at
    ON pos_sales (sold_at DESC);

-- Partial sync_status index for PENDING and FAILED sales: used by the sync
-- operations dashboard and the background sync processor to find sales that
-- still need to be processed or have experienced a failure. Partial index
-- excludes the majority of rows (N_A and SYNCED) which require no further action.
CREATE INDEX idx_pos_sales_sync_status
    ON pos_sales (sync_status)
    WHERE sync_status IN ('PENDING', 'FAILED');

-- Partial online_order_id index: supports reverse-lookup queries from the Order
-- module — "which POS sale corresponds to this order?" Used during conflict
-- resolution and order reconciliation. Partial (WHERE NOT NULL) avoids indexing
-- the majority of OFFLINE sales which carry no order reference.
CREATE INDEX idx_pos_sales_order
    ON pos_sales (online_order_id)
    WHERE online_order_id IS NOT NULL;


-- =============================================================================
-- TABLE: pos_sale_items
-- One row per line item within a POS sale. Insert-only after sale creation —
-- rows are never modified after the sale is recorded.
--
-- variant_id is a cross-module reference to product_variants.id (no FK).
-- product_name_snapshot, sku_snapshot, and price_at_sale are copied from the
-- Product module's service interfaces at sale time. See snapshot columns note
-- in the file header.
--
-- location_id is a cross-module reference to inventory_locations.id (no FK).
-- It records which physical location's stock was decremented for this line item.
-- Stored here so that, if the sale needs to be reversed (e.g., a sync conflict
-- voids the OFFLINE sale), the correct location_id is available without a
-- cross-module JOIN.
--
-- subtotal = price_at_sale * quantity, computed and stored at insert time.
-- Storing the computed value makes the sale record self-contained and stable
-- even if the price formula changes in future.
--
-- No updated_at: this table is insert-only; rows are never modified after the
-- sale is created. Price, quantity, and product snapshots are fixed at sale
-- time. This matches the insert-only contract used by order_items in V5.
-- =============================================================================
CREATE TABLE pos_sale_items (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    sale_id               UUID          NOT NULL
                                        REFERENCES pos_sales(id) ON DELETE RESTRICT,
    variant_id            UUID          NOT NULL,
    product_name_snapshot VARCHAR(500)  NOT NULL,
    sku_snapshot          VARCHAR(100)  NOT NULL,
    quantity              INT           NOT NULL CHECK (quantity > 0),
    price_at_sale         NUMERIC(12,2) NOT NULL CHECK (price_at_sale >= 0),
    currency              VARCHAR(3)    NOT NULL,
    subtotal              NUMERIC(12,2) NOT NULL CHECK (subtotal >= 0),
    location_id           UUID          NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW()
    -- No updated_at: this table is insert-only. pos_sale_items rows are never
    -- modified after the parent sale is recorded. price_at_sale, quantity, and
    -- the product name/SKU snapshots are fixed permanently at sale creation time.
);

-- sale_id index: primary access pattern — "all line items in this sale".
-- Used on every sale detail read and during OFFLINE sale void operations to
-- enumerate which stock decrements need to be reversed.
CREATE INDEX idx_pos_sale_items_sale
    ON pos_sale_items (sale_id);

-- variant_id index: reverse lookup — "which sales include this variant?"
-- Used by the POS sync conflict resolver when checking whether a given variant
-- was sold in any recent OFFLINE sale that overlaps with a pending web order,
-- and by product discontinuation workflows.
CREATE INDEX idx_pos_sale_items_variant
    ON pos_sale_items (variant_id);


-- =============================================================================
-- TABLE: pos_sync_queue
-- Holds raw offline sale payloads submitted by POS terminals after reconnection.
-- One row per sync submission. The sync processor validates the payload, applies
-- stock movements, resolves conflicts, and transitions rows to PROCESSED or FAILED.
--
-- SOFT-COMPLETE ONLY — ROWS ARE NEVER HARD-DELETED:
--   This table is an append-only operational log. Once a row is inserted it may
--   only transition to PROCESSED (success) or FAILED (permanent failure). There
--   is no DELETE path. ON DELETE RESTRICT on terminal_id prevents cascade deletes
--   from pos_terminals from silently removing sync history. Any attempt to
--   hard-delete a row in this table is an architecture violation and must be
--   rejected at the application layer before reaching the DB.
--   This is the same append-only principle applied to inventory_movements in V4.
--
-- sale_data JSONB: raw payload from the offline device, exactly as received.
-- Schema validation (required fields, type checking, plausibility) is performed
-- at the application layer by PosSyncProcessorService before any DB mutations
-- are applied. Storing the raw payload preserves the original device data for
-- audit and reprocessing if the validation logic changes.
--
-- failure_reason: populated only when status = 'FAILED'. Records a short
-- human-readable explanation of why the payload could not be processed
-- (e.g., 'STOCK_EXHAUSTED', 'INVALID_PAYLOAD', 'VARIANT_NOT_FOUND').
-- NULL when status is PENDING or PROCESSED.
-- =============================================================================
CREATE TABLE pos_sync_queue (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    terminal_id    UUID        NOT NULL
                               REFERENCES pos_terminals(id) ON DELETE RESTRICT,
    sale_data      JSONB       NOT NULL,
    submitted_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at   TIMESTAMPTZ,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                               CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED')),
    failure_reason TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- terminal_id index: supports "show sync history for this terminal" queries used
-- by the store management dashboard and by support engineers investigating sync
-- failures for a specific device.
CREATE INDEX idx_pos_sync_terminal
    ON pos_sync_queue (terminal_id);

-- Partial composite index for PENDING entries per terminal: the primary index
-- used by PosSyncProcessorService to claim and process the next batch of pending
-- entries for a specific terminal, ordered by submission time. Partial
-- (WHERE status = 'PENDING') keeps the index compact — the majority of rows
-- will be PROCESSED or FAILED and do not need to be scanned by the processor.
CREATE INDEX idx_pos_sync_pending
    ON pos_sync_queue (terminal_id, submitted_at)
    WHERE status = 'PENDING';

-- Partial status index for PENDING and FAILED entries: used by the sync
-- operations dashboard to surface entries that require attention across all
-- terminals. Partial index excludes PROCESSED rows (the large majority at steady
-- state) and keeps dashboard queries fast.
CREATE INDEX idx_pos_sync_status
    ON pos_sync_queue (status)
    WHERE status IN ('PENDING', 'FAILED');


-- =============================================================================
-- Development seed data
-- WARNING: These rows are for local development only.
--   Do not rely on these fixed UUIDs in production deployments. Integration
--   tests may reference these IDs directly; production environments should
--   register terminals through the POS Terminal API instead.
--
-- Seed: one ACTIVE POS terminal linked to the Main Warehouse inventory location.
--   location_id 'a0000000-0000-0000-0000-000000000001' is the 'Main Warehouse'
--   row seeded in V4__inventory_create_tables.sql. This UUID is used here as a
--   cross-module reference (no FK constraint) to demonstrate the pattern.
-- =============================================================================
INSERT INTO pos_terminals (id, name, location_id, status)
VALUES (
    'b0000000-0000-0000-0000-000000000001',
    'Main Store Terminal 1',
    'a0000000-0000-0000-0000-000000000001',
    'ACTIVE'
);


-- =============================================================================
-- TODO: Future migrations that may be needed for the POS module
--
-- V?__pos_add_shift_table.sql
--   If cashier shift tracking is required (clock-in/clock-out per cashier per
--   terminal, with per-shift sales summaries), create a pos_shifts table:
--     id           UUID PRIMARY KEY DEFAULT gen_random_uuid()
--     terminal_id  UUID NOT NULL REFERENCES pos_terminals(id) ON DELETE RESTRICT
--     cashier_id   UUID NOT NULL  -- cross-module ref to auth_users.id (no FK)
--     opened_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
--     closed_at    TIMESTAMPTZ
--     opening_float NUMERIC(12,2) NOT NULL DEFAULT 0
--     closing_float NUMERIC(12,2)
--     status       VARCHAR(20) NOT NULL DEFAULT 'OPEN'
--                  CHECK (status IN ('OPEN', 'CLOSED'))
--     created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
--     updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
--   pos_sales would then carry a nullable shift_id referencing pos_shifts,
--   added via a separate migration to avoid breaking the MVP schema.
--
-- V?__pos_add_background_sync_job.sql
--   When the current HTTP-based sync submission (terminal POSTs to
--   /api/v1/pos/sync) is replaced by an async job processor consuming from a
--   RabbitMQ queue, this migration would add a pos_sync_job_config table to
--   store per-terminal polling intervals, retry limits, and back-off settings.
--   For MVP, sync is driven by the terminal's HTTP reconnect flow; no
--   server-side job scheduling is needed.
--
-- V?__pos_add_receipt_table.sql
--   If digital receipt storage is required (for email/SMS receipt dispatch or
--   customer self-service receipt lookup), create a pos_receipts table:
--     id          UUID PRIMARY KEY DEFAULT gen_random_uuid()
--     sale_id     UUID NOT NULL REFERENCES pos_sales(id) ON DELETE RESTRICT
--     receipt_url VARCHAR(2048) NOT NULL  -- MinIO URL; no BLOB stored in DB
--     sent_to     VARCHAR(255)            -- email or phone (anonymised after 90d)
--     sent_at     TIMESTAMPTZ
--     created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
--   Receipt file content is stored in MinIO (via FileStorageService interface);
--   only the URL is persisted here, following the platform-wide MinIO pattern.
-- =============================================================================
