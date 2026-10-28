package net.onelitefeather.blackhole.backend.warning;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

/**
 * Tells the proxies about a warning, after the warning was written. A failed publish never fails the request: the
 * warning stands and stays pending until the player acknowledges it, and the proxy shows it on the next login. It is
 * counted in {@value #FAILURE_METER} and logged with the warning's id only, never the reason.
 */
@Singleton
public class WarningEventPublisher {

    /** Counts warning events that could not be published. */
    public static final String FAILURE_METER = "blackhole.warning.publish.failures";

    private static final Logger LOGGER = LoggerFactory.getLogger(WarningEventPublisher.class);

    private final DomainEventPublisher events;
    private final Counter failures;

    public WarningEventPublisher(DomainEventPublisher events, MeterRegistry meters) {
        this.events = events;
        this.failures = Counter.builder(FAILURE_METER).description("Warning events that could not be published").register(meters);
    }

    /** Announces a warning of one player. */
    public void warned(String warningId, String owner, long occurredAt) {
        String eventId = UUID.randomUUID().toString();
        publish(eventId, warningId, WarningEvents.warned(eventId, warningId, owner, occurredAt));
    }

    private void publish(String eventId, String warningId, Map<String, Object> payload) {
        try {
            this.events.publish(WarningEvents.WARNED, eventId, payload, failure -> failed(warningId));
        } catch (RuntimeException e) {
            failed(warningId);
        }
    }

    private void failed(String warningId) {
        this.failures.increment();
        LOGGER.warn("Could not publish the {} event of warning {}; the warning stays pending until the player acknowledges it", WarningEvents.WARNED, warningId);
    }
}
