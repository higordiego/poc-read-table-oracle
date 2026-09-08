WHENEVER SQLERROR EXIT SQL.SQLCODE
SET SERVEROUTPUT ON
SET TIMING ON

DEFINE ROW_COUNT = &1

MERGE INTO customers target
USING (
    SELECT 999999 AS id, 2 AS segment_id, 'Benchmark Customer' AS name,
           'benchmark@example.com' AS email, 'ACTIVE' AS status
      FROM dual
) source
ON (target.id = source.id)
WHEN MATCHED THEN UPDATE SET
    target.segment_id = source.segment_id,
    target.name = source.name,
    target.email = source.email,
    target.status = source.status,
    target.updated_at = SYSTIMESTAMP
WHEN NOT MATCHED THEN INSERT (id, segment_id, name, email, status)
VALUES (source.id, source.segment_id, source.name, source.email, source.status);
COMMIT;

DELETE FROM calculations WHERE id BETWEEN 1000001 AND 1000000 + &ROW_COUNT;
COMMIT;

-- At benchmark volume, letting the incremental triggers fire per row would
-- mean &ROW_COUNT synchronous single-row MERGEs dominating the load time --
-- exactly the cost this PoC's design exists to avoid on the read path, not
-- something to pay for a bulk import either. Disable them, bulk-load with
-- set-based INSERT ... SELECT, then catch the projection up with the same
-- Idempotent Bootstrap machinery (projection_admin_pkg) the app already
-- uses, in one set-based pass instead of &ROW_COUNT incremental ones.
ALTER TRIGGER trg_calculations_projection DISABLE;
ALTER TRIGGER trg_calc_line_items_projection DISABLE;

-- A single CONNECT BY LEVEL <= N generator runs out of PGA well before N
-- reaches a few million (ORA-30009). Two small (<= 2000 levels each)
-- generators combined with a plain CROSS JOIN cover N = a * b rows without
-- either one building a deep hierarchy; CEIL(&ROW_COUNT / 2000) keeps the
-- outer one small regardless of how large &ROW_COUNT is.
INSERT INTO calculations (id, customer_id, price_table_id, status, calculated_at, total_amount)
SELECT 1000000 + rn,
       999999,
       2,
       CASE MOD(rn, 4)
           WHEN 0 THEN 'DRAFT'
           WHEN 1 THEN 'CALCULATED'
           WHEN 2 THEN 'CONFIRMED'
           ELSE 'CANCELLED'
       END,
       SYSTIMESTAMP,
       0
  FROM (
      SELECT (a.lvl - 1) * 2000 + b.lvl AS rn
        FROM (SELECT LEVEL AS lvl FROM dual CONNECT BY LEVEL <= CEIL(&ROW_COUNT / 2000)) a
       CROSS JOIN (SELECT LEVEL AS lvl FROM dual CONNECT BY LEVEL <= 2000) b
  )
 WHERE rn <= &ROW_COUNT;
COMMIT;

-- 3 line items per calculation, cycling through the enterprise catalog
-- (price_table_items 4/5/6, seeded in V3) and their applicable tariffs.
INSERT INTO calculation_line_items (
    id, calculation_id, price_table_item_id, tariff_id, quantity,
    unit_price, rules_applied_count, adjustment_amount, line_total)
SELECT 2000000 + (g.rn - 1) * 3 + it.item_no,
       1000000 + g.rn,
       it.item_id,
       it.tariff_id,
       it.qty,
       it.unit_price,
       0,
       0,
       it.unit_price * it.qty
  FROM (
      SELECT (a.lvl - 1) * 2000 + b.lvl AS rn
        FROM (SELECT LEVEL AS lvl FROM dual CONNECT BY LEVEL <= CEIL(&ROW_COUNT / 2000)) a
       CROSS JOIN (SELECT LEVEL AS lvl FROM dual CONNECT BY LEVEL <= 2000) b
       WHERE (a.lvl - 1) * 2000 + b.lvl <= &ROW_COUNT
  ) g
 CROSS JOIN (
      SELECT 1 AS item_no, 4 AS item_id, 6 AS tariff_id, 0.38 AS unit_price, 10 AS qty FROM dual
      UNION ALL
      SELECT 2, 5, 8, 0.072, 20 FROM dual
      UNION ALL
      SELECT 3, 6, 9, 0.76, 5 FROM dual
  ) it;
COMMIT;

UPDATE calculations c
   SET total_amount = (
       SELECT SUM(line_total) FROM calculation_line_items WHERE calculation_id = c.id
   )
 WHERE id BETWEEN 1000001 AND 1000000 + &ROW_COUNT;
COMMIT;

ALTER TRIGGER trg_calculations_projection ENABLE;
ALTER TRIGGER trg_calc_line_items_projection ENABLE;

-- Not projection_admin_pkg.cutover(): its converge_inactive step trusts the
-- ACTIVE slot as the source of truth for anything the inactive slot
-- shouldn't have -- correct for a normal incremental rebuild, where the
-- active slot's triggers keep tracking reality while build_inactive runs.
-- Here the triggers were disabled for the whole bulk load, so the active
-- slot never saw these rows; converge_inactive would read that as "20000+
-- orphans" and delete every one of them from the freshly built inactive
-- slot right before the swap. build_inactive's view-based load is already
-- complete and correct on its own, so this validates the inactive slot
-- directly against calculation_transactional_view (the real source) and
-- swaps without ever calling converge_inactive.
DECLARE
    v_active     VARCHAR2(40);
    v_inactive   VARCHAR2(40);
    v_new_slot   CHAR(1);
    v_mismatch   NUMBER;
    v_started_at TIMESTAMP := SYSTIMESTAMP;
