-- =============================================================================
-- Migration : V17__reseed_sports_catalog.sql
-- Date      : 2026-07-17
-- Description:
--   Rebrands the seeded dev catalog as WALMAL SPORT (sports store). Existing
--   category/product/variant UUIDs and all prices are preserved (E2E + k6
--   depend on them); names, slugs, descriptions, brands, SKUs and attributes
--   are rewritten. Adds 10 new sports products with variants/prices/stock.
--   The order_items snapshot from V9 is intentionally untouched (historical).
-- =============================================================================

-- ── Categories: flatten to a 4-node sports taxonomy ──────────────────────────
UPDATE product_categories SET name='Boots',     slug='boots',     parent_id=NULL, updated_at=NOW() WHERE id='c0000000-0000-0000-0000-000000000011';
UPDATE product_categories SET name='Equipment', slug='equipment', parent_id=NULL, updated_at=NOW() WHERE id='c0000000-0000-0000-0000-000000000012';
UPDATE product_categories SET name='Jerseys',   slug='jerseys',   parent_id=NULL, updated_at=NOW() WHERE id='c0000000-0000-0000-0000-000000000021';
UPDATE product_categories SET name='Teamwear',  slug='teamwear',  parent_id=NULL, updated_at=NOW() WHERE id='c0000000-0000-0000-0000-000000000022';
UPDATE product_categories SET is_active=FALSE, updated_at=NOW() WHERE id IN
    ('c0000000-0000-0000-0000-000000000001','c0000000-0000-0000-0000-000000000002');

-- ── Existing products → sports products (same UUIDs) ─────────────────────────
UPDATE product_products SET
    category_id='c0000000-0000-0000-0000-000000000011',
    name='Velocity Elite FG Boot', slug='velocity-elite-fg-boot',
    description='Limited-edition featherweight speed boot. Carbon soleplate, hand-finished knit upper.',
    brand='Walmal Pro',
    updated_at=NOW()
WHERE id='10000000-0000-0000-0000-000000000001';

UPDATE product_products SET
    category_id='c0000000-0000-0000-0000-000000000011',
    name='Phantom Strike FG Boot', slug='phantom-strike-fg-boot',
    description='Elite firm-ground boot with asymmetric lacing and grippy strike zone.',
    brand='Walmal Pro',
    updated_at=NOW()
WHERE id='10000000-0000-0000-0000-000000000002';

UPDATE product_products SET
    category_id='c0000000-0000-0000-0000-000000000012',
    name='Pro Match Goal — Full Size', slug='pro-match-goal',
    description='Full-size aluminium match goal, weatherproof net included. FIFA-spec dimensions.',
    brand='Walmal Sport',
    updated_at=NOW()
WHERE id='10000000-0000-0000-0000-000000000003';

UPDATE product_products SET
    category_id='c0000000-0000-0000-0000-000000000021',
    name='Harbour City FC Fan Tee', slug='harbour-city-fan-tee',
    description='100% cotton supporter tee in club colours. Unisex fit.',
    brand='Harbour City FC',
    updated_at=NOW()
WHERE id='10000000-0000-0000-0000-000000000004';

UPDATE product_products SET
    category_id='c0000000-0000-0000-0000-000000000022',
    name='DNA Training Pants', slug='dna-training-pants',
    description='Slim training pants with stretch panels and zipped ankles.',
    brand='Walmal Pro',
    updated_at=NOW()
WHERE id='10000000-0000-0000-0000-000000000005';

