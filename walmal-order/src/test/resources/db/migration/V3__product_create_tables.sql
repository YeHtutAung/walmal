-- =============================================================================
-- Migration : V3__product_create_tables.sql
-- Module    : walmal-product (Product Module)
-- Date      : 2026-05-20
-- Description:
--   Creates the five tables owned exclusively by the walmal-product module:
--     product_categories, product_products, product_variants,
--     product_prices, product_images
--
-- Table ownership note:
--   ALL five tables in this migration are owned by walmal-product.
--   No other module may JOIN to these tables or declare FK constraints that
--   reference columns in these tables. Cross-module references (e.g., an
--   Inventory or Order table referencing a variant) must store the UUID value
--   only, with NO FK constraint declared in the referencing table.
--
-- Intra-module FK note:
--   FK constraints between tables within this migration ARE permitted and
--   declared. All five tables belong to the same bounded context, so
--   referential integrity is enforced at the database layer here.
--
-- updated_at management note:
--   updated_at has no database-level trigger. It is maintained by the JPA
--   @PreUpdate lifecycle callback in each entity. Direct SQL executed outside
--   the ORM (e.g., hotfix scripts, data migrations) MUST manually set
--   updated_at = NOW() to keep the column accurate.
--
-- Status column note:
--   status on product_products and product_variants is stored as VARCHAR with
--   a CHECK constraint rather than a PostgreSQL ENUM type. This avoids the
--   ALTER TYPE ADD VALUE limitation in PostgreSQL, which cannot be rolled back
--   inside a transaction and causes Flyway repair issues. To add new status
--   values in a future migration, drop and recreate the CHECK constraint — no
--   type surgery needed.
--
-- price audit note:
--   product_prices intentionally omits created_at/updated_at columns.
--   Price mutations (UPDATE) are destructive operations and must be preceded
--   by a write to the audit_log table before execution, per the platform-wide
--   Audit Log rule. The audit_log row captures old_value/new_value JSONB so
--   full price history is retained without a separate price-history table.
--
-- MinIO storage note:
--   product_images.storage_key stores the MinIO object key only (not a full
--   URL). The FileStorageService interface resolves this key to a presigned or
--   CDN URL at read time and populates cdn_url. Storing keys rather than URLs
--   decouples the schema from bucket configuration changes.
-- =============================================================================


