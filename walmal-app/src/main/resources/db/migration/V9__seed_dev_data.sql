-- =============================================================================
-- Migration : V9__seed_dev_data.sql
-- Date      : 2026-05-22
-- Description:
--   Seeds realistic development data across all modules. Provides a complete
--   set of interconnected data for local development and manual testing.
--
-- WARNING: These rows are for local development only.
--   Do not rely on these fixed UUIDs in production deployments.
--
-- Dependencies on earlier seed data:
--   V2: admin user      (a0000000-0000-0000-0000-000000000001)
--   V3: categories      (c0000000-...-0001 Electronics, c0000000-...-0002 Apparel)
--   V4: locations       (a0000000-...-0001 Main Warehouse, a0000000-...-0002 Buffer)
--   V5: order           (c0000000-...-0001 PENDING order)
--   V6: POS terminal    (b0000000-...-0001 Main Store Terminal 1)
-- =============================================================================

-- ── Auth: customer user ─────────────────────────────────────────────────────
-- Password: Customer@123 (BCrypt strength 12)
INSERT INTO auth_users (id, username, email, password_hash, role, is_active)
VALUES (
    'a0000000-0000-0000-0000-000000000002',
    'customer1',
    'customer1@walmal.com',
    '$2a$12$Jm/Lnt5VMbTsMj7XypySAerXhJnF1/Vm3xrdVIopo2hgxW.Cea6g2',
    'CUSTOMER',
    TRUE
)
ON CONFLICT DO NOTHING;

-- Password: Cashier@123 (BCrypt strength 12, same hash for dev convenience)
INSERT INTO auth_users (id, username, email, password_hash, role, is_active)
VALUES (
    'a0000000-0000-0000-0000-000000000003',
    'cashier1',
    'cashier1@walmal.com',
    '$2a$12$Jm/Lnt5VMbTsMj7XypySAerXhJnF1/Vm3xrdVIopo2hgxW.Cea6g2',
    'CASHIER',
    TRUE
)
ON CONFLICT DO NOTHING;

-- ── Product: sub-categories ──────────────────────────────────────────────────
INSERT INTO product_categories (id, name, slug, parent_id, is_active) VALUES
    ('c0000000-0000-0000-0000-000000000011', 'Smartphones', 'smartphones', 'c0000000-0000-0000-0000-000000000001', TRUE),
    ('c0000000-0000-0000-0000-000000000012', 'Laptops',     'laptops',     'c0000000-0000-0000-0000-000000000001', TRUE),
    ('c0000000-0000-0000-0000-000000000021', 'T-Shirts',    't-shirts',    'c0000000-0000-0000-0000-000000000002', TRUE),
    ('c0000000-0000-0000-0000-000000000022', 'Jeans',       'jeans',       'c0000000-0000-0000-0000-000000000002', TRUE)
ON CONFLICT DO NOTHING;

-- ── Product: products ────────────────────────────────────────────────────────
INSERT INTO product_products (id, category_id, name, slug, description, brand, status) VALUES
    ('10000000-0000-0000-0000-000000000001',
     'c0000000-0000-0000-0000-000000000011',
     'Galaxy S24 Ultra', 'galaxy-s24-ultra',
     '6.8" Dynamic AMOLED, 200MP camera, S Pen included',
     'Samsung', 'ACTIVE'),

    ('10000000-0000-0000-0000-000000000002',
     'c0000000-0000-0000-0000-000000000011',
     'iPhone 16 Pro', 'iphone-16-pro',
     '6.3" Super Retina XDR, A18 Pro chip, 48MP camera system',
     'Apple', 'ACTIVE'),

    ('10000000-0000-0000-0000-000000000003',
     'c0000000-0000-0000-0000-000000000012',
     'MacBook Pro 14"', 'macbook-pro-14',
     'M4 Pro chip, 18GB RAM, 512GB SSD, Liquid Retina XDR',
     'Apple', 'ACTIVE'),

    ('10000000-0000-0000-0000-000000000004',
     'c0000000-0000-0000-0000-000000000021',
     'Classic Crew Tee', 'classic-crew-tee',
     '100% cotton, pre-shrunk, unisex fit',
     'Walmal Basics', 'ACTIVE'),

    ('10000000-0000-0000-0000-000000000005',
     'c0000000-0000-0000-0000-000000000022',
     'Slim Fit Jeans', 'slim-fit-jeans',
     'Stretch denim, 5-pocket design, mid-rise',
     'Walmal Basics', 'ACTIVE')
ON CONFLICT DO NOTHING;