-- ── Existing variants → sports variants (same UUIDs, prices untouched) ───────
UPDATE product_variants SET sku='WP-VELO-LE-UK9',  name='Velocity Elite LE UK 9 Chaos Red',    attributes='{"size":"UK 9","color":"Chaos Red"}'   , updated_at=NOW() WHERE id='20000000-0000-0000-0000-000000000001';
UPDATE product_variants SET sku='WP-VELO-LE-UK9G', name='Velocity Elite LE UK 9 Gold Limited', attributes='{"size":"UK 9","color":"Gold Limited"}', updated_at=NOW() WHERE id='20000000-0000-0000-0000-000000000002';
UPDATE product_variants SET sku='WP-PHTM-UK8-BLK', name='Phantom Strike UK 8 Black',           attributes='{"size":"UK 8","color":"Black"}'       , updated_at=NOW() WHERE id='20000000-0000-0000-0000-000000000003';
UPDATE product_variants SET sku='WP-PHTM-UK9-BLK', name='Phantom Strike UK 9 Black',           attributes='{"size":"UK 9","color":"Black"}'       , updated_at=NOW() WHERE id='20000000-0000-0000-0000-000000000004';
UPDATE product_variants SET sku='WS-GOAL-FS-ALU',  name='Pro Match Goal Full Size Aluminium',  attributes='{"size":"Full Size","color":"Aluminium"}', updated_at=NOW() WHERE id='20000000-0000-0000-0000-000000000005';
UPDATE product_variants SET sku='HC-FTEE-M-WHT',   name='Fan Tee M White',                     attributes='{"size":"M","color":"White"}'          , updated_at=NOW() WHERE id='20000000-0000-0000-0000-000000000006';
UPDATE product_variants SET sku='HC-FTEE-L-BLK',   name='Fan Tee L Black',                     attributes='{"size":"L","color":"Black"}'          , updated_at=NOW() WHERE id='20000000-0000-0000-0000-000000000007';
UPDATE product_variants SET sku='WP-DNAP-32-NVY',  name='DNA Training Pants 32 Navy',          attributes='{"size":"32","color":"Navy"}'          , updated_at=NOW() WHERE id='20000000-0000-0000-0000-000000000008';
UPDATE product_variants SET sku='WP-DNAP-34-BLK',  name='DNA Training Pants 34 Black',         attributes='{"size":"34","color":"Black"}'         , updated_at=NOW() WHERE id='20000000-0000-0000-0000-000000000009';

-- ── Drop seed-era product images (script re-seeds sports art afterwards) ─────
-- MinIO objects become orphans; acceptable for dev data.
DELETE FROM product_images WHERE product_id IN (
    '10000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000004',
    '10000000-0000-0000-0000-000000000005');

-- ── New products ─────────────────────────────────────────────────────────────
INSERT INTO product_products (id, category_id, name, slug, description, brand, status) VALUES
    ('10000000-0000-0000-0000-000000000006','c0000000-0000-0000-0000-000000000021',
     'Harbour City FC 26/27 Home Jersey','hc-home-jersey-26-27',
     'Official 26/27 home jersey. Sweat-wicking match fabric, embroidered crest.','Harbour City FC','ACTIVE'),
    ('10000000-0000-0000-0000-000000000007','c0000000-0000-0000-0000-000000000021',
     'Harbour City FC 26/27 Away Jersey','hc-away-jersey-26-27',
     'Official 26/27 away jersey in storm white with harbour-teal trim.','Harbour City FC','ACTIVE'),
    ('10000000-0000-0000-0000-000000000008','c0000000-0000-0000-0000-000000000021',
     'Riverside United 26/27 Home Jersey','ru-home-jersey-26-27',
     'Official 26/27 home jersey. Classic riverside green with gold piping.','Riverside United','ACTIVE'),
    ('10000000-0000-0000-0000-000000000009','c0000000-0000-0000-0000-000000000021',
     'National Team Authentic Home Jersey','national-authentic-home-jersey',
     'Player-issue authentic jersey. Laser-cut ventilation, athletic cut.','National Team','ACTIVE'),
    ('10000000-0000-0000-0000-000000000010','c0000000-0000-0000-0000-000000000011',
     'Aero Knit Speed Boot','aero-knit-speed-boot',
     'Ultra-light knit speed boot for explosive acceleration.','Walmal Pro','ACTIVE'),
    ('10000000-0000-0000-0000-000000000011','c0000000-0000-0000-0000-000000000011',
     'Velocity Pro AG Boot','velocity-pro-ag-boot',
     'Artificial-grass version of the Velocity line. Hollow studs, stable chassis.','Walmal Pro','ACTIVE'),
    ('10000000-0000-0000-0000-000000000012','c0000000-0000-0000-0000-000000000012',
     '26/27 Official Match Ball','official-match-ball-26-27',
     'FIFA Quality Pro certified thermally-bonded match ball.','Walmal Sport','ACTIVE'),
    ('10000000-0000-0000-0000-000000000013','c0000000-0000-0000-0000-000000000012',
     'Grip Training Socks','grip-training-socks',
     'Anti-slip grip socks with cushioned sole and arch support.','Walmal Sport','ACTIVE'),
    ('10000000-0000-0000-0000-000000000014','c0000000-0000-0000-0000-000000000012',
     'Lite Carbon Shinguards','lite-carbon-shinguards',
     'Featherweight carbon-shell shinguards with EVA backing.','Walmal Pro','ACTIVE'),
    ('10000000-0000-0000-0000-000000000015','c0000000-0000-0000-0000-000000000022',
     'DNA Training Polo','dna-training-polo',
     'Club training polo in breathable pique with contrast collar.','Harbour City FC','ACTIVE')
