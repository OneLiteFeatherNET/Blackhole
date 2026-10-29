package net.onelitefeather.blackhole.backend.areaban;

import java.util.HashMap;
import java.util.Map;

/**
 * The payloads of the area ban events, as the Discord bot renders them. Each one is a whitelist: the area and its times
 * and source, and nothing else. No reason, no actor, no address, no owner hash and no player name.
 */
public final class AreaBanEvents {

    static final String CREATED_EVENT = "area_ban.created";
    static final String LIFTED_EVENT = "area_ban.lifted";

    private AreaBanEvents() {
    }

    public static Map<String, Object> created(AreaBan ban) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", ban.kind().name());
        payload.put("value", ban.value());
        payload.put("source", ban.source().name());
        payload.put("createdAt", ban.createdAt());
        if (ban.expiresAt() != null) {
            payload.put("expiresAt", ban.expiresAt());
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
