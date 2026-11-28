package net.onelitefeather.blackhole.velocity.guard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Why the backend refused a guard or area ban change, read from the refusal's body. The staff refusals carry a
 * stable code at the start of the message; the others carry the error kind. Anything else is {@link #OTHER}.
 */
public enum GuardRefusal {
    /** The staff member's role snapshot is older than the backend accepts; they must sign in to the panel. */
    STAFF_ROLE_STALE,
    /** The staff member has no live staff link, or holds neither the moderator nor the admin role. */
    STAFF_NOT_ELIGIBLE,
    /** A moderator asked for a country ban. */
    FORBIDDEN_KIND,
    /** A moderator asked for a permanent ban, or one longer than the limit. */
    FORBIDDEN_DURATION,
    /** The request conflicts with the current state, such as an area that is already banned. */
    CONFLICT,
    /** The request is malformed, such as an invalid value or reason. */
    VALIDATION,
    /** Any other refusal, or no readable answer at all. */
    OTHER;

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardRefusal.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param refusal the failure the backend call raised
     * @return the reason the backend gave, or {@link #OTHER} when it gave none this proxy knows
     */
    public static @NotNull GuardRefusal of(@NotNull ApiException refusal) {
        JsonNode body = parse(refusal.getResponseBody());
        String message = text(body, "errorMessage");
        if (message.startsWith(STAFF_ROLE_STALE.name() + ":")) {
            return STAFF_ROLE_STALE;
        }
        if (message.startsWith(STAFF_NOT_ELIGIBLE.name() + ":")) {
            return STAFF_NOT_ELIGIBLE;
        }
        return switch (text(body, "kind")) {
            case "FORBIDDEN_KIND" -> FORBIDDEN_KIND;
            case "FORBIDDEN_DURATION" -> FORBIDDEN_DURATION;
            case "CONFLICT" -> CONFLICT;
            case "VALIDATION" -> VALIDATION;
            default -> byStatus(refusal.getCode());
        };
    }

    private static GuardRefusal byStatus(int status) {
        return switch (status) {
            case 400 -> VALIDATION;
            case 409 -> CONFLICT;
            default -> OTHER;
        };
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return JSON.missingNode();
        }
        try {
            return JSON.readTree(body);
        } catch (JsonProcessingException e) {
            LOGGER.debug("A refusal body is not JSON, judging it by its status: {}", e.getOriginalMessage());
            return JSON.missingNode();
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || !node.isTextual() ? "" : node.asText();
    }
}
