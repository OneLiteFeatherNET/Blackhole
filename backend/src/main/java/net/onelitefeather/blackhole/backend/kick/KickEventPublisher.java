package net.onelitefeather.blackhole.backend.kick;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

/**
 * Tells the proxies about a kick, after the kick was written. A failed publish never fails the request
 * (the kick exists; its status becomes {@code NOT_ONLINE} at the deadline, honestly: no proxy was
 * told). It is counted in {@value #FAILURE_METER} and logged with the kick's id only, never the reason.
 */
@Singleton
public class KickEventPublisher {

    /** Counts kick events that could not be published. */
    public static final String FAILURE_METER = "blackhole.kick.publish.failures";

    private static final Logger LOGGER = LoggerFactory.getLogger(KickEventPublisher.class);

    private final DomainEventPublisher events;
    private final Counter failures;

    public KickEventPublisher(DomainEventPublisher events, MeterRegistry meters) {
        this.events = events;
        this.failures = Counter.builder(FAILURE_METER).description("Kick events that could not be published").register(meters);
    }

    /** Announces a kick of one player. */
    public void kicked(UUID kickId, String owner, KickScope scope, @Nullable String target, long occurredAt) {
        String eventId = UUID.randomUUID().toString();
        publish(KickEvents.KICK, eventId, kickId, KickEvents.kick(eventId, kickId, owner, scope, target, occurredAt));
    }

    /** Announces a mass kick. */
    public void massKicked(UUID actionId, KickScope scope, String target, long occurredAt) {
        String eventId = UUID.randomUUID().toString();
        publish(KickEvents.MASS, eventId, actionId, KickEvents.mass(eventId, actionId, scope, target, occurredAt));
    }

    private void publish(String eventType, String eventId, UUID kickId, Map<String, Object> payload) {
        try {
            this.events.publish(eventType, eventId, payload, failure -> failed(eventType, kickId));
        } catch (RuntimeException e) {
            failed(eventType, kickId);
        }
    }

    private void failed(String eventType, UUID kickId) {
        this.failures.increment();
        LOGGER.warn("Could not publish the {} event of kick {}; its status will read NOT_ONLINE or NO_PROXY_ANSWER at the deadline", eventType, kickId);
    }
}
