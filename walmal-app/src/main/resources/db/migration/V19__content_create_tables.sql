-- =============================================================================
-- TABLE: content_home
-- Home-page CMS document, one row per lifecycle status. The whole editorial
-- document (hero, category tiles, promo) is stored as a single JSONB value;
-- structure is validated at the application layer (Bean Validation on the
-- request DTO). Publishing copies the DRAFT row's content into the PUBLISHED row.
-- Owned exclusively by the walmal-content module.
-- =============================================================================
CREATE TABLE content_home (
    status      VARCHAR(16)  PRIMARY KEY
                             CHECK (status IN ('DRAFT', 'PUBLISHED')),
    content     JSONB        NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by  VARCHAR(255) NOT NULL
);
-- No seed rows: absence of the PUBLISHED row is the signal the storefront uses
-- to fall back to its built-in static home content.
