package com.example.projection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.projection.support.OracleIntegrationTestBase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class ProjectionMutexIT extends OracleIntegrationTestBase {

    @Autowired
    private ProjectionService projectionService;
    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void shortWaitTimeout(DynamicPropertyRegistry registry) {
        registry.add("projection.wait-timeout", () -> "3s");
        registry.add("projection.poll-interval", () -> "200ms");
    }

    @Test
    void rebuild_whenAnotherSessionHoldsTheControlRowLock_waitsThenTimesOut() throws Exception {
        jdbc.sql("UPDATE projection_control SET status = 'NEEDS_REBUILD' WHERE projection_name = 'CALCULATION_READ_PROJECTION'")
                .update();

        try (Connection lockHolder = DriverManager.getConnection(ORACLE.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
            lockHolder.setAutoCommit(false);
            try (Statement stmt = lockHolder.createStatement();
                    ResultSet rs = stmt.executeQuery("""
                            SELECT * FROM projection_control
                             WHERE projection_name = 'CALCULATION_READ_PROJECTION'
                               FOR UPDATE
                            """)) {
                rs.next();
            }

            assertThrows(IllegalStateException.class, () -> projectionService.rebuild(true));
        }

        ProjectionService.ProjectionStatus recovered = projectionService.rebuild(true);
        assertThat(recovered.ready()).isTrue();
    }

    @Test
    void rebuild_whenStatusIsStuckBuilding_throwsProjectionBuildInProgressAndTimesOutWaiting() {
        jdbc.sql("UPDATE projection_control SET status = 'BUILDING' WHERE projection_name = 'CALCULATION_READ_PROJECTION'")
                .update();

        try {
            assertThrows(IllegalStateException.class, () -> projectionService.rebuild(true));
        } finally {
            jdbc.sql("UPDATE projection_control SET status = 'NEEDS_REBUILD' WHERE projection_name = 'CALCULATION_READ_PROJECTION'")
                    .update();
            ProjectionService.ProjectionStatus recovered = projectionService.rebuild(true);
            assertThat(recovered.ready()).isTrue();
        }
    }
}
