package com.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres for integration tests. Import with
 * {@code @Import(TestcontainersConfiguration.class)}.
 *
 * <p>{@code @ServiceConnection} points the DataSource (and therefore Flyway)
 * at the container automatically. Because the container is a Spring bean,
 * it is shared by every test class that reuses the same cached context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        // Keep in sync with docker-compose.yml so tests match local dev.
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
    }
}
