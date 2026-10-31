package com.thabendra.telecom;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Oracle + Redis for the integration tests. Declared as beans so every test class that imports
 * this (with the same annotations) shares one Spring context and one pair of containers,
 * instead of starting Oracle again per class.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    OracleContainer oracle() {
        return new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
                .withStartupTimeout(Duration.ofMinutes(5));   // first start on a laptop can be slow
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }
}
