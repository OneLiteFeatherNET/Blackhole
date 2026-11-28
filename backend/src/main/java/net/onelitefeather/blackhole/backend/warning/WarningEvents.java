package net.onelitefeather.blackhole.backend.warning;

import java.util.HashMap;
import java.util.Map;

/**
 * Payloads of the warning domain events. They carry what a proxy needs to find the warning and nothing else: no reason
 * text (the proxy reads it from {@code GET /warn/{id}}), no staff name, no IP address and no raw player UUID. The owner
 * is the hash the backend already publishes everywhere.
 */
public final class WarningEvents {

    /** Event type of a warning; the subject is {@code blackhole.events.punishment.warned}. */
    public static final String WARNED = "punishment.warned";

    private WarningEvents() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** Payload of {@link #WARNED}. The acknowledgement is not published: the proxy answers it over HTTP. */
    public static Map<String, Object> warned(String eventId, String warningId, String owner, long occurredAt) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", eventId);
        payload.put("warningId", warningId);
        payload.put("owner", owner);
        payload.put("occurredAt", occurredAt);
        return payload;
    }
}