-- ── Product: variants ────────────────────────────────────────────────────────
INSERT INTO product_variants (id, product_id, sku, name, barcode, attributes, status) VALUES
    -- Galaxy S24 Ultra variants
    ('20000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000001',
     'SAM-S24U-256-BLK', 'S24 Ultra 256GB Black', '8801643123451',
     '{"storage":"256GB","color":"Black"}', 'ACTIVE'),

    ('20000000-0000-0000-0000-000000000002',
     '10000000-0000-0000-0000-000000000001',
     'SAM-S24U-512-TIT', 'S24 Ultra 512GB Titanium', '8801643123452',
     '{"storage":"512GB","color":"Titanium"}', 'ACTIVE'),

    -- iPhone 16 Pro variants
    ('20000000-0000-0000-0000-000000000003',
     '10000000-0000-0000-0000-000000000002',
     'APL-IP16P-256-BLK', 'iPhone 16 Pro 256GB Black Titanium', '0194253456781',
     '{"storage":"256GB","color":"Black Titanium"}', 'ACTIVE'),

    ('20000000-0000-0000-0000-000000000004',
     '10000000-0000-0000-0000-000000000002',
     'APL-IP16P-512-NAT', 'iPhone 16 Pro 512GB Natural Titanium', '0194253456782',
     '{"storage":"512GB","color":"Natural Titanium"}', 'ACTIVE'),

    -- MacBook Pro variant
    ('20000000-0000-0000-0000-000000000005',
     '10000000-0000-0000-0000-000000000003',
     'APL-MBP14-M4P-SLV', 'MacBook Pro 14" M4 Pro Silver', '0194253456801',
     '{"chip":"M4 Pro","color":"Silver"}', 'ACTIVE'),

    -- T-Shirt variants
    ('20000000-0000-0000-0000-000000000006',
     '10000000-0000-0000-0000-000000000004',
     'WB-TEE-M-WHT', 'Classic Tee M White', NULL,
     '{"size":"M","color":"White"}', 'ACTIVE'),

    ('20000000-0000-0000-0000-000000000007',
     '10000000-0000-0000-0000-000000000004',
     'WB-TEE-L-BLK', 'Classic Tee L Black', NULL,
     '{"size":"L","color":"Black"}', 'ACTIVE'),

    -- Jeans variants
    ('20000000-0000-0000-0000-000000000008',
     '10000000-0000-0000-0000-000000000005',
     'WB-JEAN-32-IND', 'Slim Jeans 32 Indigo', NULL,
     '{"waist":"32","color":"Indigo"}', 'ACTIVE'),

    ('20000000-0000-0000-0000-000000000009',
     '10000000-0000-0000-0000-000000000005',
     'WB-JEAN-34-BLK', 'Slim Jeans 34 Black', NULL,
     '{"waist":"34","color":"Black"}', 'ACTIVE')
ON CONFLICT DO NOTHING;

-- ── Product: prices ──────────────────────────────────────────────────────────
INSERT INTO product_prices (id, variant_id, amount, currency) VALUES
    ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 1199.99, 'USD'),
    ('30000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002', 1419.99, 'USD'),
    ('30000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000003', 999.00,  'USD'),
    ('30000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000004', 1199.00, 'USD'),
    ('30000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000005', 1999.00, 'USD'),
    ('30000000-0000-0000-0000-000000000006', '20000000-0000-0000-0000-000000000006', 24.99,   'USD'),
    ('30000000-0000-0000-0000-000000000007', '20000000-0000-0000-0000-000000000007', 24.99,   'USD'),
    ('30000000-0000-0000-0000-000000000008', '20000000-0000-0000-0000-000000000008', 59.99,   'USD'),
    ('30000000-0000-0000-0000-000000000009', '20000000-0000-0000-0000-000000000009', 59.99,   'USD')
ON CONFLICT DO NOTHING;

-- ── Inventory: stock ─────────────────────────────────────────────────────────
-- Stock at Main Warehouse for all variants
INSERT INTO inventory_stock (id, variant_id, location_id, available_quantity, reserved_quantity, low_stock_threshold) VALUES
    ('40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 50, 0, 10),
    ('40000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 30, 0, 5),
    ('40000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 75, 0, 15),
    ('40000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001', 40, 0, 10),
    ('40000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001', 25, 0, 5),
    ('40000000-0000-0000-0000-000000000006', '20000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000001', 200, 0, 30),
    ('40000000-0000-0000-0000-000000000007', '20000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000001', 180, 0, 30),
    ('40000000-0000-0000-0000-000000000008', '20000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000001', 120, 0, 20),
    ('40000000-0000-0000-0000-000000000009', '20000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000001', 90, 0, 20)
ON CONFLICT DO NOTHING;

-- Buffer stock for high-demand items
INSERT INTO inventory_stock (id, variant_id, location_id, available_quantity, reserved_quantity, low_stock_threshold) VALUES
    ('40000000-0000-0000-0000-000000000011', '20000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000002', 10, 0, 3),
    ('40000000-0000-0000-0000-000000000012', '20000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000002', 15, 0, 3)
ON CONFLICT DO NOTHING;

-- ── Inventory: initial receipt movements ─────────────────────────────────────
INSERT INTO inventory_movements (id, variant_id, location_id, movement_type, quantity_delta, performed_by) VALUES
    ('50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'RECEIPT', 50, 'seed-migration'),
    ('50000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'RECEIPT', 30, 'seed-migration'),
    ('50000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'RECEIPT', 75, 'seed-migration'),
    ('50000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001', 'RECEIPT', 40, 'seed-migration'),
    ('50000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001', 'RECEIPT', 25, 'seed-migration'),
    ('50000000-0000-0000-0000-000000000006', '20000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000001', 'RECEIPT', 200, 'seed-migration'),
    ('50000000-0000-0000-0000-000000000007', '20000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000001', 'RECEIPT', 180, 'seed-migration'),
    ('50000000-0000-0000-0000-000000000008', '20000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000001', 'RECEIPT', 120, 'seed-migration'),
    ('50000000-0000-0000-0000-000000000009', '20000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000001', 'RECEIPT', 90, 'seed-migration')
ON CONFLICT DO NOTHING;

-- ── Order: line items for the existing PENDING order ─────────────────────────
-- Links to the order seeded in V5 (c0000000-...-0001)
INSERT INTO order_items (id, order_id, variant_id, product_name_snapshot, sku_snapshot, quantity, price_at_purchase, currency, subtotal, location_id)
VALUES (
    'e0000000-0000-0000-0000-000000000001',
    'c0000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001',
    'Galaxy S24 Ultra 256GB Black',
    'SAM-S24U-256-BLK',
    1,
    99.99,
    'USD',
    99.99,
    'a0000000-0000-0000-0000-000000000001'
)
ON CONFLICT DO NOTHING;
