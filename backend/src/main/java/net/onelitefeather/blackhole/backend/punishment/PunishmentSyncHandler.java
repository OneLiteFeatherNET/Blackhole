package net.onelitefeather.blackhole.backend.punishment;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Translates punishment domain events into mirror updates: a created or updated punishment sets the
 * state of its section, an expired, revoked or appeal-lifted one clears it. Keyed by the event's
 * section, so a mute event never touches a ban key.
 */
@Singleton
public class PunishmentSyncHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PunishmentSyncHandler.class);

    private final PunishmentMirror mirror;

    public PunishmentSyncHandler(PunishmentMirror mirror) {
        this.mirror = mirror;
    }

    public void handle(DomainEvent event) {
        Map<String, Object> payload = event.payload();
        String owner = stringOrNull(payload.get("owner"));
        String section = stringOrNull(payload.get("section"));
        if (owner == null || section == null) {
            LOGGER.warn("Ignoring {} event without owner or section", event.eventType());
            return;
        }
        String enforcement = stringOrNull(payload.get("enforcement"));
        String punishmentIdentifier = stringOrNull(payload.get("punishmentIdentifier"));

        switch (event.eventType()) {
            case "punishment.created", "punishment.updated" -> this.mirror.set(
                    section, enforcement, owner, punishmentIdentifier,
                    stringOrNull(payload.get("templateIdentifier")), stringOrNull(payload.get("reason")), asLong(payload.get("expiresAt")),
                    Boolean.TRUE.equals(payload.get("automatic"))
            );
            case "punishment.expired", "punishment.revoked" -> this.mirror.clear(section, enforcement, owner, punishmentIdentifier);
            case "appeal.resolved" -> {
                if ("GRANTED_FULL_LIFT".equals(String.valueOf(payload.get("decision")))) {
                    this.mirror.clear(section, enforcement, owner, punishmentIdentifier);
                }
            }
            default -> LOGGER.debug("Ignoring unrelated event type {} on the Redis sync queue", event.eventType());
        }
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
