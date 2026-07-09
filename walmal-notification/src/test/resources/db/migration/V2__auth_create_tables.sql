-- =============================================================================
-- Migration : V2__auth_create_tables.sql
-- Module    : walmal-auth (Authentication Module)
-- Date      : 2026-05-20
-- Description:
--   Creates the auth_users table owned exclusively by the walmal-auth module.
--   No other module may JOIN to this table or reference auth_users.id via a
--   foreign key constraint. Cross-module identity references must store the
--   UUID value only, with no FK declared in the referencing table.
--
-- Refresh tokens are intentionally absent from this migration.
--   Per ADR-2, refresh token state is managed entirely in Redis (TTL-based).
--   No auth_refresh_tokens table exists or will be created in this module.
--
-- updated_at management note:
--   updated_at has no database-level trigger. It is maintained by the JPA
--   @PreUpdate lifecycle callback in AuthUser entity. Any direct SQL updates
--   executed outside the ORM (e.g., hotfix scripts, data migrations) MUST
--   manually set updated_at = NOW() to keep the column accurate.
--
-- Role storage note:
--   role is stored as VARCHAR with a CHECK constraint rather than a PostgreSQL
--   ENUM type. This avoids the ALTER TYPE ADD VALUE limitation in PostgreSQL,
--   which cannot be rolled back inside a transaction and causes Flyway repair
--   issues. When new roles are required, drop the CHECK constraint and recreate
--   it with the expanded value list in a new migration — no type surgery needed.
--
-- password_hash length note:
--   BCrypt at strength 12 produces a 60-character string. VARCHAR(72) provides
--   12 characters of margin against future BCrypt variant output growth while
--   still rejecting obviously malformed values at the database layer.
-- =============================================================================

CREATE TABLE auth_users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(50)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(72)  NOT NULL,
    role          VARCHAR(20)  NOT NULL
                  CHECK (role IN ('ADMIN', 'STAFF', 'CASHIER', 'CUSTOMER')),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Unique indexes enforce one account per username and one account per email
-- address at the database layer, independent of application-level validation.
CREATE UNIQUE INDEX idx_auth_users_username ON auth_users (username);
CREATE UNIQUE INDEX idx_auth_users_email    ON auth_users (email);

-- Non-unique indexes support role-based queries (e.g., list all CASHIER users)
-- and active/inactive account filtering without full table scans.
CREATE INDEX idx_auth_users_role   ON auth_users (role);
CREATE INDEX idx_auth_users_active ON auth_users (is_active);

-- =============================================================================
-- Development seed data
-- WARNING: This seed row is for local development only.
--   The password hash below encodes the string 'admin123' with BCrypt
--   strength 12. Replace this hash with a securely generated value before
--   any non-development deployment. The module-builder's integration test
--   suite will verify and regenerate this hash at build time.
--
-- To regenerate:
--   import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
--   new BCryptPasswordEncoder(12).encode("admin123");
-- =============================================================================
INSERT INTO auth_users (id, username, email, password_hash, role, is_active)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'admin',
    'admin@walmal.local',
    '$2a$12$LcCLURJtEKo3MVIyiynmue8LkHVD6VGjN8FHbdnnrxpicweFLMNOa',
    'ADMIN',
    TRUE
);

-- =============================================================================
-- TODO: Future migrations that may be needed for the auth module
--
-- V?__auth_add_password_reset_tokens.sql
--   If a stateful password-reset flow is required (e.g., email-link reset with
--   single-use tokens), create an auth_password_reset_tokens table:
--     id          UUID PRIMARY KEY DEFAULT gen_random_uuid()
--     user_id     UUID NOT NULL  -- no FK constraint, references auth_users.id
--     token_hash  VARCHAR(72) NOT NULL
--     expires_at  TIMESTAMPTZ NOT NULL
--     used_at     TIMESTAMPTZ
--     created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
--   Token validation logic must check used_at IS NULL AND expires_at > NOW().
--   Redis TTL is still preferred if the reset window is short (< 1 hour).
--
-- V?__auth_add_failed_login_tracking.sql
--   If account lockout after N failed attempts is required, add:
--     failed_login_count  INTEGER     NOT NULL DEFAULT 0
--     locked_until        TIMESTAMPTZ
--   to auth_users, or create a separate auth_login_attempts table for a
--   non-destructive audit trail of failed attempts.
--
-- V?__auth_add_mfa_fields.sql
--   If MFA (TOTP) is added post-MVP:
--     mfa_secret      VARCHAR(64)  -- TOTP shared secret, encrypted at rest
--     mfa_enabled     BOOLEAN NOT NULL DEFAULT FALSE
--   This is out of scope for MVP but listed here for forward planning.
-- =============================================================================
