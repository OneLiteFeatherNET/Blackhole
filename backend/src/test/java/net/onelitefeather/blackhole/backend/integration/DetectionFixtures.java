package net.onelitefeather.blackhole.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onelitefeather.blackhole.backend.support.TestClock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds valid detections and batches; a test overrides only the field it is about. */
final class DetectionFixtures {

    static final String CLIENT = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b";
    static final String SOURCE = "vulcan";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DetectionFixtures() {
    }

    /** The registration of the test integration: DETECTION version 1, tasks Lobby, static server Creative. */
    static IntegrationSpec vulcan(boolean enabled) {
        return new IntegrationSpec(SOURCE, "Vulcan bridge", CLIENT, enabled, List.of("DETECTION:1"), List.of("Lobby"),
                List.of("Creative"));
    }

    /** A valid item for {@code Lobby-2}, occurred one minute before the test clock. */
    static Map<String, Object> item() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("check", "KillAura");
        payload.put("violationLevel", 14);
        payload.put("ping", 48);
        payload.put("tps", 19.8);
        payload.put("experimental", false);
        payload.put("detail", "Attack angle delta above threshold");

        Map<String, Object> item = new HashMap<>();
        item.put("detectionId", UUID.randomUUID().toString());
        item.put("kind", "DETECTION");
        item.put("schemaVersion", 1);
        item.put("occurredAt", occurredAt(TestClock.START.minusSeconds(60)));
        item.put("server", "Lobby-2");
        item.put("owner", UUID.randomUUID().toString());
        item.put("summary", "KillAura flag");
        item.put("payload", payload);
        return item;
    }

    /** The item with one field replaced; a null value removes the field. */
    static Map<String, Object> with(Map<String, Object> item, String field, Object value) {
        Map<String, Object> copy = new HashMap<>(item);
        if (value == null) {
            copy.remove(field);
        } else {
            copy.put(field, value);
        }
        return copy;
    }

    /** The item with one payload field replaced; a null value removes the field. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withPayload(Map<String, Object> item, String field, Object value) {
        Map<String, Object> payload = new HashMap<>((Map<String, Object>) item.get("payload"));
        if (value == null) {
            payload.remove(field);
        } else {
            payload.put(field, value);
        }
        return with(item, "payload", payload);
    }

    static String occurredAt(java.time.Instant instant) {
        return instant.toString();
    }

    /** The batch body {@code {"detections": [...]}} as sent on the wire. */
    static String batch(List<Map<String, Object>> items) {
        try {
            return MAPPER.writeValueAsString(Map.of("detections", items));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A JSON value of an item, parsed the way the service reads it. */
    static JsonNode node(Map<String, Object> item) {
        return MAPPER.valueToTree(item);
    }
}
