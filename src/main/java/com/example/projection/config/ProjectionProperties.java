package com.example.projection.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("projection")
public record ProjectionProperties(
        boolean bootstrapOnStartup,
        Duration waitTimeout,
        Duration pollInterval) {
}
