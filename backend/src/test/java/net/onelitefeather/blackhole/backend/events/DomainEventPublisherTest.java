package net.onelitefeather.blackhole.backend.events;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:15:30Z");

    private final RecordingEventTransport transport = new RecordingEventTransport();
    private final DomainEventPublisher publisher =
            new DomainEventPublisher(this.transport, Clock.fixed(NOW, ZoneOffset.UTC));
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(DomainEventPublisher.class);

    @BeforeEach
    void captureLog() {
        this.log.start();
        this.logger.addAppender(this.log);
    }

    @AfterEach
    void releaseLog() {
        this.logger.detachAppender(this.log);
    }

    @Test
    void thePublishedEnvelopeCarriesTypePayloadVersionAndTheClockTime() {
        this.publisher.publish("profile.created", Map.of("owner", "abc"));

        assertEquals(1, this.transport.published().size());
        var published = this.transport.published().getFirst();
        assertEquals("profile.created", published.eventType());
        DomainEvent event = published.event();
        assertEquals("profile.created", event.eventType());
        assertEquals(NOW.toEpochMilli(), event.occurredAt());
        assertEquals(1, event.payloadVersion());
        assertEquals(Map.of("owner", "abc"), event.payload());
        assertDoesNotThrow(() -> UUID.fromString(event.eventId()), "the event id is a random UUID");
    }

    @Test
    void everyEventGetsItsOwnEventId() {
        this.publisher.publish("profile.created", Map.of());
        this.publisher.publish("profile.created", Map.of());

        assertNotEquals(this.transport.published().get(0).event().eventId(),
                this.transport.published().get(1).event().eventId(),
                "ids must differ so a dedupe never merges two events");
    }

    @Test
    void aTransportThatNeverCompletesDoesNotBlockTheCaller() {
        this.transport.respondWith(new CompletableFuture<>());

        assertDoesNotThrow(() -> this.publisher.publish("profile.created", Map.of()));
        assertEquals(1, this.transport.published().size());
    }

    @Test
    void anAsyncPublishFailureIsLoggedAndNotThrown() {
        this.transport.respondWith(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertDoesNotThrow(() -> this.publisher.publish("profile.created", Map.of()));

        assertTrue(errorLogged("profile.created"), "the failure must be logged, got " + this.log.list);
    }

    @Test
    void aSynchronousTransportFailureIsLoggedAndNotThrown() {
        this.transport.throwOnPublish(new IllegalStateException("no connection"));

        assertDoesNotThrow(() -> this.publisher.publish("profile.created", Map.of()));

        assertTrue(errorLogged("profile.created"), "the failure must be logged, got " + this.log.list);
    }

    private boolean errorLogged(String eventType) {
        return this.log.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains(eventType));
    }
}
