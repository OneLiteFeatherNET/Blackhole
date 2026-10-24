package net.onelitefeather.blackhole.discord.notify;

import io.nats.client.Connection;
import io.nats.client.ErrorListener;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import net.onelitefeather.blackhole.discord.config.BotConfig.NatsSettings;
import net.onelitefeather.blackhole.discord.notify.NotificationHandler.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bot's consumer against a real NATS server, the stream created the way the backend creates it. Every
 * test has its own server. Waiting is a bounded poll, latch or future, never a sleep.
 */
@Tag("integration")
class NatsConsumerIntegrationTest {

    /** The server version of the feather-core NATS chart, kept equal to docker/docker-compose.yml. */
    private static final String IMAGE = "nats:2.15.1-alpine";
    private static final long WAIT_SECONDS = 20;
    private static final String[] TYPES = {"report.created", "appeal.resolved"};

    private final List<AutoCloseable> toClose = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private GenericContainer<?> server;

    @BeforeEach
    void startServer() {
        // Like the other integration tests: skipped, not failed, on a machine without Docker.
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        this.server = open(null);
    }

    @AfterEach
    void stopEverything() throws Exception {
        for (AutoCloseable closeable : this.toClose.reversed()) {
            closeable.close();
        }
        this.scheduler.shutdownNow();
        if (this.server != null) {
            this.server.stop();
        }
    }

    @SuppressWarnings("resource")
    private static GenericContainer<?> open(String config) {
        GenericContainer<?> container = new GenericContainer<>(IMAGE).withExposedPorts(4222)
                .waitingFor(Wait.forLogMessage(".*Server is ready.*\\n", 1));
        if (config == null) {
            container.withCommand("-js");
        } else {
            container.withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
                    .withCommand("-c", "printf '%s' '" + config + "' > /etc/nats.conf && exec nats-server -c /etc/nats.conf -js");
        }
        container.start();
        return container;
    }

    private String hostPort() {
        return this.server.getHost() + ":" + this.server.getMappedPort(4222);
    }

    /** The backend's side: the stream. */
    private void createStream(String userInfo) throws Exception {
        try (Connection connection = Nats.connect("nats://" + userInfo + hostPort())) {
            connection.jetStreamManagement().addStream(StreamConfiguration.builder()
                    .name(NatsConsumer.STREAM).subjects("blackhole.events.>").storageType(StorageType.File).build());
        }
    }

