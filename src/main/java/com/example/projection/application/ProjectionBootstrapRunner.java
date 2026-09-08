package com.example.projection.application;

import com.example.projection.config.ProjectionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProjectionBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProjectionBootstrapRunner.class);

    private final ProjectionService projectionService;
    private final ProjectionProperties properties;

    public ProjectionBootstrapRunner(
            ProjectionService projectionService,
            ProjectionProperties properties) {
        this.projectionService = projectionService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.bootstrapOnStartup()) {
            log.warn("Projection bootstrap on startup is disabled");
            return;
        }
        ProjectionService.ProjectionStatus status = projectionService.ensureReady();
        log.info("Projection startup status: {}", status.status());
    }
}
