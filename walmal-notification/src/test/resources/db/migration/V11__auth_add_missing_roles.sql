-- =============================================================================
-- Migration : V11__auth_add_missing_roles.sql
-- Module    : walmal-auth (Authentication Module)
-- Date      : 2026-05-23
-- Description:
--   Expands the auth_users.role CHECK constraint to include the three roles
--   that were missing from the initial schema:
--     WAREHOUSE_MANAGER, WAREHOUSE_STAFF, POS_OPERATOR
--
--   V10 seeded a warehouse_mgr user with STAFF role as a workaround because
--   WAREHOUSE_MANAGER was not yet in the constraint. That user is updated here.
--
--   Also seeds one test user per new role for local development and integration
--   testing. Passwords are BCrypt strength-12.
--     warehouse_manager  → wm123456
--     warehouse_staff    → ws123456
--     pos_operator       → pos123456
--
--   Role constraint pattern (per V2 comment):
--     DROP the existing CHECK and recreate with the expanded list.
--     PostgreSQL CHECK constraints cannot be altered in place.
-- =============================================================================

-- Step 1 — expand the CHECK constraint on auth_users.role
ALTER TABLE auth_users
    DROP CONSTRAINT IF EXISTS auth_users_role_check;

ALTER TABLE auth_users
    ADD CONSTRAINT auth_users_role_check
        CHECK (role IN (
            'ADMIN',
            'STAFF',
            'CASHIER',
            'CUSTOMER',
            'WAREHOUSE_MANAGER',
            'WAREHOUSE_STAFF',
            'POS_OPERATOR'
        ));

-- Step 2 — promote the V10-seeded warehouse_mgr user to the correct role
UPDATE auth_users
SET    role       = 'WAREHOUSE_MANAGER',
       updated_at = NOW()
WHERE  username = 'warehouse_mgr';

-- Step 3 — seed one test user per new role
-- Password: wm123456 (BCrypt strength 12)
INSERT INTO auth_users (id, username, email, password_hash, role, is_active)
VALUES (
    'a0000000-0000-0000-0000-000000000005',
    'warehouse_manager',
    'warehouse_manager@walmal.local',
    '$2a$12$wHsnzES4zHZCbS5z24Gbk.KIYXRiSnUoeXo3xNDbcyK4uepM9iwRa',
    'WAREHOUSE_MANAGER',
    TRUE
)
ON CONFLICT DO NOTHING;

-- Password: ws123456 (BCrypt strength 12)
INSERT INTO auth_users (id, username, email, password_hash, role, is_active)
VALUES (
    'a0000000-0000-0000-0000-000000000006',
    'warehouse_staff',
    'warehouse_staff@walmal.local',
    '$2a$12$cz897iPN9FwH3RQeuxCU9.5J6dS7JVirIQ72sgTXa1JjL8sA3.XMG',
    'WAREHOUSE_STAFF',
    TRUE
)
ON CONFLICT DO NOTHING;

-- Password: pos123456 (BCrypt strength 12)
INSERT INTO auth_users (id, username, email, password_hash, role, is_active)
VALUES (
    'a0000000-0000-0000-0000-000000000007',
    'pos_operator',
    'pos_operator@walmal.local',
    '$2a$12$5pl986RRD66kB6NU62aVW.ESLy9Pnb5zqI/RIqRD2kAlfqTihqms.',
    'POS_OPERATOR',
    TRUE
)
ON CONFLICT DO NOTHING;
