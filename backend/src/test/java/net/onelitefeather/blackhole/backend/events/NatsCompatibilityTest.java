package net.onelitefeather.blackhole.backend.events;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.NKey;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PublishOptions;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.PublishAck;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import net.onelitefeather.blackhole.backend.support.NatsTestServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compatibility gate of the NATS change (design D2, task 3.0), against a real server of the
 * feather-core version. It pins down what the event bus needs from the client library: stream
 * creation, {@code Nats-Msg-Id} dedupe, a named durable pull consumer with a filter and ack, and
 * reading a credentials file.
 *
 * <p>Result of the gate: the Micronaut BOM of this build manages {@code micronaut-nats} 4.9.0 (not
 * 5.0.0: the build runs Micronaut 4.10 from {@code gradle.properties}). Booting it next to the
 * application failed with a {@code NonUniqueBeanException} for the {@code consumer} executor
 * configuration, and its sources show a blocking {@code Nats.connect} with no reconnect-on-connect and
 * no connection name, which would stop the application when NATS is down. The decided fallback was
 * taken: plain {@code jnats} behind the {@link EventTransport} and {@link EventSubscriptionFactory}
 * ports. This test therefore exercises {@code jnats} itself.</p>
 *
 * <p>Every wait is a bounded {@code fetch} or a future, never a sleep.</p>
 */
@Tag("integration")
class NatsCompatibilityTest {

    private static final String STREAM = "COMPAT_EVENTS";
    private static final GenericContainer<?> NATS = NatsTestServer.open();

    @BeforeAll
    static void startServer() throws Exception {
        NatsTestServer.assumeDocker();
        NATS.start();
        try (Connection connection = Nats.connect(NatsTestServer.url(NATS))) {
            connection.jetStreamManagement().addStream(StreamConfiguration.builder()
                    .name(STREAM).subjects("compat.>").storageType(StorageType.File)
                    .duplicateWindow(Duration.ofMinutes(2)).build());
        }
    }

    @AfterAll
    static void stopServer() {
        if (NATS.isRunning()) {
            NATS.stop();
        }
    }

    @Test
    void aCreatedStreamKeepsItsSettings() throws Exception {
        try (Connection connection = Nats.connect(NatsTestServer.url(NATS))) {
            var configuration = connection.jetStreamManagement().getStreamInfo(STREAM).getConfiguration();

            assertEquals(List.of("compat.>"), configuration.getSubjects());
            assertEquals(StorageType.File, configuration.getStorageType());
        }
    }

    @Test
    void aRepeatedMessageIdIsStoredOnceAndPublishAsyncReportsTheDuplicate() throws Exception {
        String id = UUID.randomUUID().toString();
        try (Connection connection = Nats.connect(NatsTestServer.url(NATS))) {
            JetStream jetStream = connection.jetStream();
            PublishOptions options = PublishOptions.builder().stream(STREAM).messageId(id).build();

            PublishAck first = jetStream.publishAsync("compat.dedupe", "{}".getBytes(), options).get(10, TimeUnit.SECONDS);
            PublishAck second = jetStream.publishAsync("compat.dedupe", "{}".getBytes(), options).get(10, TimeUnit.SECONDS);

            assertFalse(first.isDuplicate(), "the first publish is stored");
            assertTrue(second.isDuplicate(), "the repeated Nats-Msg-Id must be reported as a duplicate");
            assertEquals(first.getSeqno(), second.getSeqno(), "and point at the stored message");
        }
    }

    @Test
    void aNamedDurablePullConsumerWithAFilterDeliversOnlyMatchingMessagesAndAcks() throws Exception {
        try (Connection connection = Nats.connect(NatsTestServer.url(NATS))) {
            JetStreamManagement management = connection.jetStreamManagement();
            management.addOrUpdateConsumer(STREAM, ConsumerConfiguration.builder()
                    .durable("compat-durable")
                    .filterSubjects(List.of("compat.pull.wanted"))
                    .ackPolicy(AckPolicy.Explicit)
                    .deliverPolicy(DeliverPolicy.New)
                    .build());
            JetStream jetStream = connection.jetStream();
            JetStreamSubscription subscription = jetStream.subscribe(null, PullSubscribeOptions.bind(STREAM, "compat-durable"));
            jetStream.publish("compat.pull.wanted", "a".getBytes());
            jetStream.publish("compat.pull.other", "b".getBytes());

            List<Message> messages = subscription.fetch(5, Duration.ofSeconds(5));
            for (Message message : messages) {
                message.ackSync(Duration.ofSeconds(5));
            }

            assertEquals(1, messages.size(), "only the filtered subject is delivered");
            assertEquals("compat.pull.wanted", messages.getFirst().getSubject());
            var info = management.getConsumerInfo(STREAM, "compat-durable");
            assertEquals(0, info.getNumAckPending(), "the message is acknowledged");
        }
    }

    @Test
    void readsACredentialsFileAndAuthenticatesWithItsKey(@TempDir Path directory) throws Exception {
        NKey user = NKey.createUser(null);
        String config = "authorization { users = [ { nkey: " + new String(user.getPublicKey()) + " } ] }\n";
        Path creds = directory.resolve("user.creds");
        Files.writeString(creds, """
                -----BEGIN NATS USER JWT-----
                eyJ0eXAiOiJKV1QiLCJhbGciOiJlZDI1NTE5LW5rZXkifQ.e30.c2ln
                ------END NATS USER JWT------

                ************************* IMPORTANT *************************
                NKEY Seed printed below can be used to sign and prove identity.

                -----BEGIN USER NKEY SEED-----
                %s
                ------END USER NKEY SEED------

                *************************************************************
                """.formatted(new String(user.getSeed())));
        try (GenericContainer<?> secured = NatsTestServer.openWithConfig(config)) {
            secured.start();
            String url = NatsTestServer.url(secured);
            Options anonymous = new Options.Builder().server(url).maxReconnects(0)
                    .connectionTimeout(Duration.ofSeconds(3)).build();

            assertThrows(Exception.class, () -> Nats.connect(anonymous).close(), "anonymous must not connect");

            Options withCreds = new Options.Builder().server(url).credentialPath(creds.toString()).build();
            try (Connection connection = Nats.connect(withCreds)) {
                assertEquals(Connection.Status.CONNECTED, connection.getStatus());
            }
        }
    }
}
