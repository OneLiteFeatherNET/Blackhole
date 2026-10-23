package net.onelitefeather.blackhole.discord.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.NotificationKind;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Optional;

/**
 * The part of a backend domain event the bot acts on: which report or appeal changed. The backend
 * envelope is {@code {eventId, eventType, occurredAt, payloadVersion, payload}}; the payload holds only
 * identifiers and hashes, so the bot reads the display facts from the backend afterwards.
 *
 * @param eventId    the unique id of the event, for idempotency
 * @param eventType  {@code report.created}, {@code report.resolved}, {@code appeal.submitted} or {@code appeal.resolved}
 * @param kind       report or appeal
 * @param identifier the report or appeal
 */
public record NotificationEvent(
        @NotNull String eventId, @NotNull String eventType, @NotNull NotificationKind kind, @NotNull String identifier
) {

    /** The routing keys the bot subscribes to. */
    public static final String[] ROUTING_KEYS = {"report.created", "report.resolved", "appeal.submitted", "appeal.resolved"};

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param body the message body as published by the backend
     * @return the event, or empty when it is malformed or not one the bot handles
     */
    public static @NotNull Optional<NotificationEvent> parse(byte[] body) {
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
        String reportId = payload.path("reportIdentifier").asText("");
        String appealId = payload.path("appealIdentifier").asText("");
        if (eventId.isEmpty()) {
            return Optional.empty();
        }
        return switch (type) {
            case "report.created", "report.resolved" -> reportId.isEmpty() ? Optional.empty()
                    : Optional.of(new NotificationEvent(eventId, type, NotificationKind.REPORT, reportId));
            case "appeal.submitted", "appeal.resolved" -> appealId.isEmpty() ? Optional.empty()
                    : Optional.of(new NotificationEvent(eventId, type, NotificationKind.APPEAL, appealId));
            default -> Optional.empty();
        };
    }
}
