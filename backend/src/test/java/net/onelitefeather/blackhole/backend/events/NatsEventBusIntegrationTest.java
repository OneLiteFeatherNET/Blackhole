package net.onelitefeather.blackhole.backend.events;

import io.micronaut.json.JsonMapper;
import io.nats.client.Connection;
import io.nats.client.ErrorListener;
import io.nats.client.Nats;
import io.nats.client.Options;
import net.onelitefeather.blackhole.backend.support.NatsTestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The NATS adapters against a real server. Every test starts its own server, so no test sees another's
 * stream or consumer. Waiting is always a bounded poll, latch or future, never a sleep.
 */
@Tag("integration")
class NatsEventBusIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private final JsonMapper mapper = JsonMapper.createDefault();
    private final List<AutoCloseable> toClose = new ArrayList<>();
    private GenericContainer<?> server;

    @BeforeEach
    void startServer() {
        NatsTestServer.assumeDocker();
        this.server = NatsTestServer.open();
        this.server.start();
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

    /** One backend instance: its own connection, provisioner, transport and subscription factory. */
    private final class Instance {
        final NatsConnectionProvider connections;
        final StreamProvisioner provisioner;
        final NatsEventTransport transport;
        final NatsEventSubscriptionFactory subscriptions;

        Instance(String url) {
            NatsSettings settings = new NatsSettings(url, "", "test-" + UUID.randomUUID(), Duration.ofSeconds(5), 1,
                    Duration.ofDays(7), 100L * 1024 * 1024);
            this.connections = new NatsConnectionProvider(settings);
            this.provisioner = new StreamProvisioner(this.connections, settings);
            this.transport = new NatsEventTransport(this.connections, NatsEventBusIntegrationTest.this.mapper, this.provisioner);
            this.subscriptions = new NatsEventSubscriptionFactory(this.connections, this.provisioner,
                    NatsEventBusIntegrationTest.this.mapper);
            NatsEventBusIntegrationTest.this.toClose.add(this.connections::close);
        }

        /** Creates the stream and the durable, so a message published next is not skipped (deliver new). */
        void prepare() {
            assertTrue(this.provisioner.ensureStream(), "stream");
            this.provisioner.ensureConsumer(EventConsumers.REDIS_SYNC);
        }

        void publish(DomainEvent event) throws Exception {
            this.transport.publish(event.eventType(), event).toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private Instance instance() {
        return new Instance(NatsTestServer.url(this.server));
    }

    private static DomainEvent event(String type) {
        return new DomainEvent(UUID.randomUUID().toString(), type, 1L, 1, Map.of("owner", "abc"));
    }

    @Test
    void aPublishedEventReachesTheRedisSyncHandler() throws Exception {
        Instance backend = instance();
        backend.prepare();
        BlockingQueue<DomainEvent> received = new LinkedBlockingQueue<>();
        this.toClose.add(backend.subscriptions.subscribe(EventConsumers.REDIS_SYNC, received::add)::close);
        DomainEvent sent = event("punishment.created");

        backend.publish(sent);

        assertEquals(sent, received.poll(WAIT.toSeconds(), TimeUnit.SECONDS));
    }

    @Test
    void anEventTypeTheConsumerDoesNotFilterOnIsNotDelivered() throws Exception {
        Instance backend = instance();
        backend.prepare();
        BlockingQueue<DomainEvent> received = new LinkedBlockingQueue<>();
        this.toClose.add(backend.subscriptions.subscribe(EventConsumers.REDIS_SYNC, received::add)::close);

        backend.publish(event("report.created"));
        DomainEvent wanted = event("appeal.resolved");
        backend.publish(wanted);

        assertEquals(wanted, received.poll(WAIT.toSeconds(), TimeUnit.SECONDS),
                "the first delivered event is the appeal, the report was filtered out");
    }

    @Test
    void twoInstancesOnOneDurableShareTheWorkAndEachEventIsHandledOnce() throws Exception {
        Instance first = instance();
        Instance second = instance();
        first.prepare();
        int total = 20;
        CountDownLatch handled = new CountDownLatch(total);
        Map<String, Integer> perEvent = new ConcurrentHashMap<>();
        EventHandler count = event -> {
            perEvent.merge(event.eventId(), 1, Integer::sum);
            handled.countDown();
        };
        this.toClose.add(first.subscriptions.subscribe(EventConsumers.REDIS_SYNC, count)::close);
        this.toClose.add(second.subscriptions.subscribe(EventConsumers.REDIS_SYNC, count)::close);

        for (int i = 0; i < total; i++) {
            first.publish(event("punishment.updated"));
        }

        assertTrue(handled.await(WAIT.toSeconds(), TimeUnit.SECONDS), "all events handled, missing " + handled.getCount());
        assertEquals(total, perEvent.size());
        assertTrue(perEvent.values().stream().allMatch(times -> times == 1), "each event once, got " + perEvent);
    }

    @Test
    void everyEphemeralSubscriberReceivesEveryEventNotOnlyOne() throws Exception {
        Instance first = instance();
        Instance second = instance();
        assertTrue(first.provisioner.ensureStream(), "stream");
        BlockingQueue<DomainEvent> onFirst = new LinkedBlockingQueue<>();
        BlockingQueue<DomainEvent> onSecond = new LinkedBlockingQueue<>();
        List<String> types = List.of("punishment.updated");
        this.toClose.add(first.subscriptions.subscribeEphemeral(types, onFirst::add)::close);
        this.toClose.add(second.subscriptions.subscribeEphemeral(types, onSecond::add)::close);

        // An ephemeral consumer is created on its own thread, and an event published before it exists is not delivered
        // (deliver new). So publish until both have seen one, then check that a marked event reaches both.
        boolean firstSeen = false;
        boolean secondSeen = false;
        for (int attempt = 0; attempt < 100 && !(firstSeen && secondSeen); attempt++) {
            first.publish(event("punishment.updated"));
            firstSeen |= onFirst.poll(100, TimeUnit.MILLISECONDS) != null;
            secondSeen |= onSecond.poll(100, TimeUnit.MILLISECONDS) != null;
        }
        assertTrue(firstSeen && secondSeen, "both ephemeral subscribers started and received events");

        DomainEvent sent = event("punishment.updated");
        first.publish(sent);

        assertTrue(awaitEvent(onFirst, sent), "the first subscriber received the marked event");
        assertTrue(awaitEvent(onSecond, sent), "the second subscriber received it too: a broadcast, not a work queue");
    }

    private static boolean awaitEvent(BlockingQueue<DomainEvent> queue, DomainEvent wanted) throws InterruptedException {
        for (DomainEvent got = queue.poll(WAIT.toSeconds(), TimeUnit.SECONDS); got != null; got = queue.poll(WAIT.toSeconds(), TimeUnit.SECONDS)) {
            if (got.equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aRepeatedEventIdIsStoredOnce() throws Exception {
        Instance backend = instance();
        backend.prepare();
        DomainEvent sent = event("punishment.created");

        backend.publish(sent);
        backend.publish(sent);

        long stored = backend.connections.connection().jetStreamManagement().getStreamInfo(NatsStreams.STREAM)
                .getStreamState().getMsgCount();
        assertEquals(1, stored, "the second publish is a duplicate inside the window");
    }

    @Test
    void aConsumerStartedAfterARestartStillGetsTheEventsStoredMeanwhile() throws Exception {
        Instance before = instance();
        before.prepare();
        before.subscriptions.subscribe(EventConsumers.REDIS_SYNC, event -> { }).close();
        DomainEvent sent = event("punishment.revoked");
        before.publish(sent);

        Instance afterRestart = instance();
        BlockingQueue<DomainEvent> received = new LinkedBlockingQueue<>();
        this.toClose.add(afterRestart.subscriptions.subscribe(EventConsumers.REDIS_SYNC, received::add)::close);

        assertEquals(sent, received.poll(WAIT.toSeconds(), TimeUnit.SECONDS));
    }

    @Test
    void aFailingHandlerStillAcknowledgesSoTheEventIsNotDeliveredAgain() throws Exception {
        Instance backend = instance();
        backend.prepare();
        CountDownLatch handled = new CountDownLatch(1);
        this.toClose.add(backend.subscriptions.subscribe(EventConsumers.REDIS_SYNC, event -> {
            handled.countDown();
            throw new IllegalStateException("redis down");
        })::close);

        backend.publish(event("punishment.created"));

        assertTrue(handled.await(WAIT.toSeconds(), TimeUnit.SECONDS));
        var consumer = backend.connections.connection().jetStreamManagement()
                .getConsumerInfo(NatsStreams.STREAM, EventConsumers.REDIS_SYNC.name());
        awaitNoPending(backend, consumer.getName());
    }

    private void awaitNoPending(Instance backend, String consumerName) throws Exception {
        // The ack is sent right after the handler returns: poll the server's view a bounded number of times.
        for (int attempt = 0; attempt < 100; attempt++) {
            var info = backend.connections.connection().jetStreamManagement().getConsumerInfo(NatsStreams.STREAM, consumerName);
            if (info.getNumAckPending() == 0 && info.getAckFloor().getStreamSequence() >= 1) {
                return;
            }
            backend.connections.connection().flush(Duration.ofMillis(100));
        }
        throw new AssertionError("the message was never acknowledged");
    }

    @Test
    void theBackendWorksWithExactlyThePermissionsOfItsNatsUserAndNothingMore() throws Exception {
        this.server.stop();
        this.server = NatsTestServer.openWithConfig(BACKEND_ONLY_CONFIG);
        this.server.start();
        String host = this.server.getHost() + ":" + this.server.getMappedPort(4222);
        Instance backend = new Instance("nats://backend:secret@" + host);
        backend.prepare();
        BlockingQueue<DomainEvent> received = new LinkedBlockingQueue<>();
        this.toClose.add(backend.subscriptions.subscribe(EventConsumers.REDIS_SYNC, received::add)::close);
        DomainEvent sent = event("punishment.created");

        backend.publish(sent);

        assertEquals(sent, received.poll(WAIT.toSeconds(), TimeUnit.SECONDS),
                "stream and consumer management, publish, pull and ack are all allowed");
        CompletableFuture<String> violation = new CompletableFuture<>();
        Options options = new Options.Builder().server("nats://backend:secret@" + host)
                .errorListener(new ErrorListener() {
                    @Override
                    public void errorOccurred(Connection conn, String error) {
                        violation.complete(error);
                    }
                }).build();
        try (Connection connection = Nats.connect(options)) {
            connection.publish("some.other.subject", "x".getBytes());
            String error = violation.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertNotNull(error);
            assertTrue(error.toLowerCase().contains("permissions violation"), "got " + error);
        }
    }

    /** The publish and subscribe permissions of the user blackhole-backend in design D6. */
    private static final String BACKEND_ONLY_CONFIG = """
            authorization {
              users = [
                { user: backend, password: secret,
                  permissions: {
                    publish: { allow: [
                      "blackhole.events.>",
                      "$JS.API.STREAM.CREATE.BLACKHOLE_EVENTS",
                      "$JS.API.STREAM.UPDATE.BLACKHOLE_EVENTS",
                      "$JS.API.STREAM.INFO.BLACKHOLE_EVENTS",
                      "$JS.API.CONSUMER.CREATE.BLACKHOLE_EVENTS.blackhole-redis-sync",
                      "$JS.API.CONSUMER.CREATE.BLACKHOLE_EVENTS.blackhole-redis-sync.>",
                      "$JS.API.CONSUMER.DURABLE.CREATE.BLACKHOLE_EVENTS.blackhole-redis-sync",
                      "$JS.API.CONSUMER.INFO.BLACKHOLE_EVENTS.blackhole-redis-sync",
                      "$JS.API.CONSUMER.MSG.NEXT.BLACKHOLE_EVENTS.blackhole-redis-sync",
                      "$JS.ACK.BLACKHOLE_EVENTS.blackhole-redis-sync.>"
                    ] }
                    subscribe: { allow: ["_INBOX.>"] }
                  } }
              ]
            }
            """;
}
