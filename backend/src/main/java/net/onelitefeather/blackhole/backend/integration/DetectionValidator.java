package net.onelitefeather.blackhole.backend.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evidence.validation.PiiGuard;
import net.onelitefeather.blackhole.backend.integration.dto.DetectionReason;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Checks one detection of a batch against the envelope table and the payload rules of the design (decisions 2 and 5).
 * Pure: no store, no network; the clock is the caller's {@code now}. The first rule that fails decides the reason.
 */
@Singleton
public class DetectionValidator {

    /** The payload serialised as JSON may be at most this many bytes. */
    static final int MAX_PAYLOAD_BYTES = 4096;
    /** Containers may nest at most this deep: the payload object is level 1. */
    static final int MAX_DEPTH = 3;
    /** occurredAt may lie this far before the server clock. */
    static final Duration MAX_AGE = Duration.ofHours(24);
    /** occurredAt may lie this far after the server clock (a small skew between the game server and Blackhole). */
    static final Duration MAX_SKEW = Duration.ofMinutes(5);
    static final int SERVER_MAX = 64;
    static final int SUMMARY_MAX = 200;

    private static final Set<String> FIELDS = Set.of("detectionId", "kind", "schemaVersion", "occurredAt", "server", "owner",
            "severity", "summary", "payload", "sourceSystem");
    private static final Set<String> REQUIRED = Set.of("detectionId", "kind", "schemaVersion", "occurredAt", "server", "owner",
            "summary", "payload");
    private static final Set<String> SEVERITIES = Set.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern KIND = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SchemaRegistry schemas;

    public DetectionValidator(SchemaRegistry schemas) {
        this.schemas = schemas;
    }

    /** The outcome of one item: a valid detection, or the reason it is refused. */
    public sealed interface Verdict permits Verdict.Valid, Verdict.Rejected {

        /** The item passed every rule; the fields are typed and the owner is still the raw UUID (the service hashes it). */
        record Valid(Detection detection) implements Verdict {
        }

        record Rejected(DetectionReason reason) implements Verdict {
        }
    }

    /** A detection whose envelope and payload passed. {@code payload} is the validated object. */
    public record Detection(UUID detectionId, String kind, int schemaVersion, Instant occurredAt, String server, UUID owner,
                            String severity, String summary, JsonNode payload) {
    }

    /**
     * Validates one item of a batch.
     *
     * @param item the item as it arrived; anything but an object is refused
     * @param spec the caller's registration: its source system, kinds and server scope
     * @param now  the server clock, the only time source for the window
     */
    public Verdict validate(JsonNode item, IntegrationSpec spec, Instant now) {
        if (item == null || !item.isObject()) {
            return reject(DetectionReason.SCHEMA_INVALID);
        }
        Set<String> names = names(item);
        if (!FIELDS.containsAll(names) || !names.containsAll(REQUIRED)) {
            return reject(DetectionReason.SCHEMA_INVALID);
        }
        JsonNode source = item.get("sourceSystem");
        if (source != null && !source.isTextual()) {
            return reject(DetectionReason.SCHEMA_INVALID);
        }
        if (source != null && !source.asText().equals(spec.sourceSystem())) {
            return reject(DetectionReason.SOURCE_MISMATCH);
        }
        UUID detectionId = uuid(item.get("detectionId"));
        if (detectionId == null) {
            return reject(DetectionReason.SCHEMA_INVALID);
        }
        String kind = text(item.get("kind"));
        Integer version = integer(item.get("schemaVersion"));
        if (kind == null || !KIND.matcher(kind).matches() || version == null || version < 1 || version > 999) {
            return reject(DetectionReason.SCHEMA_INVALID);
        }
        Schema schema = this.schemas.find(spec.sourceSystem(), kind, version).orElse(null);
        if (schema == null || !spec.allowedKinds().contains(kind + ":" + version)) {
            return reject(DetectionReason.UNKNOWN_KIND);
        }
        Instant occurredAt = instant(item.get("occurredAt"));
        if (occurredAt == null) {
            return reject(DetectionReason.SCHEMA_INVALID);
        }
        if (occurredAt.isBefore(now.minus(MAX_AGE)) || occurredAt.isAfter(now.plus(MAX_SKEW))) {
            return reject(DetectionReason.OCCURRED_AT_OUT_OF_RANGE);
        }
        String server = text(item.get("server"));
        if (!lengthBetween(server, 1, SERVER_MAX) || hasControl(server)) {
            return reject(DetectionReason.SCHEMA_INVALID);
        }
        if (!IntegrationServerScope.allows(server, spec.allowedTasks(), spec.allowedStaticServers())) {
            return reject(DetectionReason.SERVER_OUT_OF_SCOPE);
        }
        UUID owner = uuid(item.get("owner"));
        if (owner == null) {
            return reject(DetectionReason.SCHEMA_INVALID);
        }
        String severity = severity(item.get("severity"));
        if (severity == null && item.get("severity") != null && !item.get("severity").isNull()) {
            return reject(DetectionReason.SCHEMA_INVALID);
        }
        String summary = text(item.get("summary"));
        if (!lengthBetween(summary, 1, SUMMARY_MAX) || hasControl(summary)) {
            return reject(DetectionReason.SCHEMA_INVALID);
        }
        if (containsPii(summary)) {
            return reject(DetectionReason.PII_IN_PAYLOAD);
        }
        JsonNode payload = item.get("payload");
        DetectionReason payloadProblem = payloadProblem(payload, schema);
        if (payloadProblem != null) {
            return reject(payloadProblem);
        }
        return new Verdict.Valid(new Detection(detectionId, kind, version, occurredAt, server, owner, severity, summary, payload));
    }

