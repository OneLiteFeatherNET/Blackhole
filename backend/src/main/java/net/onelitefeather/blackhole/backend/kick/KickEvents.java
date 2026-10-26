package net.onelitefeather.blackhole.backend.kick;

import io.micronaut.core.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Payloads of the kick domain events. They carry what a proxy needs to act and nothing else: no reason
 * text (the proxy reads it from {@code GET /kick/{id}}), no staff name, no IP address and no raw
 * player UUID. The owner is the hash the backend already publishes everywhere.
 */
public final class KickEvents {

    /** Event type of a kick of one player; the subject is {@code blackhole.events.punishment.kick}. */
    public static final String KICK = "punishment.kick";

    /** Event type of a mass kick; the subject is {@code blackhole.events.kick.mass}. */
    public static final String MASS = "kick.mass";

    private KickEvents() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** Payload of {@link #KICK}. {@code target} is left out for a network kick. */
    public static Map<String, Object> kick(String eventId, UUID kickId, String owner, KickScope scope, @Nullable String target, long occurredAt) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", eventId);
        payload.put("kickId", kickId.toString());
        payload.put("owner", owner);
        payload.put("scope", scope.name());
        if (target != null) {
            payload.put("target", target);
        }
        payload.put("occurredAt", occurredAt);
        return payload;
    }

    /** Payload of {@link #MASS}. */
    public static Map<String, Object> mass(String eventId, UUID actionId, KickScope scope, String target, long occurredAt) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", eventId);
        payload.put("actionId", actionId.toString());
        payload.put("scope", scope.name());
        payload.put("target", target);
        payload.put("occurredAt", occurredAt);
        return payload;
    }
}
