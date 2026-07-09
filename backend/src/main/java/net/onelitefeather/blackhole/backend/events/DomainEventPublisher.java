package net.onelitefeather.blackhole.backend.events;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

/**
 * Thin wrapper called as a side effect of existing write paths (see
 * {@code PunishmentEntityController}, {@code ProfileController},
 * {@code PunishmentExpirySweeper}) — additive instrumentation, not a rewrite of those flows.
 */
@Singleton
public class DomainEventPublisher {

    private static final int CURRENT_PAYLOAD_VERSION = 1;
    private static final Logger LOGGER = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final DomainEventRabbitPublisher rabbitPublisher;

    public DomainEventPublisher(DomainEventRabbitPublisher rabbitPublisher) {
        this.rabbitPublisher = rabbitPublisher;
    }

    /**
     * Publishes a domain event. The routing key is the event type itself (e.g.
     * {@code punishment.created}), so consumers can bind with wildcards (e.g. {@code punishment.*}).
     *
     * @param eventType the event type / routing key
     * @param payload   event-specific data — hashed owner/IDs only, never raw player UUIDs
     */
    public void publish(String eventType, Map<String, Object> payload) {
        DomainEvent event = new DomainEvent(
                UUID.randomUUID().toString(),
                eventType,
                System.currentTimeMillis(),
                CURRENT_PAYLOAD_VERSION,
                payload
        );
        try {
            this.rabbitPublisher.publish(eventType, event);
        } catch (RuntimeException e) {
            // The event bus is an additive side channel; a broker hiccup must never fail the
            // primary write path that triggered it.
            LOGGER.error("Failed to publish domain event {}: {}", eventType, e.getMessage());
        }
    }
}