-- =============================================================================
-- TABLE: product_categories
-- Self-referencing hierarchy. parent_id = NULL denotes a root category.
-- ON DELETE RESTRICT prevents removal of a category that still has children.
-- =============================================================================
CREATE TABLE product_categories (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(150) NOT NULL,
    slug       VARCHAR(120) NOT NULL,
    parent_id  UUID         REFERENCES product_categories(id) ON DELETE RESTRICT,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- slug must be globally unique to support clean URL routing (/c/{slug})
CREATE UNIQUE INDEX idx_product_categories_slug      ON product_categories (slug);
-- parent_id index accelerates fetching direct children of a category
CREATE INDEX        idx_product_categories_parent_id ON product_categories (parent_id);
-- is_active index supports admin list queries filtered by active status
CREATE INDEX        idx_product_categories_is_active ON product_categories (is_active);


-- =============================================================================
-- TABLE: product_products
-- category_id FK is declared here because product_products and
-- product_categories are in the same module. Other modules must NOT declare
-- FKs against product_products.
-- =============================================================================
CREATE TABLE product_products (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID         NOT NULL
                             REFERENCES product_categories(id) ON DELETE RESTRICT,
    name        VARCHAR(300) NOT NULL,
    slug        VARCHAR(300) NOT NULL,
    description TEXT,
    brand       VARCHAR(150),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                             CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- slug must be globally unique to support clean URL routing (/p/{slug})
CREATE UNIQUE INDEX idx_product_products_slug        ON product_products (slug);
-- category_id index supports listing products within a category
CREATE INDEX        idx_product_products_category_id ON product_products (category_id);
-- status index supports active/inactive filtering without full table scans
CREATE INDEX        idx_product_products_status      ON product_products (status);
-- brand index supports brand-filtered browse queries
CREATE INDEX        idx_product_products_brand       ON product_products (brand);


-- =============================================================================
-- TABLE: product_variants
-- sku is globally unique — this is the stable identifier that Inventory and
-- Order modules store as a plain UUID/VARCHAR column (no FK constraint on
-- their side).
-- attributes JSONB stores arbitrary key-value dimension data (e.g.,
-- {"size": "L", "colour": "Blue"}) without requiring schema changes per
-- product type.
-- =============================================================================
CREATE TABLE product_variants (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID         NOT NULL
                            REFERENCES product_products(id) ON DELETE CASCADE,
    sku        VARCHAR(100) NOT NULL,
    name       VARCHAR(200) NOT NULL,
    barcode    VARCHAR(50),
    attributes JSONB,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- sku uniqueness is enforced at the DB layer — application-layer checks alone
-- are insufficient given concurrent inserts
CREATE UNIQUE INDEX idx_product_variants_sku        ON product_variants (sku);
-- product_id index supports fetching all variants for a product
CREATE INDEX        idx_product_variants_product_id ON product_variants (product_id);
-- barcode index supports POS barcode scan lookups
CREATE INDEX        idx_product_variants_barcode    ON product_variants (barcode);
-- status index supports active-only variant filtering
CREATE INDEX        idx_product_variants_status     ON product_variants (status);


-- =============================================================================
-- TABLE: product_prices
-- One price row per variant, enforced at the DB layer by the UNIQUE constraint
-- on variant_id.  Price UPDATEs are destructive; callers MUST write to
-- audit_log before executing an UPDATE on this table.
-- No created_at/updated_at: audit_log captures full history via old_value/
-- new_value JSONB.
-- effective_from allows the application to record when a price took effect
-- without requiring a separate price-history table for MVP.
-- =============================================================================
CREATE TABLE product_prices (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    variant_id     UUID          NOT NULL UNIQUE
                                 REFERENCES product_variants(id) ON DELETE CASCADE,
    amount         NUMERIC(10,2) NOT NULL CHECK (amount >= 0),
    currency       VARCHAR(3)    NOT NULL DEFAULT 'USD',
    effective_from TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- variant_id uniqueness is already covered by the UNIQUE constraint above;
-- a separate B-tree index is therefore not needed on variant_id alone.
-- currency index supports potential multi-currency reporting queries
CREATE INDEX idx_product_prices_currency ON product_prices (currency);


-- =============================================================================
-- TABLE: product_images
-- product_id and variant_id FKs are both within this module — FKs declared.
-- variant_id ON DELETE SET NULL: deleting a variant orphans the image to the
-- product level rather than cascading a delete, preserving product gallery
-- integrity.
-- Partial unique index on (product_id) WHERE is_primary = TRUE enforces
-- exactly one primary image per product at the DB layer. The application must
-- set is_primary = FALSE on the current primary before promoting a new one, or
-- execute both in the same transaction.
-- =============================================================================
CREATE TABLE product_images (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id    UUID         NOT NULL
                               REFERENCES product_products(id) ON DELETE CASCADE,
    variant_id    UUID         REFERENCES product_variants(id) ON DELETE SET NULL,
    storage_key   VARCHAR(512) NOT NULL,
    cdn_url       VARCHAR(512),
    alt_text      VARCHAR(255),
    display_order INTEGER      NOT NULL DEFAULT 0,
    is_primary    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Composite index supports ordered image listing for a product
CREATE INDEX idx_product_images_product_display
    ON product_images (product_id, display_order);
-- variant_id index supports fetching images scoped to a specific variant
CREATE INDEX idx_product_images_variant_id
    ON product_images (variant_id);
-- Partial unique index: at most one row per product may have is_primary = TRUE
-- This index name is referenced in application exception handling.
CREATE UNIQUE INDEX idx_product_images_primary_per_product
    ON product_images (product_id)
    WHERE is_primary = TRUE;


-- =============================================================================
-- Development seed data
-- WARNING: These rows are for local development only.
--   Do not rely on these fixed UUIDs in production deployments. Integration
--   tests may reference these IDs directly; production environments should
--   populate categories through the Product API instead.
-- =============================================================================
INSERT INTO product_categories (id, name, slug, parent_id, is_active) VALUES
    ('c0000000-0000-0000-0000-000000000001', 'Electronics', 'electronics', NULL, TRUE),
    ('c0000000-0000-0000-0000-000000000002', 'Apparel',      'apparel',      NULL, TRUE);


-- =============================================================================
-- TODO: Future migrations that may be needed for the product module
--
-- V?__product_add_fts_index.sql
--   Add a full-text search index to support keyword search across product
--   name, description, and brand without an external search engine for MVP:
--     ALTER TABLE product_products
--       ADD COLUMN search_vector TSVECTOR
--         GENERATED ALWAYS AS (
--           to_tsvector('english',
--             coalesce(name, '') || ' ' ||
--             coalesce(description, '') || ' ' ||
--             coalesce(brand, '')
--           )
--         ) STORED;
--     CREATE INDEX idx_product_products_fts ON product_products USING GIN (search_vector);
--   Consider whether a separate search module (e.g., OpenSearch) is preferable
--   before adding this; GIN indexes have write-amplification cost.
--
-- V?__product_categories_closure_table.sql
--   If category hierarchy queries deeper than one level become a performance
--   concern (e.g., "all products in Electronics including sub-categories"),
--   replace the single parent_id self-reference with a closure table:
--     product_category_paths (ancestor_id UUID, descendant_id UUID, depth INT)
--   This enables O(1) subtree queries at the cost of additional write logic.
--   The current parent_id design is sufficient for MVP assuming shallow
--   (2–3 level) hierarchies.
--
-- V?__product_add_tags.sql
--   If tag-based filtering is required, add a product_tags table:
--     product_tags (product_id UUID, tag VARCHAR(80), PRIMARY KEY (product_id, tag))
--   rather than storing tags as a JSONB array, to enable indexed tag queries.
--
-- V?__product_prices_history.sql
--   If point-in-time price lookup is required (e.g., for order repricing
--   audits), create a product_price_history table:
--     id, variant_id, amount, currency, effective_from, superseded_at
--   For MVP, audit_log provides sufficient price change history.
-- =============================================================================
