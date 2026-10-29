package net.onelitefeather.blackhole.discord.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * A join guard or area ban event as the backend publishes it. The payload is read only through the keys
 * {@link GuardMessageRenderer} names, so a field the backend never meant to publish cannot reach Discord.
 *
 * @param eventId    the unique id of the event, which also marks its Discord message
 * @param eventType  one of {@link #TYPES}
 * @param occurredAt when the backend published it, epoch milliseconds
 * @param payload    the event payload as published
 */
public record GuardEvent(@NotNull String eventId, @NotNull String eventType, long occurredAt, @NotNull JsonNode payload) {

    /** The guard event types the team channel receives. */
    public static final Set<String> TYPES = Set.of("guard.level_changed", "area_ban.created", "area_ban.lifted", "guard.denials.summary");

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param body the message body as published by the backend
     * @return the event, or empty when the body is malformed or not one of the guard event types
     */
    public static @NotNull Optional<GuardEvent> parse(byte[] body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        String eventId = root.path("eventId").asText("");
        String type = root.path("eventType").asText("");
        JsonNode payload = root.path("payload");
        if (eventId.isEmpty() || !TYPES.contains(type) || !payload.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new GuardEvent(eventId, type, root.path("occurredAt").asLong(0), payload));
    }
}