BEGIN
    projection_admin_pkg.reconcile_active_slot;
    projection_admin_pkg.build_inactive;

    v_active   := projection_admin_pkg.active_table;
    v_inactive := projection_admin_pkg.inactive_table;
    v_new_slot := SUBSTR(v_inactive, -1);

    EXECUTE IMMEDIATE 'LOCK TABLE ' || v_active || ' IN SHARE MODE';

    EXECUTE IMMEDIATE '
        SELECT COUNT(*) FROM (
            SELECT calculation_id, customer_id, customer_name, customer_email,
                   segment_code, segment_name, price_table_id, price_table_name,
                   status, line_item_count, total_amount, rules_applied_count,
                   total_adjustment, requested_at, calculated_at
              FROM ' || v_inactive || '
            MINUS
            SELECT calculation_id, customer_id, customer_name, customer_email,
                   segment_code, segment_name, price_table_id, price_table_name,
                   status, line_item_count, total_amount, rules_applied_count,
                   total_adjustment, requested_at, calculated_at
              FROM calculation_transactional_view
        )' INTO v_mismatch;

    IF v_mismatch = 0 THEN
        EXECUTE IMMEDIATE '
            SELECT COUNT(*) FROM (
                SELECT calculation_id, customer_id, customer_name, customer_email,
                       segment_code, segment_name, price_table_id, price_table_name,
                       status, line_item_count, total_amount, rules_applied_count,
                       total_adjustment, requested_at, calculated_at
                  FROM calculation_transactional_view
                MINUS
                SELECT calculation_id, customer_id, customer_name, customer_email,
                       segment_code, segment_name, price_table_id, price_table_name,
                       status, line_item_count, total_amount, rules_applied_count,
                       total_adjustment, requested_at, calculated_at
                  FROM ' || v_inactive || '
            )' INTO v_mismatch;
    END IF;

    IF v_mismatch > 0 THEN
        RAISE_APPLICATION_ERROR(-20002,
            'Bulk-load rebuild aborted: ' || v_mismatch || ' row(s) differ between ' ||
            v_inactive || ' and calculation_transactional_view');
    END IF;

    EXECUTE IMMEDIATE 'CREATE OR REPLACE SYNONYM calculation_read_projection FOR ' || v_inactive;

    UPDATE projection_control
       SET active_slot = v_new_slot,
           status = 'READY',
           last_built_at = SYSTIMESTAMP,
           last_validated_at = SYSTIMESTAMP,
           source_row_count = (SELECT COUNT(*) FROM calculation_transactional_view),
           projection_row_count = (SELECT COUNT(*) FROM calculation_read_projection),
           mismatch_count = 0,
           updated_at = SYSTIMESTAMP
     WHERE projection_name = 'CALCULATION_READ_PROJECTION';
    COMMIT;

    DBMS_OUTPUT.PUT_LINE(
        'Loaded ' || &ROW_COUNT || ' calculations and rebuilt the projection in ' ||
        EXTRACT(SECOND FROM (SYSTIMESTAMP - v_started_at)) || ' seconds (seconds component).'
    );
END;
/

-- calculation_read_projection is a synonym over whichever physical slot
-- (_a/_b) is active; DBMS_STATS/DBMS_MEMOPTIMIZE need the real object name.
COLUMN active_table_col NEW_VALUE ACTIVE_TABLE NOPRINT
SELECT 'CALCULATION_READ_PROJECTION_' || active_slot AS active_table_col
  FROM projection_control
 WHERE projection_name = 'CALCULATION_READ_PROJECTION';

-- method_opt => 'FOR ALL COLUMNS SIZE SKEWONLY': the bulk load puts every
-- row under one benchmark customer_id, so that column goes from "a handful
-- of distinct values, roughly evenly spread" to "one value owns ~100% of
-- rows, everything else owns a few rows each" -- exactly the shape a plain
-- gather (no histogram) estimates worst. Without SKEWONLY here, the
-- optimizer picked TABLE ACCESS FULL for a 3-row customer_id filter after
-- this load (same plan as for the 2.7M-row one), because it assumed a
-- roughly uniform distribution across customer_id and had no data to know
-- otherwise. SKEWONLY builds a histogram wherever it detects skew, which
-- lets the optimizer tell "this customer_id matches 3 rows, use the index"
-- from "this one matches 2.7M, a full scan is actually cheaper" -- the same
-- column, two very different right answers.
BEGIN
    DBMS_STATS.GATHER_TABLE_STATS(USER, 'CALCULATIONS',
        method_opt => 'FOR ALL COLUMNS SIZE SKEWONLY');
    DBMS_STATS.GATHER_TABLE_STATS(USER, 'CALCULATION_LINE_ITEMS');
    DBMS_STATS.GATHER_TABLE_STATS(USER, '&ACTIVE_TABLE',
        method_opt => 'FOR ALL COLUMNS SIZE SKEWONLY');
    DBMS_MEMOPTIMIZE.POPULATE(USER, '&ACTIVE_TABLE');
END;
/

SELECT COUNT(*) AS projection_rows FROM calculation_read_projection;
EXIT;
