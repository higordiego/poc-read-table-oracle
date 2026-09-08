CREATE OR REPLACE PACKAGE calculation_maintenance_pkg AS
    PROCEDURE refresh_calculation(p_calculation_id IN NUMBER);
    PROCEDURE remove_calculation(p_calculation_id IN NUMBER);
END calculation_maintenance_pkg;
/

CREATE OR REPLACE PACKAGE BODY calculation_maintenance_pkg AS
    PROCEDURE refresh_calculation(p_calculation_id IN NUMBER) IS
    BEGIN
        MERGE INTO calculation_read_projection target
        USING (
            SELECT *
              FROM calculation_transactional_view
             WHERE calculation_id = p_calculation_id
        ) source
        ON (target.calculation_id = source.calculation_id)
        WHEN MATCHED THEN UPDATE SET
            target.customer_id = source.customer_id,
            target.customer_name = source.customer_name,
            target.customer_email = source.customer_email,
            target.segment_code = source.segment_code,
            target.segment_name = source.segment_name,
            target.price_table_id = source.price_table_id,
            target.price_table_name = source.price_table_name,
            target.status = source.status,
            target.line_item_count = source.line_item_count,
            target.total_amount = source.total_amount,
            target.rules_applied_count = source.rules_applied_count,
            target.total_adjustment = source.total_adjustment,
            target.requested_at = source.requested_at,
            target.calculated_at = source.calculated_at,
            target.projection_updated_at = SYSTIMESTAMP
        WHEN NOT MATCHED THEN INSERT (
            calculation_id,
            customer_id,
            customer_name,
            customer_email,
            segment_code,
            segment_name,
            price_table_id,
            price_table_name,
            status,
            line_item_count,
            total_amount,
            rules_applied_count,
            total_adjustment,
            requested_at,
            calculated_at,
            projection_updated_at
        ) VALUES (
            source.calculation_id,
            source.customer_id,
            source.customer_name,
            source.customer_email,
            source.segment_code,
            source.segment_name,
            source.price_table_id,
            source.price_table_name,
            source.status,
            source.line_item_count,
            source.total_amount,
            source.rules_applied_count,
            source.total_adjustment,
            source.requested_at,
            source.calculated_at,
            SYSTIMESTAMP
        );
    END refresh_calculation;

    PROCEDURE remove_calculation(p_calculation_id IN NUMBER) IS
    BEGIN
        DELETE FROM calculation_read_projection WHERE calculation_id = p_calculation_id;
    END remove_calculation;
END calculation_maintenance_pkg;
/

-- Denormalized display fields only; customers change far more often than
-- their price-table assignment matters to a calculation already on record.
CREATE OR REPLACE TRIGGER trg_customers_calc_projection
AFTER UPDATE OF name, email ON customers
FOR EACH ROW
BEGIN
    UPDATE calculation_read_projection
       SET customer_name = :NEW.name,
           customer_email = :NEW.email,
           projection_updated_at = SYSTIMESTAMP
     WHERE customer_id = :NEW.id;
END;
/

-- Compound trigger from the start: refresh_calculation() reads
-- calculation_transactional_view, which joins back into calculations, so a
-- plain FOR EACH ROW trigger here would raise ORA-04091 (mutating table)
-- the moment calculations itself is the table being written.
CREATE OR REPLACE TRIGGER trg_calculations_projection
FOR INSERT OR UPDATE OR DELETE ON calculations
COMPOUND TRIGGER
    TYPE t_action IS TABLE OF VARCHAR2(10) INDEX BY VARCHAR2(40);
    g_actions t_action;

    AFTER EACH ROW IS
    BEGIN
        IF DELETING THEN
            g_actions(TO_CHAR(:OLD.id)) := 'REMOVE';
        ELSE
            g_actions(TO_CHAR(:NEW.id)) := 'REFRESH';
        END IF;
    END AFTER EACH ROW;

    AFTER STATEMENT IS
        v_key VARCHAR2(40);
    BEGIN
        v_key := g_actions.FIRST;
        WHILE v_key IS NOT NULL LOOP
            IF g_actions(v_key) = 'REMOVE' THEN
                calculation_maintenance_pkg.remove_calculation(TO_NUMBER(v_key));
            ELSE
                calculation_maintenance_pkg.refresh_calculation(TO_NUMBER(v_key));
            END IF;
            v_key := g_actions.NEXT(v_key);
        END LOOP;
    END AFTER STATEMENT;
