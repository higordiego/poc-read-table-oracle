-- Catalog (reference data: segments, price tables, items, tariffs, rules)
-- plus two demonstration customers and calculations, mirroring what the
-- README's curl examples expect.

INSERT INTO customer_segments (id, code, name) VALUES (1, 'RETAIL', 'Retail');
INSERT INTO customer_segments (id, code, name) VALUES (2, 'ENTERPRISE', 'Enterprise');

INSERT INTO customers (id, segment_id, name, email, status)
VALUES (1, 1, 'Alan Turing', 'alan@example.com', 'ACTIVE');
INSERT INTO customers (id, segment_id, name, email, status)
VALUES (2, 2, 'Ada Lovelace', 'ada@example.com', 'ACTIVE');

INSERT INTO price_tables (id, segment_id, name, currency, valid_from, status)
VALUES (1, 1, 'Retail Standard 2026', 'BRL', DATE '2026-01-01', 'ACTIVE');
INSERT INTO price_tables (id, segment_id, name, currency, valid_from, status)
VALUES (2, 2, 'Enterprise Standard 2026', 'BRL', DATE '2026-01-01', 'ACTIVE');

INSERT INTO price_table_items (id, price_table_id, product_code, description, base_price)
VALUES (1, 1, 'COMPUTE-HR', 'Compute Hour', 0.50);
INSERT INTO price_table_items (id, price_table_id, product_code, description, base_price)
VALUES (2, 1, 'STORAGE-GB', 'Storage GB-month', 0.10);
INSERT INTO price_table_items (id, price_table_id, product_code, description, base_price)
VALUES (3, 1, 'API-1K', 'API Calls (1k)', 1.00);
INSERT INTO price_table_items (id, price_table_id, product_code, description, base_price)
VALUES (4, 2, 'COMPUTE-HR', 'Compute Hour', 0.40);
INSERT INTO price_table_items (id, price_table_id, product_code, description, base_price)
VALUES (5, 2, 'STORAGE-GB', 'Storage GB-month', 0.08);
INSERT INTO price_table_items (id, price_table_id, product_code, description, base_price)
VALUES (6, 2, 'API-1K', 'API Calls (1k)', 0.80);

-- rate_type PERCENT: unit_price = base_price * (1 + rate_value / 100).
-- rate_type FIXED: unit_price = base_price + rate_value.
INSERT INTO tariffs (id, price_table_item_id, min_quantity, max_quantity, rate_type, rate_value, valid_from)
VALUES (1, 1, 1, 99, 'PERCENT', 0, DATE '2026-01-01');
INSERT INTO tariffs (id, price_table_item_id, min_quantity, max_quantity, rate_type, rate_value, valid_from)
VALUES (2, 1, 100, NULL, 'PERCENT', -10, DATE '2026-01-01');
INSERT INTO tariffs (id, price_table_item_id, min_quantity, max_quantity, rate_type, rate_value, valid_from)
VALUES (3, 2, 1, 999, 'PERCENT', 0, DATE '2026-01-01');
INSERT INTO tariffs (id, price_table_item_id, min_quantity, max_quantity, rate_type, rate_value, valid_from)
VALUES (4, 2, 1000, NULL, 'PERCENT', -15, DATE '2026-01-01');
INSERT INTO tariffs (id, price_table_item_id, min_quantity, max_quantity, rate_type, rate_value, valid_from)
VALUES (5, 3, 1, NULL, 'FIXED', 0, DATE '2026-01-01');
INSERT INTO tariffs (id, price_table_item_id, min_quantity, max_quantity, rate_type, rate_value, valid_from)
VALUES (6, 4, 1, 499, 'PERCENT', -5, DATE '2026-01-01');
INSERT INTO tariffs (id, price_table_item_id, min_quantity, max_quantity, rate_type, rate_value, valid_from)
VALUES (7, 4, 500, NULL, 'PERCENT', -20, DATE '2026-01-01');
INSERT INTO tariffs (id, price_table_item_id, min_quantity, max_quantity, rate_type, rate_value, valid_from)
VALUES (8, 5, 1, NULL, 'PERCENT', -10, DATE '2026-01-01');
INSERT INTO tariffs (id, price_table_item_id, min_quantity, max_quantity, rate_type, rate_value, valid_from)
VALUES (9, 6, 1, NULL, 'PERCENT', -5, DATE '2026-01-01');

-- adjustment_type PERCENT stacks multiplicatively on the tariff-adjusted
-- unit price (matches PriceResolutionService); FIXED adds a flat amount.
INSERT INTO tariff_rules (id, tariff_id, rule_type, condition_value, adjustment_type, adjustment_value, priority)
VALUES (1, 2, 'REGION', 'BR-SP', 'PERCENT', -2, 1);
INSERT INTO tariff_rules (id, tariff_id, rule_type, condition_value, adjustment_type, adjustment_value, priority)
VALUES (2, 7, 'CHANNEL', 'PARTNER', 'PERCENT', -3, 1);

-- Demonstration calculations (line items pre-resolved by hand here, the
-- same math PriceResolutionService applies at write time).
INSERT INTO calculations (id, customer_id, price_table_id, status, calculated_at, total_amount)
VALUES (1, 1, 1, 'CALCULATED', SYSTIMESTAMP, 10.00);
INSERT INTO calculation_line_items (
    id, calculation_id, price_table_item_id, tariff_id, quantity, unit_price,
    rules_applied_count, adjustment_amount, line_total
) VALUES (1, 1, 1, 1, 10, 0.50, 0, 0, 5.00);
INSERT INTO calculation_line_items (
    id, calculation_id, price_table_item_id, tariff_id, quantity, unit_price,
    rules_applied_count, adjustment_amount, line_total
) VALUES (2, 1, 3, 5, 5, 1.00, 0, 0, 5.00);

INSERT INTO calculations (id, customer_id, price_table_id, status, calculated_at, total_amount)
VALUES (2, 2, 2, 'CALCULATED', SYSTIMESTAMP, 330.24);
INSERT INTO calculation_line_items (
    id, calculation_id, price_table_item_id, tariff_id, quantity, unit_price,
    rules_applied_count, adjustment_amount, line_total
) VALUES (3, 2, 4, 7, 600, 0.3104, 1, 5.76, 186.24);
INSERT INTO calculation_line_items (
    id, calculation_id, price_table_item_id, tariff_id, quantity, unit_price,
    rules_applied_count, adjustment_amount, line_total
) VALUES (4, 2, 5, 8, 2000, 0.072, 0, 0, 144.00);
