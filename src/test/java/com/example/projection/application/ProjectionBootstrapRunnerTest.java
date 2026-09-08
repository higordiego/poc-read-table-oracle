package com.example.projection.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.projection.config.ProjectionProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class ProjectionBootstrapRunnerTest {

    private final ProjectionService projectionService = mock(ProjectionService.class);
    private final ApplicationArguments arguments = mock(ApplicationArguments.class);

    @Test
    void run_whenBootstrapIsEnabled_callsEnsureReady() {
        ProjectionProperties properties = new ProjectionProperties(true, Duration.ofSeconds(1), Duration.ofMillis(1));
        when(projectionService.ensureReady()).thenReturn(new ProjectionService.ProjectionStatus(
                "CALCULATION_READ_PROJECTION", "READY", 1, "A",
                null, null, null, 0L, 0L, 0L, null, null));
        ProjectionBootstrapRunner runner = new ProjectionBootstrapRunner(projectionService, properties);

        runner.run(arguments);

        verify(projectionService).ensureReady();
    }

    @Test
    void run_whenBootstrapIsDisabled_neverCallsEnsureReady() {
        ProjectionProperties properties = new ProjectionProperties(false, Duration.ofSeconds(1), Duration.ofMillis(1));
        ProjectionBootstrapRunner runner = new ProjectionBootstrapRunner(projectionService, properties);

        runner.run(arguments);

        verify(projectionService, never()).ensureReady();
    }
}
