-- Demo catalog. Applied only under the `demo` profile, which adds classpath:db/seed to the Flyway
-- locations — so these rows cannot reach an environment that did not ask for them by name.
--
-- Version 900 leaves the 1..899 range free for real schema migrations, so a future V2 never has to
-- sort after the seed. Every insert is ON CONFLICT DO NOTHING and every id is fixed, which makes
-- re-running this against a partially seeded database a no-op instead of a duplicate-key failure.

insert into categories (id, slug, name, description) values
    ('11111111-0000-4000-8000-000000000001', 'audio',      'Audio',      'Headphones, earbuds and speakers'),
    ('11111111-0000-4000-8000-000000000002', 'wearables',  'Wearables',  'Watches and fitness trackers'),
    ('11111111-0000-4000-8000-000000000003', 'computing',  'Computing',  'Laptops, tablets and accessories'),
    ('11111111-0000-4000-8000-000000000004', 'home',       'Smart Home', 'Everything that talks to your Wi-Fi')
on conflict (id) do nothing;

insert into products (id, sku, slug, name, description, category_id, base_price, currency, status, image_url) values
    ('22222222-0000-4000-8000-000000000001', 'AUD-HP-001', 'aurora-over-ear-headphones',
     'Aurora Over-Ear Headphones', 'Active noise cancelling, 40-hour battery.',
     '11111111-0000-4000-8000-000000000001', 299.00, 'USD', 'ACTIVE', null),
    ('22222222-0000-4000-8000-000000000002', 'AUD-EB-002', 'pulse-wireless-earbuds',
     'Pulse Wireless Earbuds', 'Six hours per charge, four more in the case.',
     '11111111-0000-4000-8000-000000000001', 129.00, 'USD', 'ACTIVE', null),
    ('22222222-0000-4000-8000-000000000003', 'AUD-SP-003', 'echo-field-speaker',
     'Echo Field Portable Speaker', 'IP67, floats, genuinely loud.',
     '11111111-0000-4000-8000-000000000001', 89.50, 'USD', 'ACTIVE', null),
    ('22222222-0000-4000-8000-000000000004', 'WEA-WT-001', 'meridian-smartwatch',
     'Meridian Smartwatch', 'Sapphire glass, seven-day battery, offline maps.',
     '11111111-0000-4000-8000-000000000002', 449.00, 'USD', 'ACTIVE', null),
    ('22222222-0000-4000-8000-000000000005', 'WEA-FT-002', 'stride-fitness-band',
     'Stride Fitness Band', 'Sleep, heart rate, and a screen you can read outdoors.',
     '11111111-0000-4000-8000-000000000002', 79.00, 'USD', 'ACTIVE', null),
    ('22222222-0000-4000-8000-000000000006', 'CMP-LT-001', 'vertex-14-laptop',
     'Vertex 14 Laptop', '14-inch, 16GB, all-day battery.',
     '11111111-0000-4000-8000-000000000003', 1299.00, 'USD', 'ACTIVE', null),
    ('22222222-0000-4000-8000-000000000007', 'CMP-TB-002', 'slate-11-tablet',
     'Slate 11 Tablet', 'Laminated display, pen included.',
     '11111111-0000-4000-8000-000000000003', 599.00, 'USD', 'ACTIVE', null),
    ('22222222-0000-4000-8000-000000000008', 'CMP-KB-003', 'tactile-75-keyboard',
     'Tactile 75 Mechanical Keyboard', 'Hot-swappable, gasket mount, very clacky.',
     '11111111-0000-4000-8000-000000000003', 159.00, 'USD', 'ACTIVE', null),
    ('22222222-0000-4000-8000-000000000009', 'HOM-BL-001', 'lumen-smart-bulb-4pack',
     'Lumen Smart Bulb (4-pack)', 'Sixteen million colours, one app.',
     '11111111-0000-4000-8000-000000000004', 59.00, 'USD', 'ACTIVE', null),
    ('22222222-0000-4000-8000-000000000010', 'HOM-CM-002', 'sentry-indoor-camera',
     'Sentry Indoor Camera', 'Local recording, no subscription.',
     '11111111-0000-4000-8000-000000000004', 119.00, 'USD', 'ACTIVE', null),
    ('22222222-0000-4000-8000-000000000011', 'HOM-PL-003', 'switchbox-smart-plug',
     'Switchbox Smart Plug', 'Energy monitoring, works offline.',
     '11111111-0000-4000-8000-000000000004', 24.99, 'USD', 'ACTIVE', null),
    -- One DRAFT row, so the ?status= filter has something to actually exclude.
    ('22222222-0000-4000-8000-000000000012', 'AUD-HP-999', 'aurora-studio-headphones',
     'Aurora Studio Headphones', 'Unannounced. Should not appear on the storefront.',
     '11111111-0000-4000-8000-000000000001', 499.00, 'USD', 'DRAFT', null)
on conflict (id) do nothing;

-- A sale that is already live, so `GET /api/v1/flash-sales/active` returns something on a cold
-- start. The window opens an hour in the past and runs for thirty days.
insert into flash_sales (id, slug, name, starts_at, ends_at, status) values
    ('33333333-0000-4000-8000-000000000001', 'launch-week',
     'Launch Week Doorbusters',
     now() - interval '1 hour', now() + interval '30 days', 'SCHEDULED'),
    -- ...and one that has not opened yet, so `/upcoming` is not empty either.
    ('33333333-0000-4000-8000-000000000002', 'midnight-drop',
     'Midnight Drop',
     now() + interval '7 days', now() + interval '7 days 2 hours', 'SCHEDULED')
on conflict (id) do nothing;

-- Every sale price is strictly below the product's base price; the service rejects anything else,
-- and seed data that could not have been created through the API would be a lie.
insert into flash_sale_items (id, flash_sale_id, product_id, sale_price, allocated_units, per_customer_limit) values
    ('44444444-0000-4000-8000-000000000001', '33333333-0000-4000-8000-000000000001',
     '22222222-0000-4000-8000-000000000001', 179.00, 500, 1),
    ('44444444-0000-4000-8000-000000000002', '33333333-0000-4000-8000-000000000001',
     '22222222-0000-4000-8000-000000000004', 299.00, 200, 1),
    ('44444444-0000-4000-8000-000000000003', '33333333-0000-4000-8000-000000000001',
     '22222222-0000-4000-8000-000000000009', 39.00, 2000, 4),
    ('44444444-0000-4000-8000-000000000004', '33333333-0000-4000-8000-000000000002',
     '22222222-0000-4000-8000-000000000006', 899.00, 50, 1)
on conflict (id) do nothing;
