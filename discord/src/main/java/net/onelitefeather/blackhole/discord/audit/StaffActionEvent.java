package net.onelitefeather.blackhole.discord.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Optional;

/**
 * The part of a {@code blackhole.events.staff.action} event the channel shows. The envelope is
 * {@code {eventId, eventType, occurredAt, payloadVersion, payload}}; the payload holds the audit fields. There is no
 * free text in it (no reason), no Ban ID, no IP and no raw UUID besides the audit id and the staff oid.
 */
public record StaffActionEvent(
        @NotNull String auditId,
        long occurredAt,
        @NotNull String surface,
        @NotNull String action,
        @Nullable String actorName,
        @NotNull String actorOid,
        @NotNull String targetKind,
        @Nullable String targetRef,
        @Nullable String targetName,
        @Nullable String panelPath
) {

    /** The event type as the backend publishes it; the subject is {@code blackhole.events.staff.action}. */
    public static final String EVENT_TYPE = "staff.action";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param body the message body as the backend published it
     * @return the event, or empty when it is malformed or not a staff action
     */
    public static @NotNull Optional<StaffActionEvent> parse(byte[] body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject() || !EVENT_TYPE.equals(root.path("eventType").asText(""))) {
            return Optional.empty();
        }
        JsonNode payload = root.path("payload");
        String auditId = text(payload, "auditId");
        String action = text(payload, "action");
        String surface = text(payload, "surface");
        String targetKind = text(payload, "targetKind");
        if (auditId == null || action == null || surface == null || targetKind == null) {
            return Optional.empty();
        }
        return Optional.of(new StaffActionEvent(auditId, payload.path("occurredAt").asLong(0), surface, action,
                text(payload, "actorName"), text(payload, "actorOid", ""), targetKind,
                text(payload, "targetRef"), text(payload, "targetName"), text(payload, "panelPath")));
    }

    private static String text(JsonNode node, String field) {
        return text(node, field, null);
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return fallback;
        }
        return value.asText();
    }
}
