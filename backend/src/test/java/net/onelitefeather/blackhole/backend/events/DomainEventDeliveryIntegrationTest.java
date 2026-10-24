package net.onelitefeather.blackhole.backend.events;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Subscription;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.support.NatsTestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain events raised from request handling must actually reach the broker: publishing runs on
 * the Netty event loop, where a blocking publish is refused and the event would be lost.
 */
@Tag("integration")
class DomainEventDeliveryIntegrationTest extends ApiIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private Connection connection;
    private Subscription subscription;
    private ListAppender<ILoggingEvent> publisherLog;
    private Logger publisherLogger;

    @BeforeEach
    void listenOnTheEventSubject() throws Exception {
        // Publishing is best effort: make sure the stream exists, then listen like any subscriber would.
        assertTrue(this.context.getBean(StreamProvisioner.class).ensureStream(), "the stream must exist");
        this.connection = Nats.connect(NatsTestServer.url(NATS));
        this.subscription = this.connection.subscribe("blackhole.events.profile.created");
        this.connection.flush(WAIT);

        this.publisherLogger = (Logger) LoggerFactory.getLogger(DomainEventPublisher.class);
        this.publisherLog = new ListAppender<>();
        this.publisherLog.start();
        this.publisherLogger.addAppender(this.publisherLog);
    }

    @AfterEach
    void stopListening() throws Exception {
        this.publisherLogger.detachAppender(this.publisherLog);
        this.connection.close();
    }

    @Test
    void creatingAProfileOverHttpDeliversProfileCreatedToTheBroker() throws Exception {
        String owner = "a".repeat(128);

        Reply reply = post("/profile", Map.of("owner", owner, "metaData", Map.of()));

        assertEquals(200, reply.status(), reply.body());
        Message message = this.subscription.nextMessage(WAIT);
        assertNotNull(message, "profile.created must arrive on blackhole.events.profile.created");
        String body = new String(message.getData(), StandardCharsets.UTF_8);
        assertTrue(body.contains(owner), "event carries the owner, got " + body);
        assertEquals(eventIdOf(body), message.getHeaders().getFirst("Nats-Msg-Id"),
                "the dedupe id is the event id");
    }

    @Test
    void publishingFromTheEventLoopLogsNoFailure() throws Exception {
        post("/profile", Map.of("owner", "b".repeat(128), "metaData", Map.of()));
        assertNotNull(this.subscription.nextMessage(WAIT), "event arrived");

        long errors = this.publisherLog.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.ERROR)).count();
        assertEquals(0, errors, "no publish failure logged, got " + this.publisherLog.list);
    }

    private static String eventIdOf(String body) {
        int start = body.indexOf("\"eventId\":\"") + "\"eventId\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }
}