    /** The first problem of the payload, or null. Cheap structural rules run before the schema, PII last. */
    private static DetectionReason payloadProblem(JsonNode payload, Schema schema) {
        if (payload == null || !payload.isObject()) {
            return DetectionReason.SCHEMA_INVALID;
        }
        try {
            if (MAPPER.writeValueAsBytes(payload).length > MAX_PAYLOAD_BYTES) {
                return DetectionReason.SCHEMA_INVALID;
            }
        } catch (JsonProcessingException e) {
            return DetectionReason.SCHEMA_INVALID;
        }
        if (depth(payload) > MAX_DEPTH) {
            return DetectionReason.SCHEMA_INVALID;
        }
        boolean[] control = {false};
        boolean[] pii = {false};
        forEachString(payload, value -> {
            control[0] |= hasControl(value);
            pii[0] |= containsPii(value);
        });
        if (control[0]) {
            return DetectionReason.SCHEMA_INVALID;
        }
        if (!schema.validate(payload).isEmpty()) {
            return DetectionReason.SCHEMA_INVALID;
        }
        return pii[0] ? DetectionReason.PII_IN_PAYLOAD : null;
    }

    /** Nesting depth of a JSON value: a scalar is 0, a container is one more than its deepest child. */
    static int depth(JsonNode node) {
        if (!node.isContainerNode()) {
            return 0;
        }
        int deepest = 0;
        for (JsonNode child : node) {
            deepest = Math.max(deepest, depth(child));
        }
        return deepest + 1;
    }

    /** Every string of the value: the text of each string node and each field name. */
    static void forEachString(JsonNode node, Consumer<String> visitor) {
        if (node.isTextual()) {
            visitor.accept(node.asText());
        } else if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                visitor.accept(field.getKey());
                forEachString(field.getValue(), visitor);
            }
        } else if (node.isArray()) {
            node.forEach(child -> forEachString(child, visitor));
        }
    }

    /** Whether a text contains a player UUID or an IP address literal (the same check as the evidence path). */
    static boolean containsPii(String text) {
        return PiiGuard.containsUuid(text) || PiiGuard.containsIp(text);
    }

    static boolean hasControl(String text) {
        return text != null && text.chars().anyMatch(Character::isISOControl);
    }

    /** Length in code points, the way the schema's maxLength counts. */
    static boolean lengthBetween(String text, int min, int max) {
        if (text == null) {
            return false;
        }
        int length = text.codePointCount(0, text.length());
        return length >= min && length <= max;
    }

    private static Set<String> names(JsonNode item) {
        Set<String> names = new HashSet<>();
        item.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Verdict reject(DetectionReason reason) {
        return new Verdict.Rejected(reason);
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static UUID uuid(JsonNode node) {
        String text = text(node);
        if (text == null || !UUID_TEXT.matcher(text).matches()) {
            return null;
        }
        return UUID.fromString(text);
    }

    private static Integer integer(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() ? node.asInt() : null;
    }

    private static Instant instant(JsonNode node) {
        String text = text(node);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The severity text when it is one of the enum values, else null (an absent or null severity is also null). */
    private static String severity(JsonNode node) {
        String text = text(node);
        return text != null && SEVERITIES.contains(text) ? text : null;
    }
}
