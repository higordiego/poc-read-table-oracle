package com.example.projection.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.projection.application.CalculationCommandService.LineItemRequest;
import com.example.projection.application.ProjectionService.ProjectionStatus;
import com.example.projection.support.OracleIntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ProjectionRebuildIT extends OracleIntegrationTestBase {

    @Autowired
    private CustomerService customerService;
    @Autowired
    private CalculationCommandService calculationCommandService;
    @Autowired
    private ProjectionService projectionService;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void rebuild_flipsActiveSlotAndLeavesZeroMismatches() {
        ProjectionStatus before = projectionService.ensureReady();
        String slotBefore = before.activeSlot();

        long customerId = customerService.create(1, "Rebuild Test Customer", "rebuild-it@example.com", "ACTIVE").id();
        calculationCommandService.create(customerId, 1L, List.of(new LineItemRequest(1, 5, null)));

        ProjectionStatus afterRebuild = projectionService.rebuild(true);

        assertThat(afterRebuild.ready()).isTrue();
        assertThat(afterRebuild.mismatchCount()).isZero();
        assertThat(afterRebuild.activeSlot()).isNotEqualTo(slotBefore);
        assertThat(afterRebuild.sourceRowCount()).isEqualTo(afterRebuild.projectionRowCount());
    }

    @Test
    void validate_reportsZeroMismatchesWithoutRebuilding() {
        projectionService.ensureReady();

        ProjectionStatus validated = projectionService.validate();

        assertThat(validated.mismatchCount()).isZero();
    }

    @Test
    void getMemoptimizedStatus_reflectsThePoolAndTableConfiguredByTheInitScript() {
        projectionService.ensureReady();

        ProjectionService.MemoptimizedStatus status = projectionService.getMemoptimizedStatus();

        assertThat(status.poolEnabled()).isTrue();
        assertThat(status.tableMemoptimizeRead()).isEqualTo("ENABLED");
    }

    @Test
    void ensureReady_whenSchemaVersionIsStale_forcesARebuildDespiteReadyStatus() {
        projectionService.ensureReady();
        jdbc.sql("UPDATE projection_control SET schema_version = 0 WHERE projection_name = 'CALCULATION_READ_PROJECTION'")
                .update();

        ProjectionStatus result = projectionService.ensureReady();

        assertThat(result.schemaVersion()).isEqualTo(1);
        assertThat(result.ready()).isTrue();
    }

    @Test
    void validate_whenTheProjectionHasAnOrphanRow_reportsNeedsRebuildWithAPositiveMismatchCount() {
        projectionService.ensureReady();
        long ghostId = 555_555_001L;
        jdbc.sql("""
                INSERT INTO calculation_read_projection (
                    calculation_id, customer_id, customer_name, customer_email,
                    segment_code, segment_name, price_table_id, price_table_name,
                    status, line_item_count, total_amount, rules_applied_count,
                    total_adjustment, requested_at, projection_updated_at)
                VALUES (:id, 1, 'Ghost', 'ghost@example.com', 'RETAIL', 'Retail',
                        1, 'Retail Standard 2026', 'CALCULATED', 0, 0, 0, 0,
                        SYSTIMESTAMP, SYSTIMESTAMP)
                """)
                .param("id", ghostId)
                .update();

        try {
            ProjectionStatus dirty = projectionService.validate();

            assertThat(dirty.status()).isEqualTo("NEEDS_REBUILD");
            assertThat(dirty.mismatchCount()).isGreaterThan(0);
        } finally {
            jdbc.sql("DELETE FROM calculation_read_projection WHERE calculation_id = :id")
                    .param("id", ghostId)
                    .update();
            ProjectionStatus clean = projectionService.validate();
            assertThat(clean.status()).isEqualTo("READY");
        }
    }
}