ON CONFLICT DO NOTHING;

-- ── New variants ─────────────────────────────────────────────────────────────
INSERT INTO product_variants (id, product_id, sku, name, barcode, attributes, status) VALUES
    ('20000000-0000-0000-0000-000000000010','10000000-0000-0000-0000-000000000006','HC-HOME-S', 'HC Home Jersey S', NULL,'{"size":"S"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000011','10000000-0000-0000-0000-000000000006','HC-HOME-M', 'HC Home Jersey M', NULL,'{"size":"M"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000012','10000000-0000-0000-0000-000000000006','HC-HOME-L', 'HC Home Jersey L', NULL,'{"size":"L"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000013','10000000-0000-0000-0000-000000000007','HC-AWAY-M', 'HC Away Jersey M', NULL,'{"size":"M"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000014','10000000-0000-0000-0000-000000000007','HC-AWAY-L', 'HC Away Jersey L', NULL,'{"size":"L"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000015','10000000-0000-0000-0000-000000000008','RU-HOME-M', 'RU Home Jersey M', NULL,'{"size":"M"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000016','10000000-0000-0000-0000-000000000008','RU-HOME-L', 'RU Home Jersey L', NULL,'{"size":"L"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000017','10000000-0000-0000-0000-000000000009','NT-AUTH-M', 'National Authentic M', NULL,'{"size":"M"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000018','10000000-0000-0000-0000-000000000009','NT-AUTH-L', 'National Authentic L', NULL,'{"size":"L"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000019','10000000-0000-0000-0000-000000000010','WP-AERO-UK8', 'Aero Knit UK 8', NULL,'{"size":"UK 8","color":"White"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000020','10000000-0000-0000-0000-000000000010','WP-AERO-UK9', 'Aero Knit UK 9', NULL,'{"size":"UK 9","color":"White"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000021','10000000-0000-0000-0000-000000000010','WP-AERO-UK10','Aero Knit UK 10', NULL,'{"size":"UK 10","color":"White"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000022','10000000-0000-0000-0000-000000000011','WP-VPAG-UK9', 'Velocity Pro AG UK 9', NULL,'{"size":"UK 9","color":"Chaos Red"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000023','10000000-0000-0000-0000-000000000011','WP-VPAG-UK10','Velocity Pro AG UK 10', NULL,'{"size":"UK 10","color":"Chaos Red"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000024','10000000-0000-0000-0000-000000000012','WS-BALL-S5', 'Match Ball Size 5', NULL,'{"size":"Size 5"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000025','10000000-0000-0000-0000-000000000013','WS-SOCK-M', 'Grip Socks M', NULL,'{"size":"M","color":"Black"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000026','10000000-0000-0000-0000-000000000013','WS-SOCK-L', 'Grip Socks L', NULL,'{"size":"L","color":"Black"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000027','10000000-0000-0000-0000-000000000014','WP-SHIN-S', 'Carbon Shinguards S', NULL,'{"size":"S"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000028','10000000-0000-0000-0000-000000000014','WP-SHIN-M', 'Carbon Shinguards M', NULL,'{"size":"M"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000029','10000000-0000-0000-0000-000000000015','HC-POLO-M', 'DNA Polo M', NULL,'{"size":"M","color":"Navy"}','ACTIVE'),
    ('20000000-0000-0000-0000-000000000030','10000000-0000-0000-0000-000000000015','HC-POLO-L', 'DNA Polo L', NULL,'{"size":"L","color":"Navy"}','ACTIVE')