    private void publish(String userInfo, String eventType, String body) throws Exception {
        try (Connection connection = Nats.connect("nats://" + userInfo + hostPort())) {
            connection.jetStream().publish("blackhole.events." + eventType, body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private NatsConsumer consumer(String userInfo, java.util.function.Function<byte[], Outcome> handler, Duration retryDelay) {
        NatsConsumer consumer = new NatsConsumer(new NatsSettings("nats://" + userInfo + hostPort(), ""),
                NatsConsumer.NOTIFY_DURABLE, TYPES, handler, this.scheduler, retryDelay, Duration.ofMillis(200));
        this.toClose.add(consumer);
        consumer.start();
        return consumer;
    }

    @Test
    void aPublishedEventReachesTheHandlerAndIsAcknowledged() throws Exception {
        createStream("");
        BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
        NatsConsumer consumer = consumer("", body -> {
            bodies.add(new String(body, StandardCharsets.UTF_8));
            return Outcome.DONE;
        }, Duration.ofMillis(200));
        consumer.ready().get(WAIT_SECONDS, TimeUnit.SECONDS);

        publish("", "report.created", "{\"eventType\":\"report.created\"}");

        assertEquals("{\"eventType\":\"report.created\"}", bodies.poll(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void anEventTypeOutsideTheFilterIsNotDelivered() throws Exception {
        createStream("");
        BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
        NatsConsumer consumer = consumer("", body -> {
            bodies.add(new String(body, StandardCharsets.UTF_8));
            return Outcome.DONE;
        }, Duration.ofMillis(200));
        consumer.ready().get(WAIT_SECONDS, TimeUnit.SECONDS);

        publish("", "punishment.created", "ignored");
        publish("", "appeal.resolved", "wanted");

        assertEquals("wanted", bodies.poll(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void aRetryIsRedeliveredAfterTheDelay() throws Exception {
        createStream("");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        NatsConsumer consumer = consumer("", body -> {
            twice.countDown();
            return calls.incrementAndGet() == 1 ? Outcome.RETRY : Outcome.DONE;
        }, Duration.ofMillis(200));
        consumer.ready().get(WAIT_SECONDS, TimeUnit.SECONDS);

        publish("", "report.created", "{}");

        assertTrue(twice.await(WAIT_SECONDS, TimeUnit.SECONDS), "the message came back after the nak");
    }

    @Test
    void theConsumerStartsOnceTheBackendHasCreatedTheStream() throws Exception {
        // The bot comes up first: the stream does not exist yet, so the first attempt fails and is retried.
        NatsConsumer consumer = consumer("", body -> Outcome.DONE, Duration.ofMillis(200));
        assertFalse(consumer.ready().isDone(), "no stream, not ready");

        createStream("");

        consumer.ready().get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** The permissions of the user blackhole-discord in design D6, plus an unrestricted user for the test itself. */
    private static final String CONFIG = """
            authorization {
              users = [
                { user: backend, password: secret },
                { user: bot, password: secret,
                  permissions: {
                    publish: { allow: [
                      "$JS.API.STREAM.INFO.BLACKHOLE_EVENTS",
                      "$JS.API.CONSUMER.CREATE.BLACKHOLE_EVENTS.blackhole-discord-notify",
                      "$JS.API.CONSUMER.CREATE.BLACKHOLE_EVENTS.blackhole-discord-notify.>",
                      "$JS.API.CONSUMER.DURABLE.CREATE.BLACKHOLE_EVENTS.blackhole-discord-notify",
                      "$JS.API.CONSUMER.INFO.BLACKHOLE_EVENTS.blackhole-discord-notify",
                      "$JS.API.CONSUMER.MSG.NEXT.BLACKHOLE_EVENTS.blackhole-discord-notify",
                      "$JS.ACK.BLACKHOLE_EVENTS.blackhole-discord-notify.>",
                      "$JS.API.CONSUMER.CREATE.BLACKHOLE_EVENTS.blackhole-discord-case",
                      "$JS.API.CONSUMER.CREATE.BLACKHOLE_EVENTS.blackhole-discord-case.>",
                      "$JS.API.CONSUMER.DURABLE.CREATE.BLACKHOLE_EVENTS.blackhole-discord-case",
                      "$JS.API.CONSUMER.INFO.BLACKHOLE_EVENTS.blackhole-discord-case",
                      "$JS.API.CONSUMER.MSG.NEXT.BLACKHOLE_EVENTS.blackhole-discord-case",
                      "$JS.ACK.BLACKHOLE_EVENTS.blackhole-discord-case.>"
                    ] }
                    subscribe: { allow: ["_INBOX.>"] }
                  } }
              ]
            }
            """;

    @Test
    void theBotWorksWithExactlyItsNatsPermissionsAndCannotPublishEvents() throws Exception {
        this.server.stop();
        this.server = open(CONFIG);
        createStream("backend:secret@");
        BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
        NatsConsumer consumer = consumer("bot:secret@", body -> {
            bodies.add(new String(body, StandardCharsets.UTF_8));
            return Outcome.DONE;
        }, Duration.ofMillis(200));
        consumer.ready().get(WAIT_SECONDS, TimeUnit.SECONDS);

        publish("backend:secret@", "report.created", "from the backend");

        assertEquals("from the backend", bodies.poll(WAIT_SECONDS, TimeUnit.SECONDS),
                "creating the durable, pulling and acking are allowed");
        CompletableFuture<String> violation = new CompletableFuture<>();
        Options options = new Options.Builder().server("nats://bot:secret@" + hostPort())
                .errorListener(new ErrorListener() {
                    @Override
                    public void errorOccurred(Connection conn, String error) {
                        violation.complete(error);
                    }
                }).build();
        try (Connection connection = Nats.connect(options)) {
            connection.publish("blackhole.events.report.created", "forged".getBytes(StandardCharsets.UTF_8));
            String error = violation.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(error);
            assertTrue(error.toLowerCase().contains("permissions violation"), "got " + error);
        }
    }
}
