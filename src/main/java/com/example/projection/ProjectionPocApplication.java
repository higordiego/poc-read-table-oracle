package com.example.projection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ProjectionPocApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProjectionPocApplication.class, args);
    }
}