ON CONFLICT DO NOTHING;

-- ── New prices (price id suffix aligned to variant id suffix) ────────────────
INSERT INTO product_prices (id, variant_id, amount, currency) VALUES
    ('30000000-0000-0000-0000-000000000010','20000000-0000-0000-0000-000000000010',115.00,'USD'),
    ('30000000-0000-0000-0000-000000000011','20000000-0000-0000-0000-000000000011',115.00,'USD'),
    ('30000000-0000-0000-0000-000000000012','20000000-0000-0000-0000-000000000012',115.00,'USD'),
    ('30000000-0000-0000-0000-000000000013','20000000-0000-0000-0000-000000000013',115.00,'USD'),
    ('30000000-0000-0000-0000-000000000014','20000000-0000-0000-0000-000000000014',115.00,'USD'),
    ('30000000-0000-0000-0000-000000000015','20000000-0000-0000-0000-000000000015',115.00,'USD'),
    ('30000000-0000-0000-0000-000000000016','20000000-0000-0000-0000-000000000016',115.00,'USD'),
    ('30000000-0000-0000-0000-000000000017','20000000-0000-0000-0000-000000000017',189.00,'USD'),
    ('30000000-0000-0000-0000-000000000018','20000000-0000-0000-0000-000000000018',189.00,'USD'),
    ('30000000-0000-0000-0000-000000000019','20000000-0000-0000-0000-000000000019',349.00,'USD'),
    ('30000000-0000-0000-0000-000000000020','20000000-0000-0000-0000-000000000020',349.00,'USD'),
    ('30000000-0000-0000-0000-000000000021','20000000-0000-0000-0000-000000000021',349.00,'USD'),
    ('30000000-0000-0000-0000-000000000022','20000000-0000-0000-0000-000000000022',319.00,'USD'),
    ('30000000-0000-0000-0000-000000000023','20000000-0000-0000-0000-000000000023',319.00,'USD'),
    ('30000000-0000-0000-0000-000000000024','20000000-0000-0000-0000-000000000024', 79.00,'USD'),
    ('30000000-0000-0000-0000-000000000025','20000000-0000-0000-0000-000000000025', 22.00,'USD'),
    ('30000000-0000-0000-0000-000000000026','20000000-0000-0000-0000-000000000026', 22.00,'USD'),
    ('30000000-0000-0000-0000-000000000027','20000000-0000-0000-0000-000000000027', 45.00,'USD'),
    ('30000000-0000-0000-0000-000000000028','20000000-0000-0000-0000-000000000028', 45.00,'USD'),
    ('30000000-0000-0000-0000-000000000029','20000000-0000-0000-0000-000000000029', 50.00,'USD'),
    ('30000000-0000-0000-0000-000000000030','20000000-0000-0000-0000-000000000030', 50.00,'USD')
ON CONFLICT DO NOTHING;

