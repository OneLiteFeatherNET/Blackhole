package net.onelitefeather.blackhole.backend.ipban;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Announces IP ban changes. Payloads carry the ban id and the kind of change, never the address: events leave the
 * backend. A failed publish is logged with the id only; the change stands, and the firewall catches up on its next run.
 */
@Singleton
public class IpBanEvents {

    /** Event type of a new ban. */
    public static final String CREATED = "ipban.created";
    /** Event type of a lift. */
    public static final String LIFTED = "ipban.lifted";
    /** Event type of any change; the firewall reconciler and the proxies listen to this one. */
    public static final String CHANGED = "ipban.changed";

    private static final Logger LOGGER = LoggerFactory.getLogger(IpBanEvents.class);

    private final DomainEventPublisher events;

    public IpBanEvents(DomainEventPublisher events) {
        this.events = events;
    }

    /** Announces a created ban, followed by the change event. */
    public void created(UUID banId, long occurredAt) {
        publish(CREATED, banId, "CREATED", occurredAt);
        publish(CHANGED, banId, "CREATED", occurredAt);
    }

    /** Announces a lifted ban, followed by the change event. */
    public void lifted(UUID banId, long occurredAt) {
        publish(LIFTED, banId, "LIFTED", occurredAt);
        publish(CHANGED, banId, "LIFTED", occurredAt);
    }

    /** The payload of every IP ban event: the id, the kind of change and the time. Exposed for the no-address test. */
    static Map<String, Object> payload(String eventId, UUID banId, String change, long occurredAt) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", eventId);
        payload.put("banId", banId.toString());
        payload.put("change", change);
        payload.put("occurredAt", occurredAt);
        return payload;
    }

    private void publish(String eventType, UUID banId, String change, long occurredAt) {
        String eventId = UUID.randomUUID().toString();
        try {
            this.events.publish(eventType, eventId, payload(eventId, banId, change, occurredAt),
                    failure -> LOGGER.warn("Could not publish {} for IP ban {}", eventType, banId));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not publish {} for IP ban {}", eventType, banId);
        }
    }
}
