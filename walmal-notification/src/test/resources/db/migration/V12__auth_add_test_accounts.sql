-- =============================================================================
-- Migration : V12__auth_add_test_accounts.sql
-- Module    : walmal-auth (Authentication Module)
-- Date      : 2026-05-29
-- Description:
--   Adds two fixed test accounts for E2E and integration testing.
--   UUIDs and password hashes are stable so test fixtures can reference
--   them by ID without dynamic lookups.
--
--   customer_test  customer@test.com  TestPass123!  CUSTOMER
--   admin_test     admin@test.com     AdminPass123! ADMIN
--
--   Passwords are BCrypt strength 12.
-- =============================================================================

-- Password: TestPass123! (BCrypt strength 12)
INSERT INTO auth_users (id, username, email, password_hash, role, is_active)
VALUES (
    '07d56e02-8642-4739-8fb0-13bd5548635d',
    'customer_test',
    'customer@test.com',
    '$2a$12$5fIVXJ27gQAJ9RjiAUAarusy4.D/uX4cy4w/7fPs1XjQNbMFvmZ.m',
    'CUSTOMER',
    TRUE
)
ON CONFLICT DO NOTHING;

-- Password: AdminPass123! (BCrypt strength 12)
INSERT INTO auth_users (id, username, email, password_hash, role, is_active)
VALUES (
    'be47033b-7e58-40a6-9a59-534bb0a4ed5a',
    'admin_test',
    'admin@test.com',
    '$2a$12$81gC7ODGJ0oMt2a5tWJkT.A1w1f3cY3Dn46.47vEEvjEOQSt4AK36',
    'ADMIN',
    TRUE
)
ON CONFLICT DO NOTHING;