END trg_calculations_projection;
/

-- Compound trigger to avoid ORA-04091 (mutating table) and to refresh each
-- affected calculation once after the line-item statement has completed,
-- even when it inserts/updates/deletes many rows at once.
CREATE OR REPLACE TRIGGER trg_calc_line_items_projection
FOR INSERT OR UPDATE OR DELETE ON calculation_line_items
COMPOUND TRIGGER
    TYPE t_calc_ids IS TABLE OF NUMBER INDEX BY VARCHAR2(40);
    g_calc_ids t_calc_ids;

    AFTER EACH ROW IS
    BEGIN
        IF INSERTING OR UPDATING THEN
            g_calc_ids(TO_CHAR(:NEW.calculation_id)) := :NEW.calculation_id;
        END IF;

        IF DELETING OR UPDATING THEN
            g_calc_ids(TO_CHAR(:OLD.calculation_id)) := :OLD.calculation_id;
        END IF;
    END AFTER EACH ROW;

    AFTER STATEMENT IS
        v_key VARCHAR2(40);
    BEGIN
        v_key := g_calc_ids.FIRST;
        WHILE v_key IS NOT NULL LOOP
            calculation_maintenance_pkg.refresh_calculation(g_calc_ids(v_key));
            v_key := g_calc_ids.NEXT(v_key);
        END LOOP;
    END AFTER STATEMENT;
END trg_calc_line_items_projection;
/

-- Blue/green rebuild administration. Ported as-is from the already
-- validated version of this PoC: only the object names changed
-- (order_read_projection -> calculation_read_projection,
-- order_transactional_view -> calculation_transactional_view).
CREATE OR REPLACE PACKAGE projection_admin_pkg AS
    FUNCTION active_table RETURN VARCHAR2;
    FUNCTION inactive_table RETURN VARCHAR2;
    PROCEDURE reconcile_active_slot;
    PROCEDURE build_inactive;
    FUNCTION converge_inactive RETURN NUMBER;
    PROCEDURE cutover;
    FUNCTION mismatch_count RETURN NUMBER;
    PROCEDURE request_population;
END projection_admin_pkg;
/

