package com.example.projection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class ProjectionHealthIndicatorTest {

    private final ProjectionService projectionService = mock(ProjectionService.class);
    private final ProjectionHealthIndicator indicator = new ProjectionHealthIndicator(projectionService);

    @Test
    void health_whenProjectionIsReady_reportsUpWithProjectionDetails() {
        when(projectionService.getStatus()).thenReturn(new ProjectionService.ProjectionStatus(
                "CALCULATION_READ_PROJECTION", "READY", 1, "A",
                null, null, null, 5L, 5L, 0L, null, null));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("status", "READY")
                .containsEntry("activeSlot", "A")
                .containsEntry("mismatches", 0L);
    }

    @Test
    void health_whenProjectionNeedsRebuild_reportsDown() {
        when(projectionService.getStatus()).thenReturn(new ProjectionService.ProjectionStatus(
                "CALCULATION_READ_PROJECTION", "NEEDS_REBUILD", 1, "A",
                null, null, null, null, null, null, null, null));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void health_whenProjectionServiceThrows_reportsDownWithTheException() {
        when(projectionService.getStatus()).thenThrow(new IllegalStateException("no connection"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }
}
