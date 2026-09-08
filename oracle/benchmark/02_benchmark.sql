WHENEVER SQLERROR EXIT SQL.SQLCODE
SET SERVEROUTPUT ON
SET LINESIZE 220
SET PAGESIZE 100

DEFINE ITERATIONS = &1
DEFINE ROW_COUNT = &2

PROMPT === Configuration ===
SELECT name, display_value
  FROM v$parameter
 WHERE name = 'memoptimize_pool_size';

-- calculation_read_projection is a synonym over whichever physical slot
-- (_a/_b) is active; DBMS_MEMOPTIMIZE and ALTER TABLE need the real object
-- name, so resolve it once here and reuse it as &ACTIVE_TABLE below.
COLUMN active_table_col NEW_VALUE ACTIVE_TABLE NOPRINT
SELECT 'CALCULATION_READ_PROJECTION_' || active_slot AS active_table_col
  FROM projection_control
 WHERE projection_name = 'CALCULATION_READ_PROJECTION';

SELECT table_name, memoptimize_read
  FROM user_tables
 WHERE table_name = '&ACTIVE_TABLE';

PROMPT === Eligible lookup and runtime plan ===
SELECT /* fast_lookup_benchmark_probe */ *
  FROM calculation_read_projection
 WHERE calculation_id = 1000001;

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'BASIC +PREDICATE'));

PROMPT === Memoptimized projection lookup ===
DECLARE
    v_total NUMBER;
    v_started NUMBER := DBMS_UTILITY.GET_TIME;
BEGIN
    FOR i IN 1..&ITERATIONS LOOP
        SELECT total_amount
          INTO v_total
          FROM calculation_read_projection
         WHERE calculation_id = 1000000 + MOD(i - 1, &ROW_COUNT) + 1;
    END LOOP;
    DBMS_OUTPUT.PUT_LINE(
        'projection_memoptimized_ms=' || ((DBMS_UTILITY.GET_TIME - v_started) * 10)
    );
END;
/

PROMPT === Same projection with Fast Lookup disabled (A/B control) ===
-- Evict from the memoptimize pool rather than toggling the table attribute:
-- ORA-62149 refuses ALTER TABLE ... MEMOPTIMIZE FOR READ once a table has a
-- virtual column, and any index on a TIMESTAMP WITH TIME ZONE column
-- (requested_at, here) makes Oracle add one automatically. Dropping the
-- object from the pool is enough to force the B-tree path below; the table
-- attribute itself never needs to move.
BEGIN
    DBMS_MEMOPTIMIZE.DROP_OBJECT(USER, '&ACTIVE_TABLE');
END;
/

DECLARE
    v_total NUMBER;
    v_started NUMBER := DBMS_UTILITY.GET_TIME;
BEGIN
    FOR i IN 1..&ITERATIONS LOOP
        SELECT total_amount
          INTO v_total
          FROM calculation_read_projection
         WHERE calculation_id = 1000000 + MOD(i - 1, &ROW_COUNT) + 1;
    END LOOP;
    DBMS_OUTPUT.PUT_LINE(
        'projection_btree_ms=' || ((DBMS_UTILITY.GET_TIME - v_started) * 10)
    );
END;
/

PROMPT === Transactional join/aggregation baseline ===
DECLARE
    v_total NUMBER;
    v_started NUMBER := DBMS_UTILITY.GET_TIME;
BEGIN
    FOR i IN 1..&ITERATIONS LOOP
        SELECT total_amount
          INTO v_total
          FROM calculation_transactional_view
         WHERE calculation_id = 1000000 + MOD(i - 1, &ROW_COUNT) + 1;
    END LOOP;
    DBMS_OUTPUT.PUT_LINE(
        'transactional_view_ms=' || ((DBMS_UTILITY.GET_TIME - v_started) * 10)
    );
END;
/

PROMPT === Restore Fast Lookup ===
BEGIN
    DBMS_MEMOPTIMIZE.POPULATE(USER, '&ACTIVE_TABLE');
END;
/

EXIT;
