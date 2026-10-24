package net.onelitefeather.blackhole.backend.support;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * A real NATS server for integration tests. Testcontainers has no NATS module, so this is a plain
 * container. The tag follows the server of the feather-core NATS chart (see docker/docker-compose.yml).
 */
public final class NatsTestServer {

    /** The server image, kept equal to docker/docker-compose.yml. */
    public static final String IMAGE = "nats:2.15.1-alpine";

    private static final int CLIENT_PORT = 4222;

    private NatsTestServer() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** Skips the calling test (instead of failing it) on a machine without Docker, like the other integration tests. */
    public static void assumeDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    /** A server with JetStream and no authentication; the caller starts and stops it. */
    @SuppressWarnings("resource")
    public static GenericContainer<?> open() {
        return new GenericContainer<>(IMAGE)
                .withCommand("-js")
                .withExposedPorts(CLIENT_PORT)
                .waitingFor(Wait.forLogMessage(".*Server is ready.*\\n", 1));
    }

    /**
     * A server that first writes {@code config} to a file and then starts with it (the image takes no
     * config on the command line, and copying a file in needs libraries the test classpath lacks).
     */
    @SuppressWarnings("resource")
    public static GenericContainer<?> openWithConfig(String config) {
        return new GenericContainer<>(IMAGE)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
                .withCommand("-c", "printf '%s' '" + config.replace("'", "") + "' > /etc/nats.conf"
                        + " && exec nats-server -c /etc/nats.conf -js")
                .withExposedPorts(CLIENT_PORT)
                .waitingFor(Wait.forLogMessage(".*Server is ready.*\\n", 1));
    }

    /** The client URL of a running server. */
    public static String url(GenericContainer<?> server) {
        return "nats://" + server.getHost() + ":" + server.getMappedPort(CLIENT_PORT);
    }
}
