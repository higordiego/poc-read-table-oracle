package com.example.projection.application;

import java.net.InetAddress;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.example.projection.config.ProjectionProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionService.class);
    private static final String PROJECTION_NAME = "CALCULATION_READ_PROJECTION";
    private static final int SCHEMA_VERSION = 1;
    /** Convergence passes run against the inactive slot before cutover claims the final lock. */
    private static final int CONVERGE_PASSES = 3;

    private final JdbcClient jdbc;
    private final ProjectionProperties properties;
    private final TransactionTemplate transactions;
    private final Timer rebuildTimer;
    private final Counter rebuildCounter;

    public ProjectionService(
            JdbcClient jdbc,
            ProjectionProperties properties,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.rebuildTimer = Timer.builder("projection.rebuild.duration")
                .description("Duration of an Oracle read projection rebuild")
                .register(meterRegistry);
        this.rebuildCounter = Counter.builder("projection.rebuild.completed")
                .description("Successful Oracle read projection rebuilds")
                .register(meterRegistry);
    }

    public ProjectionStatus ensureReady() {
        ProjectionStatus status = getStatus();
        if (status.ready() && status.schemaVersion() == SCHEMA_VERSION) {
            requestPopulation();
            return getStatus();
        }
        return rebuild(false);
    }

    public ProjectionStatus rebuild(boolean force) {
        return rebuildTimer.record(() -> attemptRebuild(force));
    }

    public ProjectionStatus validate() {
        try {
            ProjectionStatus status = transactions.execute(ignored -> {
                lockControlRow();
                // No longer locks the 30 source tables: the projection is
                // kept in sync by the same-transaction triggers, so under
                // normal operation source and projection already agree. A
                // transient mismatch under heavy concurrent writes (the two
                // MINUS queries inside mismatch_count run as separate
                // statements) self-corrects on the next validate/rebuild.
                long sourceCount = count("calculation_transactional_view");
                long projectionCount = count("calculation_read_projection");
                long mismatches = mismatchCount();

                String nextStatus = mismatches == 0 && sourceCount == projectionCount
                        ? "READY"
                        : "NEEDS_REBUILD";
                jdbc.sql("""
                        UPDATE projection_control
                           SET status = :status,
                               last_validated_at = SYSTIMESTAMP,
                               source_row_count = :sourceCount,
                               projection_row_count = :projectionCount,
                               mismatch_count = :mismatches,
                               last_error = CASE WHEN :mismatches = 0 THEN NULL
                                                 ELSE 'Projection validation failed' END,
                               updated_at = SYSTIMESTAMP
                         WHERE projection_name = :name
                        """)
                        .param("status", nextStatus)
                        .param("sourceCount", sourceCount)
                        .param("projectionCount", projectionCount)
                        .param("mismatches", mismatches)
                        .param("name", PROJECTION_NAME)
                        .update();
                return getStatus();
            });
            return requireResult(status);
        } catch (DataAccessException exception) {
            if (isOracleResourceBusy(exception)) {
                throw new IllegalStateException("A projection build or validation is already running", exception);
            }
            throw exception;
        }
    }

    public ProjectionStatus getStatus() {
        return jdbc.sql("""
                SELECT projection_name,
                       status,
                       schema_version,
                       active_slot,
                       TO_CHAR(last_built_at, 'YYYY-MM-DD"T"HH24:MI:SS.FF3TZH:TZM') AS last_built_at,
                       TO_CHAR(last_validated_at, 'YYYY-MM-DD"T"HH24:MI:SS.FF3TZH:TZM') AS last_validated_at,
                       TO_CHAR(population_requested_at, 'YYYY-MM-DD"T"HH24:MI:SS.FF3TZH:TZM') AS population_requested_at,
                       source_row_count,
                       projection_row_count,
                       mismatch_count,
                       build_owner,
                       last_error
                  FROM projection_control
                 WHERE projection_name = :name
                """)
                .param("name", PROJECTION_NAME)
                .query(ProjectionService::mapStatus)
                .single();
    }

    public MemoptimizedStatus getMemoptimizedStatus() {
        long poolBytes = jdbc.sql("""
                SELECT TO_NUMBER(value)
                  FROM v$parameter
                 WHERE name = 'memoptimize_pool_size'
                """)
                .query(Long.class)
                .single();

        String activeSlot = jdbc.sql("""
                SELECT active_slot
                  FROM projection_control
                 WHERE projection_name = :name
                """)
                .param("name", PROJECTION_NAME)
                .query(String.class)
                .single();

        // calculation_read_projection is a synonym over the active slot; MEMOPTIMIZE_READ
        // is a physical table attribute, so it has to be checked on the real object.
        String tableStatus = jdbc.sql("""
                SELECT memoptimize_read
                  FROM user_tables
                 WHERE table_name = :tableName
                """)
                .param("tableName", PROJECTION_NAME + "_" + activeSlot)
                .query(String.class)
                .single();

        return new MemoptimizedStatus(
                poolBytes > 0,
                poolBytes,
                tableStatus,
                "SELECT ... FROM CALCULATION_READ_PROJECTION WHERE CALCULATION_ID = :id",
                "Confirm with DBMS_XPLAN: INDEX UNIQUE SCAN READ OPTIM");
    }

    private ProjectionStatus attemptRebuild(boolean force) {
        try {
            ProjectionStatus result = requireResult(
                    transactions.execute(ignored -> rebuildInTransaction(force)));
            requestPopulation();
            return getStatus();
        } catch (ProjectionBuildInProgressException exception) {
            log.info("Another instance is already building the projection; waiting for READY");
            return waitUntilReady();
        } catch (DataAccessException exception) {
            if (isOracleResourceBusy(exception)) {
                log.info("Another instance owns the projection bootstrap lock; waiting for READY");
                return waitUntilReady();
            }
            markNeedsRebuild(exception);
            throw exception;
        } catch (RuntimeException exception) {
            markNeedsRebuild(exception);
            throw exception;
        }
    }

    private ProjectionStatus rebuildInTransaction(boolean force) {
        ProjectionStatus current = lockControlRow();
        if (!force && current.ready() && current.schemaVersion() == SCHEMA_VERSION) {
            return current;
        }

        // Heals projection_control.active_slot if a previous cutover crashed
        // between the synonym swap (DDL, implicit commit) and the control
        // row update that follows it.
        jdbc.sql("BEGIN projection_admin_pkg.reconcile_active_slot; END;").update();

        String owner = buildOwner();
        log.info("Starting idempotent projection rebuild as {}", owner);
        int claimed = jdbc.sql("""
                UPDATE projection_control
                   SET status = 'BUILDING',
                       build_owner = :owner,
                       last_error = NULL,
                       updated_at = SYSTIMESTAMP
                 WHERE projection_name = :name
                   AND status <> 'BUILDING'
                """)
                .param("owner", owner)
                .param("name", PROJECTION_NAME)
                .update();
        if (claimed == 0) {
            // The FOR UPDATE row lock from lockControlRow() only protects
            // this method up to the first DDL statement below: cutover()
            // swaps a synonym, which commits implicitly in Oracle and
            // releases that lock early. This status guard is what actually
            // keeps two instances from building at the same time end to end.
            // Abort (roll back) instead of waiting right here: this
            // transaction is still holding the FOR UPDATE lock on this same
            // row, and waiting while holding it would block the other
            // instance's own closing UPDATE and deadlock both sides.
            throw new ProjectionBuildInProgressException();
        }

        // Bulk-builds the inactive slot from the transactional view. No
        // source table is locked: the active slot keeps serving reads and
        // receiving incremental trigger writes for the whole duration.
        jdbc.sql("BEGIN projection_admin_pkg.build_inactive; END;").update();
        for (int pass = 0; pass < CONVERGE_PASSES; pass++) {
            // Pulls forward whatever the active slot's triggers applied
            // while the bulk build was running. Cheap table-to-table MERGE;
            // cutover() below still runs one authoritative final pass under
            // lock and validates before it swaps anything.
            jdbc.sql("""
                    DECLARE
                        v_delta NUMBER;
                    BEGIN
                        v_delta := projection_admin_pkg.converge_inactive;
                    END;
                    """)
                    .update();
        }

        // The only step that pauses writers: locks just the active
        // projection table, converges once more, validates, and swaps the
        // synonym. Aborts (RAISE_APPLICATION_ERROR) without swapping if the
        // slots still disagree after the final convergence pass.
        jdbc.sql("BEGIN projection_admin_pkg.cutover; END;").update();

        long sourceCount = count("calculation_transactional_view");
        long projectionCount = count("calculation_read_projection");
        long mismatches = mismatchCount();
        if (mismatches != 0 || sourceCount != projectionCount) {
            throw new IllegalStateException(
                    "Projection validation failed: source=%d projection=%d mismatches=%d"
                            .formatted(sourceCount, projectionCount, mismatches));
        }

        jdbc.sql("""
                UPDATE projection_control
                   SET status = 'READY',
                       schema_version = :schemaVersion,
                       last_built_at = SYSTIMESTAMP,
                       last_validated_at = SYSTIMESTAMP,
                       source_row_count = :sourceCount,
                       projection_row_count = :projectionCount,
                       mismatch_count = 0,
                       build_owner = :owner,
                       last_error = NULL,
                       updated_at = SYSTIMESTAMP
                 WHERE projection_name = :name
                """)
                .param("schemaVersion", SCHEMA_VERSION)
                .param("sourceCount", sourceCount)
                .param("projectionCount", projectionCount)
                .param("owner", owner)
                .param("name", PROJECTION_NAME)
                .update();

        ProjectionStatus ready = getStatus();
        rebuildCounter.increment();
        log.info("Projection is READY with {} rows", projectionCount);

        return ready;
    }

    private ProjectionStatus lockControlRow() {
        return jdbc.sql("""
                SELECT projection_name,
                       status,
                       schema_version,
                       active_slot,
                       TO_CHAR(last_built_at, 'YYYY-MM-DD"T"HH24:MI:SS.FF3TZH:TZM') AS last_built_at,
                       TO_CHAR(last_validated_at, 'YYYY-MM-DD"T"HH24:MI:SS.FF3TZH:TZM') AS last_validated_at,
                       TO_CHAR(population_requested_at, 'YYYY-MM-DD"T"HH24:MI:SS.FF3TZH:TZM') AS population_requested_at,
                       source_row_count,
                       projection_row_count,
                       mismatch_count,
                       build_owner,
                       last_error
                  FROM projection_control
                 WHERE projection_name = :name
                   FOR UPDATE WAIT 0
                """)
                .param("name", PROJECTION_NAME)
                .query(ProjectionService::mapStatus)
                .single();
    }

    private ProjectionStatus waitUntilReady() {
        Instant deadline = Instant.now().plus(properties.waitTimeout());
        while (Instant.now().isBefore(deadline)) {
            ProjectionStatus status = getStatus();
            if (status.ready() && status.schemaVersion() == SCHEMA_VERSION) {
                return status;
            }
            sleep(properties.pollInterval());
        }
        throw new IllegalStateException(
                "Timed out waiting for another instance to bootstrap the projection");
    }

    private long mismatchCount() {
        return jdbc.sql("SELECT projection_admin_pkg.mismatch_count FROM dual")
                .query(Long.class)
                .single();
    }

    private long count(String tableOrView) {
        return jdbc.sql("SELECT COUNT(*) FROM " + tableOrView)
                .query(Long.class)
                .single();
    }

    private void markNeedsRebuild(RuntimeException failure) {
        try {
            transactions.executeWithoutResult(ignored -> jdbc.sql("""
                    UPDATE projection_control
                       SET status = 'NEEDS_REBUILD',
                           last_error = :error,
                           updated_at = SYSTIMESTAMP
                     WHERE projection_name = :name
                    """)
                    .param("error", abbreviate(failure.getMessage(), 1900))
                    .param("name", PROJECTION_NAME)
                    .update());
        } catch (RuntimeException statusFailure) {
            failure.addSuppressed(statusFailure);
        }
    }

    private static ProjectionStatus mapStatus(ResultSet rs, int rowNumber) throws SQLException {
        return new ProjectionStatus(
                rs.getString("projection_name"),
                rs.getString("status"),
                rs.getInt("schema_version"),
                rs.getString("active_slot"),
                rs.getString("last_built_at"),
                rs.getString("last_validated_at"),
                rs.getString("population_requested_at"),
                nullableLong(rs, "source_row_count"),
                nullableLong(rs, "projection_row_count"),
                nullableLong(rs, "mismatch_count"),
                rs.getString("build_owner"),
                rs.getString("last_error"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static boolean isOracleResourceBusy(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException && sqlException.getErrorCode() == 54) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String buildOwner() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + UUID.randomUUID();
        } catch (Exception ignored) {
            return "unknown:" + UUID.randomUUID();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for projection bootstrap", exception);
        }
    }

    private static String abbreviate(String message, int maxLength) {
        String value = message == null ? "Unknown projection failure" : message;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static ProjectionStatus requireResult(ProjectionStatus status) {
        if (status == null) {
            throw new IllegalStateException("Projection transaction returned no status");
        }
        return status;
    }

    private void requestPopulation() {
        jdbc.sql("BEGIN projection_admin_pkg.request_population; END;").update();
        jdbc.sql("""
                UPDATE projection_control
                   SET population_requested_at = SYSTIMESTAMP,
                       updated_at = SYSTIMESTAMP
                 WHERE projection_name = :name
                """)
                .param("name", PROJECTION_NAME)
                .update();
        log.info("Requested CALCULATION_READ_PROJECTION population in the memoptimize pool");
    }

    public record ProjectionStatus(
            String projectionName,
            String status,
            int schemaVersion,
            String activeSlot,
            String lastBuiltAt,
            String lastValidatedAt,
            String populationRequestedAt,
            Long sourceRowCount,
            Long projectionRowCount,
            Long mismatchCount,
            String buildOwner,
            String lastError) {

        public boolean ready() {
            return "READY".equals(status);
        }
    }

    public record MemoptimizedStatus(
            boolean poolEnabled,
            long poolBytes,
            String tableMemoptimizeRead,
            String eligibleQueryShape,
            String expectedPlanOperation) {
    }

    /**
     * Signals that another instance already owns the projection build
     * (the {@code status <> 'BUILDING'} guard matched zero rows). Thrown
     * from inside the transaction so Spring rolls it back -- and releases
     * this session's {@code FOR UPDATE} row lock -- before the caller waits
     * for the other instance to reach READY.
     */
    private static final class ProjectionBuildInProgressException extends RuntimeException {
    }
}
