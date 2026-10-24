package net.onelitefeather.blackhole.velocity.nats;

import io.nats.client.Connection;
import io.nats.client.ErrorListener;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proxy's bus client against a real NATS server with the permission set of the {@code blackhole-velocity}
 * user: subscribe on the five subject groups, never publish. Every test has its own server. Waiting is a
 * bounded poll on a queue, never a sleep.
 */
@Tag("integration")
class NatsBusIntegrationTest {

    /** The server version of the feather-core NATS chart, kept equal to docker/docker-compose.yml. */
    private static final String IMAGE = "nats:2.15.1-alpine";
    private static final long WAIT_SECONDS = 20;

    /** A backend that may do everything and a proxy user that may only subscribe, over TCP and WebSocket. */
    private static final String CONFIG = """
            port: 4222
            websocket { port: 8080, no_tls: true }
            authorization {
              users = [
                { user: "backend", password: "backend" }
                { user: "velocity", password: "velocity",
                  permissions: {
                    publish: { deny: ">" }
                    subscribe: { allow: ["blackhole.events.punishment.*", "blackhole.events.appeal.resolved",
                      "blackhole.events.section.changed", "blackhole.events.report.created", "blackhole.events.report.feedback"] }
                  } }
              ]
            }
            """;

    private final List<AutoCloseable> toClose = new ArrayList<>();
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> serverErrors = new LinkedBlockingQueue<>();
    private GenericContainer<?> server;
    private int tcpPort;

    private final BusClient.Listener listener = new BusClient.Listener() {
        @Override
        public void connected() {
            events.add("connected");
        }

        @Override
        public void disconnected() {
            events.add("disconnected");
        }

        @Override
        public void message(@NotNull String subject, byte @NotNull [] body) {
            events.add(subject + " " + new String(body, StandardCharsets.UTF_8));
        }
    };

    @BeforeEach
    @SuppressWarnings("resource")
    void startServer() throws Exception {
        // Like the other integration tests: skipped, not failed, on a machine without Docker.
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        try (ServerSocket free = new ServerSocket(0)) {
            this.tcpPort = free.getLocalPort();
        }
        // Fixed host ports survive a container restart, which the reconnect test needs.
        GenericContainer<?> container = new GenericContainer<>(IMAGE).withExposedPorts(4222, 8080)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
                .withCommand("-c", "cat > /etc/nats.conf <<'EOF'\n" + CONFIG + "EOF\nexec nats-server -c /etc/nats.conf")
                .waitingFor(Wait.forLogMessage(".*Server is ready.*\\n", 1));
        container.setPortBindings(List.of(this.tcpPort + ":4222"));
        container.start();
        this.server = container;
    }

    @AfterEach
    void stopEverything() throws Exception {
        for (AutoCloseable closeable : this.toClose.reversed()) {
            closeable.close();
        }
        if (this.server != null) {
            this.server.stop();
        }
    }

    private String tcpUrl(String user) {
        return "nats://" + user + ":" + user + "@" + this.server.getHost() + ":" + this.tcpPort;
    }

    private String webSocketUrl(String user) {
        return "ws://" + user + ":" + user + "@" + this.server.getHost() + ":" + this.server.getMappedPort(8080);
    }

    private JnatsBusClient proxy(String url) {
        JnatsBusClient client = new JnatsBusClient(url, "", Duration.ofMillis(200), JnatsBusClient.daemonThread());
        this.toClose.add(client);
        assertTrue(client.start(this.listener));
        return client;
    }

    private void publish(String subject, String body) throws Exception {
        try (Connection connection = Nats.connect(tcpUrl("backend"))) {
            connection.publish(subject, body.getBytes(StandardCharsets.UTF_8));
            connection.flush(Duration.ofSeconds(5));
        }
    }

    /**
     * Waits, bounded by {@link #WAIT_SECONDS} in total, until the {@code expected} event is queued. Connection-state
     * events ({@code connected}, {@code disconnected}) that arrive first are skipped, because the connection may
     * report its state more than once. Any other event that is not {@code expected} fails the test at once.
     */
    private void awaitEvent(String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (true) {
            long remaining = deadline - System.nanoTime();
            String event = remaining > 0 ? this.events.poll(remaining, TimeUnit.NANOSECONDS) : null;
            assertNotNull(event, "no '" + expected + "' event within " + WAIT_SECONDS + " s");
            if (event.equals(expected)) {
                return;
            }
            assertTrue(event.equals("connected") || event.equals("disconnected"),
                    "expected '" + expected + "' but got '" + event + "'");
        }
    }