CREATE OR REPLACE PACKAGE BODY projection_admin_pkg AS

    FUNCTION active_table RETURN VARCHAR2 IS
        v_slot CHAR(1);
    BEGIN
        SELECT active_slot INTO v_slot
          FROM projection_control
         WHERE projection_name = 'CALCULATION_READ_PROJECTION';
        RETURN 'CALCULATION_READ_PROJECTION_' || v_slot;
    END active_table;

    FUNCTION inactive_table RETURN VARCHAR2 IS
    BEGIN
        RETURN CASE active_table
                   WHEN 'CALCULATION_READ_PROJECTION_A' THEN 'CALCULATION_READ_PROJECTION_B'
                   ELSE 'CALCULATION_READ_PROJECTION_A'
               END;
    END inactive_table;

    -- Reconciles projection_control.active_slot with the synonym's actual
    -- target. cutover() commits the synonym swap and the control-row update
    -- as two separate implicit-commit steps; a crash between them would
    -- otherwise leave the next rebuild building into the slot that is
    -- actually live.
    PROCEDURE reconcile_active_slot IS
        v_real_table VARCHAR2(40);
    BEGIN
        SELECT table_name INTO v_real_table
          FROM user_synonyms
         WHERE synonym_name = 'CALCULATION_READ_PROJECTION';

        UPDATE projection_control
           SET active_slot = SUBSTR(v_real_table, -1)
         WHERE projection_name = 'CALCULATION_READ_PROJECTION'
           AND active_slot <> SUBSTR(v_real_table, -1);
    END reconcile_active_slot;

    -- Bulk-loads the inactive slot from the transactional view. No source
    -- table is locked: the active slot keeps serving reads and receiving
    -- incremental trigger writes for the whole duration.
    PROCEDURE build_inactive IS
        v_inactive VARCHAR2(40) := inactive_table;
    BEGIN
        EXECUTE IMMEDIATE 'TRUNCATE TABLE ' || v_inactive;
        EXECUTE IMMEDIATE '
            INSERT INTO ' || v_inactive || ' (
                calculation_id, customer_id, customer_name, customer_email,
                segment_code, segment_name, price_table_id, price_table_name,
                status, line_item_count, total_amount, rules_applied_count,
                total_adjustment, requested_at, calculated_at, projection_updated_at)
            SELECT calculation_id, customer_id, customer_name, customer_email,
                   segment_code, segment_name, price_table_id, price_table_name,
                   status, line_item_count, total_amount, rules_applied_count,
                   total_adjustment, requested_at, calculated_at, SYSTIMESTAMP
              FROM calculation_transactional_view';
    END build_inactive;

    -- Pulls forward whatever the active slot's own triggers have applied
    -- since build_inactive started, and drops rows for calculations that no
    -- longer exist. Cheap: a table-to-table MERGE, not the multi-way join.
    -- Safe to call repeatedly; the caller loops on the returned delta until
    -- it converges to (near) zero.
    FUNCTION converge_inactive RETURN NUMBER IS
        v_inactive VARCHAR2(40) := inactive_table;
        v_delta    NUMBER := 0;
    BEGIN
        EXECUTE IMMEDIATE '
            MERGE INTO ' || v_inactive || ' tgt
            USING calculation_read_projection src
            ON (tgt.calculation_id = src.calculation_id)
            WHEN MATCHED THEN UPDATE SET
                tgt.customer_id = src.customer_id,
                tgt.customer_name = src.customer_name,
                tgt.customer_email = src.customer_email,
                tgt.segment_code = src.segment_code,
                tgt.segment_name = src.segment_name,
                tgt.price_table_id = src.price_table_id,
                tgt.price_table_name = src.price_table_name,
                tgt.status = src.status,
                tgt.line_item_count = src.line_item_count,
                tgt.total_amount = src.total_amount,
                tgt.rules_applied_count = src.rules_applied_count,
                tgt.total_adjustment = src.total_adjustment,
                tgt.requested_at = src.requested_at,
                tgt.calculated_at = src.calculated_at,
                tgt.projection_updated_at = src.projection_updated_at
             WHERE src.projection_updated_at > tgt.projection_updated_at
            WHEN NOT MATCHED THEN INSERT (
                calculation_id, customer_id, customer_name, customer_email,
                segment_code, segment_name, price_table_id, price_table_name,
                status, line_item_count, total_amount, rules_applied_count,
                total_adjustment, requested_at, calculated_at, projection_updated_at)
            VALUES (
                src.calculation_id, src.customer_id, src.customer_name, src.customer_email,
                src.segment_code, src.segment_name, src.price_table_id, src.price_table_name,
                src.status, src.line_item_count, src.total_amount, src.rules_applied_count,
                src.total_adjustment, src.requested_at, src.calculated_at, src.projection_updated_at)';
        v_delta := v_delta + SQL%ROWCOUNT;

        EXECUTE IMMEDIATE '
            DELETE FROM ' || v_inactive || ' tgt
             WHERE NOT EXISTS (
                 SELECT 1 FROM calculation_read_projection src
                  WHERE src.calculation_id = tgt.calculation_id
             )';
        v_delta := v_delta + SQL%ROWCOUNT;

        RETURN v_delta;
    END converge_inactive;

    -- The only step that pauses writers, and only for a final convergence
    -- pass plus a validation query -- not the whole rebuild. Both slots are
    -- always MEMOPTIMIZE FOR READ (see calculation_read_projection_b's
    -- CREATE TABLE in V1), so cutover only has to populate the pool for the
    -- slot about to go active; that call touches neither the active table
    -- nor the synonym, so it happens before anything is locked.
    PROCEDURE cutover IS
        v_active    VARCHAR2(40) := active_table;
        v_inactive  VARCHAR2(40) := inactive_table;
        v_new_slot  CHAR(1) := SUBSTR(inactive_table, -1);
        v_converged NUMBER;
        v_mismatch  NUMBER;
    BEGIN
        DBMS_MEMOPTIMIZE.POPULATE(USER, v_inactive);

        EXECUTE IMMEDIATE 'LOCK TABLE ' || v_active || ' IN SHARE MODE';

        v_converged := converge_inactive;

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
                  FROM ' || v_active || '
            )' INTO v_mismatch;

        IF v_mismatch = 0 THEN
            EXECUTE IMMEDIATE '
                SELECT COUNT(*) FROM (
                    SELECT calculation_id, customer_id, customer_name, customer_email,
                           segment_code, segment_name, price_table_id, price_table_name,
                           status, line_item_count, total_amount, rules_applied_count,
                           total_adjustment, requested_at, calculated_at
                      FROM ' || v_active || '
                    MINUS
                    SELECT calculation_id, customer_id, customer_name, customer_email,
                           segment_code, segment_name, price_table_id, price_table_name,
                           status, line_item_count, total_amount, rules_applied_count,
                           total_adjustment, requested_at, calculated_at
                      FROM ' || v_inactive || '
                )' INTO v_mismatch;
        END IF;

        IF v_mismatch > 0 THEN
            RAISE_APPLICATION_ERROR(-20001,
                'Cutover aborted: ' || v_mismatch ||
                ' row(s) differ between ' || v_inactive || ' and ' || v_active);
        END IF;

        EXECUTE IMMEDIATE 'CREATE OR REPLACE SYNONYM calculation_read_projection FOR ' || v_inactive;
        -- The statement above is DDL: Oracle commits implicitly, which is
        -- also what releases the SHARE lock taken earlier. Any writer that
        -- was blocked on it resumes right here, resolving the synonym fresh
        -- -- so it already targets v_inactive. No write is lost at the edge.

        UPDATE projection_control
           SET active_slot = v_new_slot
         WHERE projection_name = 'CALCULATION_READ_PROJECTION';
        COMMIT;
    END cutover;

    FUNCTION mismatch_count RETURN NUMBER IS
        v_source_only     NUMBER;
        v_projection_only NUMBER;
    BEGIN
        SELECT COUNT(*)
          INTO v_source_only
          FROM (
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
                FROM calculation_read_projection
          );

        SELECT COUNT(*)
          INTO v_projection_only
          FROM (
              SELECT calculation_id, customer_id, customer_name, customer_email,
                     segment_code, segment_name, price_table_id, price_table_name,
                     status, line_item_count, total_amount, rules_applied_count,
                     total_adjustment, requested_at, calculated_at
                FROM calculation_read_projection
              MINUS
              SELECT calculation_id, customer_id, customer_name, customer_email,
                     segment_code, segment_name, price_table_id, price_table_name,
                     status, line_item_count, total_amount, rules_applied_count,
                     total_adjustment, requested_at, calculated_at
                FROM calculation_transactional_view
          );

        RETURN v_source_only + v_projection_only;
    END mismatch_count;

    PROCEDURE request_population IS
    BEGIN
        DBMS_MEMOPTIMIZE.POPULATE(USER, active_table);
    END request_population;

END projection_admin_pkg;
/
