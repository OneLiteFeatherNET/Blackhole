package net.onelitefeather.blackhole.backend.events;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Thin wrapper called as a side effect of existing write paths (see
 * {@code PunishmentEntityController}, {@code ProfileController},
 * {@code PunishmentExpirySweeper}) — additive instrumentation, not a rewrite of those flows.
 */
@Singleton
public class DomainEventPublisher {

    private static final int CURRENT_PAYLOAD_VERSION = 1;
    private static final Logger LOGGER = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final EventTransport transport;
    private final Clock clock;

    public DomainEventPublisher(EventTransport transport, Clock clock) {
        this.transport = transport;
        this.clock = clock;
    }

    /**
     * Publishes a domain event. The event type is the routing identity (e.g.
     * {@code punishment.created}), so consumers can filter by type or by a prefix of it.
     *
     * @param eventType the event type
     * @param payload   event-specific data — hashed owner/IDs only, never raw player UUIDs
     */
    public void publish(String eventType, Map<String, Object> payload) {
        publish(eventType, UUID.randomUUID().toString(), payload, failure -> { });
    }

    /**
     * Publishes a domain event under an id the caller already put into the payload, and tells the caller
     * when the publish failed (synchronously or when the broker did not accept it), so it can count the
     * failure. The failure is still logged here and never thrown.
     *
     * @param eventId   the event id, which is also the broker's message id for de-duplication
     * @param onFailure told about a failed publish; must not throw
     */
    public void publish(String eventType, String eventId, Map<String, Object> payload, Consumer<Throwable> onFailure) {
        DomainEvent event = new DomainEvent(
                eventId,
                eventType,
                this.clock.millis(),
                CURRENT_PAYLOAD_VERSION,
                payload
        );
        try {
            // Fire and forget: the transport never blocks the calling thread (which may be a Netty
            // event-loop thread); failures surface asynchronously through the stage.
            this.transport.publish(eventType, event).whenComplete((ignored, e) -> {
                if (e != null) {
                    LOGGER.error("Failed to publish domain event {}: {}", eventType, e.getMessage());
                    onFailure.accept(e);
                }
            });
        } catch (RuntimeException e) {
            // The event bus is an additive side channel; a broker hiccup must never fail the
            // primary write path that triggered it.
            LOGGER.error("Failed to publish domain event {}: {}", eventType, e.getMessage());
            onFailure.accept(e);
        }
    }
}
