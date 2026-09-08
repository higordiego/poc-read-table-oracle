package com.example.projection.application;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("readProjection")
public class ProjectionHealthIndicator implements HealthIndicator {

    private final ProjectionService projectionService;

    public ProjectionHealthIndicator(ProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @Override
    public Health health() {
        try {
            ProjectionService.ProjectionStatus status = projectionService.getStatus();
            Health.Builder health = status.ready() ? Health.up() : Health.down();
            return health
                    .withDetail("status", status.status())
                    .withDetail("schemaVersion", status.schemaVersion())
                    .withDetail("activeSlot", status.activeSlot())
                    .withDetail("sourceRows", status.sourceRowCount())
                    .withDetail("projectionRows", status.projectionRowCount())
                    .withDetail("mismatches", status.mismatchCount())
                    .build();
        } catch (RuntimeException exception) {
            return Health.down(exception).build();
        }
    }
}