    /** A raw connection of the proxy's user whose server errors are collected. */
    private Connection rawProxyConnection(String url) throws Exception {
        Connection connection = Nats.connect(new Options.Builder().server(url).errorListener(new ErrorListener() {
            @Override
            public void errorOccurred(Connection conn, String error) {
                serverErrors.add(error);
            }
        }).build());
        this.toClose.add(connection);
        return connection;
    }

    @Test
    void aPublishedPunishmentEventReachesTheProxyOverTcp() throws Exception {
        proxy(tcpUrl("velocity"));
        // The subscriptions are in place before the cache is trusted.
        awaitEvent("connected");

        publish("blackhole.events.punishment.created", "{\"payload\":{}}");

        awaitEvent("blackhole.events.punishment.created {\"payload\":{}}");
    }

    @Test
    void aPublishedPunishmentEventReachesTheProxyOverTheWebSocketListener() throws Exception {
        proxy(webSocketUrl("velocity"));
        awaitEvent("connected");

        publish("blackhole.events.report.created", "{\"payload\":{\"reportIdentifier\":\"r1\"}}");

        awaitEvent("blackhole.events.report.created {\"payload\":{\"reportIdentifier\":\"r1\"}}");
    }

    @Test
    void everySubscribedSubjectGroupIsDelivered() throws Exception {
        proxy(tcpUrl("velocity"));
        awaitEvent("connected");

        for (String subject : List.of("blackhole.events.punishment.expired", "blackhole.events.punishment.revoked",
                "blackhole.events.appeal.resolved", "blackhole.events.section.changed", "blackhole.events.report.feedback")) {
            publish(subject, "{}");
            awaitEvent(subject + " {}");
        }
    }

    @Test
    void anEventOutsideTheSubscriptionsIsNotDelivered() throws Exception {
        proxy(tcpUrl("velocity"));
        awaitEvent("connected");

        publish("blackhole.events.case.created", "{\"ignored\":true}");
        publish("blackhole.events.appeal.resolved", "{\"wanted\":true}");

        // The case event would fail this wait as an unexpected message, so the next delivered event must be the appeal.
        awaitEvent("blackhole.events.appeal.resolved {\"wanted\":true}");
    }

    @Test
    void theProxyCredentialCannotPublish() throws Exception {
        Connection connection = rawProxyConnection(tcpUrl("velocity"));

        connection.publish("blackhole.events.punishment.created", "forged".getBytes(StandardCharsets.UTF_8));
        connection.flush(Duration.ofSeconds(5));

        String error = this.serverErrors.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(error, "the server must answer a publish with an error");
        assertTrue(error.contains("Permissions Violation for Publish"), error);
    }

    @Test
    void theProxyCredentialCannotSubscribeToCaseEvents() throws Exception {
        Connection connection = rawProxyConnection(tcpUrl("velocity"));

        connection.subscribe("blackhole.events.case.>");
        connection.flush(Duration.ofSeconds(5));

        String error = this.serverErrors.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(error, "the server must refuse the subscription");
        assertTrue(error.contains("Permissions Violation for Subscription"), error);
    }

    @Test
    void losingTheServerIsReportedAndTheClientReconnectsAndSubscribesAgain() throws Exception {
        proxy(tcpUrl("velocity"));
        awaitEvent("connected");

        this.server.getDockerClient().restartContainerCmd(this.server.getContainerId()).withTimeout(1).exec();

        // The cache must be discarded as soon as the connection drops, then trusted again only once resubscribed.
        awaitEvent("disconnected");
        awaitEvent("connected");

        publish("blackhole.events.punishment.created", "{\"after\":\"restart\"}");
        awaitEvent("blackhole.events.punishment.created {\"after\":\"restart\"}");
    }

    @Test
    void startingWhileTheServerIsDownDoesNotBlockAndConnectsOnceItIsUp() throws Exception {
        this.server.getDockerClient().stopContainerCmd(this.server.getContainerId()).withTimeout(1).exec();

        long started = System.nanoTime();
        proxy(tcpUrl("velocity"));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0, "start() returns at once");

        this.server.getDockerClient().startContainerCmd(this.server.getContainerId()).exec();

        // Failed attempts only ever report a disconnect, the cache stays untrusted until the connection is up.
        awaitEvent("connected");
    }
}
