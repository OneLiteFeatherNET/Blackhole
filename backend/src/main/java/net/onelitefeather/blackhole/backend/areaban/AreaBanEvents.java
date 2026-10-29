package net.onelitefeather.blackhole.backend.areaban;

import io.micronaut.core.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The payloads of the area ban events, as the Discord bot renders them. Each one is a whitelist: the area, its times, its
 * source and its reason, and for a created ban the display name of the staff member it is for. No address, no owner hash
 * and no player name.
 */
public final class AreaBanEvents {

    static final String CREATED_EVENT = "area_ban.created";
    static final String LIFTED_EVENT = "area_ban.lifted";

    private AreaBanEvents() {
    }

    /** @param actorName the display name of the staff member the ban is for, or null for an automatic ban */
    public static Map<String, Object> created(AreaBan ban, @Nullable String actorName) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", ban.kind().name());
        payload.put("value", ban.value());
        payload.put("reason", ban.reason());
        payload.put("source", ban.source().name());
        payload.put("createdAt", ban.createdAt());
        if (ban.expiresAt() != null) {
            payload.put("expiresAt", ban.expiresAt());
        }
        if (actorName != null) {
            payload.put("actorName", actorName);
        }
        return payload;
    }

    public static Map<String, Object> lifted(AreaBan ban) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", ban.kind().name());
        payload.put("value", ban.value());
        payload.put("liftedAt", ban.liftedAt());
        return payload;
    }
}