-- ── New stock at Main Warehouse (a0…-0001) ───────────────────────────────────
INSERT INTO inventory_stock (id, variant_id, location_id, available_quantity, reserved_quantity, low_stock_threshold) VALUES
    ('40000000-0000-0000-0000-000000000021','20000000-0000-0000-0000-000000000010','a0000000-0000-0000-0000-000000000001',120,0,20),
    ('40000000-0000-0000-0000-000000000022','20000000-0000-0000-0000-000000000011','a0000000-0000-0000-0000-000000000001',150,0,20),
    ('40000000-0000-0000-0000-000000000023','20000000-0000-0000-0000-000000000012','a0000000-0000-0000-0000-000000000001',150,0,20),
    ('40000000-0000-0000-0000-000000000024','20000000-0000-0000-0000-000000000013','a0000000-0000-0000-0000-000000000001',100,0,15),
    ('40000000-0000-0000-0000-000000000025','20000000-0000-0000-0000-000000000014','a0000000-0000-0000-0000-000000000001',100,0,15),
    ('40000000-0000-0000-0000-000000000026','20000000-0000-0000-0000-000000000015','a0000000-0000-0000-0000-000000000001', 90,0,15),
    ('40000000-0000-0000-0000-000000000027','20000000-0000-0000-0000-000000000016','a0000000-0000-0000-0000-000000000001', 90,0,15),
    ('40000000-0000-0000-0000-000000000028','20000000-0000-0000-0000-000000000017','a0000000-0000-0000-0000-000000000001', 60,0,10),
    ('40000000-0000-0000-0000-000000000029','20000000-0000-0000-0000-000000000018','a0000000-0000-0000-0000-000000000001', 60,0,10),
    ('40000000-0000-0000-0000-000000000030','20000000-0000-0000-0000-000000000019','a0000000-0000-0000-0000-000000000001', 40,0,8),
    ('40000000-0000-0000-0000-000000000031','20000000-0000-0000-0000-000000000020','a0000000-0000-0000-0000-000000000001', 40,0,8),
    ('40000000-0000-0000-0000-000000000032','20000000-0000-0000-0000-000000000021','a0000000-0000-0000-0000-000000000001', 40,0,8),
    ('40000000-0000-0000-0000-000000000033','20000000-0000-0000-0000-000000000022','a0000000-0000-0000-0000-000000000001', 35,0,8),
    ('40000000-0000-0000-0000-000000000034','20000000-0000-0000-0000-000000000023','a0000000-0000-0000-0000-000000000001', 35,0,8),
    ('40000000-0000-0000-0000-000000000035','20000000-0000-0000-0000-000000000024','a0000000-0000-0000-0000-000000000001',200,0,30),
    ('40000000-0000-0000-0000-000000000036','20000000-0000-0000-0000-000000000025','a0000000-0000-0000-0000-000000000001',300,0,50),
    ('40000000-0000-0000-0000-000000000037','20000000-0000-0000-0000-000000000026','a0000000-0000-0000-0000-000000000001',300,0,50),
    ('40000000-0000-0000-0000-000000000038','20000000-0000-0000-0000-000000000027','a0000000-0000-0000-0000-000000000001', 80,0,15),
    ('40000000-0000-0000-0000-000000000039','20000000-0000-0000-0000-000000000028','a0000000-0000-0000-0000-000000000001', 80,0,15),
    ('40000000-0000-0000-0000-000000000040','20000000-0000-0000-0000-000000000029','a0000000-0000-0000-0000-000000000001',110,0,20),
    ('40000000-0000-0000-0000-000000000041','20000000-0000-0000-0000-000000000030','a0000000-0000-0000-0000-000000000001',110,0,20)
ON CONFLICT DO NOTHING;

-- ── Receipt movements for the new stock ──────────────────────────────────────
INSERT INTO inventory_movements (id, variant_id, location_id, movement_type, quantity_delta, performed_by)
SELECT ('50000000-0000-0000-0000-0000000000' || LPAD((9 + row_number() OVER (ORDER BY s.id))::text, 2, '0'))::uuid,
       s.variant_id, s.location_id, 'RECEIPT', s.available_quantity, 'seed-migration-v17'
FROM inventory_stock s
WHERE s.id::text >= '40000000-0000-0000-0000-000000000021'
  AND s.id::text <= '40000000-0000-0000-0000-000000000041'
ON CONFLICT DO NOTHING;
