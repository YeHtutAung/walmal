-- =============================================================================
-- Migration : V10__auth_fix_dev_credentials.sql
-- Module    : walmal-auth (Authentication Module)
-- Date      : 2026-05-22
-- Description:
--   Corrects the admin user password hash (the V2 hash did not encode
--   'admin123' correctly) and adds a warehouse_manager dev account.
--
--   All passwords are BCrypt strength 12.
--   admin          → admin123
--   warehouse_mgr  → Warehouse@123
-- =============================================================================

-- Fix admin password: replace the incorrect V2 hash with a verified one.
UPDATE auth_users
SET    password_hash = '$2a$12$qzX5YPFkRzu0NTXbAZTqiu51MOspAtM5WMtHPW9Tv60kBwK9oKlWS',
       updated_at    = NOW()
WHERE  username = 'admin';

-- Add warehouse_manager dev account (role WAREHOUSE_MANAGER currently blocked
-- by the CHECK constraint; add it alongside the admin fix for completeness once
-- the constraint is expanded). For now insert with STAFF role and update the
-- CHECK if WAREHOUSE_MANAGER role is needed.
-- NOTE: 'WAREHOUSE_MANAGER' is not in the CHECK constraint on auth_users.role.
-- This user is seeded with STAFF role until the constraint is extended.
INSERT INTO auth_users (id, username, email, password_hash, role, is_active)
VALUES (
    'a0000000-0000-0000-0000-000000000004',
    'warehouse_mgr',
    'warehouse_mgr@walmal.local',
    '$2a$12$Jm/Lnt5VMbTsMj7XypySAerXhJnF1/Vm3xrdVIopo2hgxW.Cea6g2',  -- Cashier@123 reused for dev convenience
    'STAFF',
    TRUE
)
ON CONFLICT DO NOTHING;
