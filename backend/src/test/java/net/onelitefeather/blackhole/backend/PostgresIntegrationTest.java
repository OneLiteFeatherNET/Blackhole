package net.onelitefeather.blackhole.backend;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

/**
 * Base for tests that boot the application against real PostgreSQL, RabbitMQ and Redis
 * containers. Tagged {@code integration}: needs Docker, so exclude it with
 * {@code ./gradlew :backend:test -PexcludeTags=integration}. Without Docker the tests are skipped.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresIntegrationTest {

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("blackhole")
            .withUsername("blackhole")
            .withPassword("blackhole");

    @Container
    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    @Container
    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    /**
     * Property overrides that point the application at the running containers. Everything else
     * keeps the defaults from {@code application.yml}.
     */
    protected static Map<String, Object> contextProperties() {
        return Map.<String, Object>of(
                "datasources.default.url", POSTGRES.getJdbcUrl(),
                "datasources.default.username", POSTGRES.getUsername(),
                "datasources.default.password", POSTGRES.getPassword(),
                "rabbitmq.host", RABBIT.getHost(),
                "rabbitmq.port", RABBIT.getAmqpPort(),
                "redis.uri", "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379)
        );
    }
}
