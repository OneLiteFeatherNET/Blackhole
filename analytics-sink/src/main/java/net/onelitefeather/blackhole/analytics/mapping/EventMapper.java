package net.onelitefeather.blackhole.analytics.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns one envelope into one row of its family table, or into a dead letter with the reason. Pure: no I/O, no clock.
 */
public final class EventMapper {

    /** Payload versions the sink understands. Anything else is dead-lettered, never guessed at. */
    static final Set<Integer> SUPPORTED_PAYLOAD_VERSIONS = Set.of(1);

    /**
     * Staff identifiers (verified Entra object ids). They are not player identities, but they are still personal data,
     * so they are stored as a SHA-512 digest and never in clear text.
     */
    private static final Set<String> STAFF_KEYS = Set.of("recordedByOid", "resolvedBy");

    private static final String OWNER = "owner";

    private final ObjectMapper json = new ObjectMapper();

    public MappingResult map(EventEnvelope envelope) {
        if (!SUPPORTED_PAYLOAD_VERSIONS.contains(envelope.payloadVersion())) {
            return new DeadLetter(DeadLetterReason.UNSUPPORTED_VERSION,
                    "payload version " + envelope.payloadVersion());
        }
        Optional<EventFamily> family = EventFamily.of(envelope.eventType());
        if (family.isEmpty()) {
            return new DeadLetter(DeadLetterReason.UNKNOWN_TYPE, "event type " + envelope.eventType());
        }

        Map<String, Object> payload = pseudonymizeStaff(envelope.payload());
        Optional<String> violation = PseudonymityGuard.check(payload);
        if (violation.isPresent()) {
            return new DeadLetter(DeadLetterReason.PSEUDONYMITY_VIOLATION, violation.get());
        }

        Object rawOwner = payload.remove(OWNER);
        if (rawOwner != null && !(rawOwner instanceof String)) {
            return new DeadLetter(DeadLetterReason.MALFORMED, "owner is not a string");
        }

        Map<String, Object> typed = new LinkedHashMap<>();
        for (String key : family.get().typedKeys()) {
            if (!payload.containsKey(key)) {
                continue;
            }
            Object value = payload.remove(key);
            Optional<Object> column = toColumn(key, value);
            if (column.isEmpty()) {
                return new DeadLetter(DeadLetterReason.MALFORMED, "wrong value kind for key " + key);
            }
            typed.put(key, column.get());
        }

        String extra;
        try {
            extra = json.writeValueAsString(new TreeMap<>(payload));
        } catch (JsonProcessingException e) {
            return new DeadLetter(DeadLetterReason.MALFORMED, "extra payload is not serializable");
        }

        return new Mapped(new EventRow(
                family.get().table(),
                envelope.eventId(),
                envelope.eventType(),
                Instant.ofEpochMilli(envelope.occurredAt()),
                envelope.payloadVersion(),
                (String) rawOwner,
                typed,
                extra));
    }

    private static Optional<Object> toColumn(String key, Object value) {
        if (EventSchemas.isBooleanKey(key)) {
            return value instanceof Boolean ? Optional.of(value) : Optional.empty();
        }
        if (EventSchemas.isLongKey(key)) {
            return value instanceof Number number ? Optional.of(number.longValue()) : Optional.empty();
        }
        return Optional.of(String.valueOf(value));
    }

    private static Map<String, Object> pseudonymizeStaff(Map<String, Object> payload) {
        Map<String, Object> copy = new HashMap<>(payload);
        for (String key : STAFF_KEYS) {
            if (copy.get(key) instanceof String oid) {
                copy.put(key, sha512Hex(oid));
            }
        }
        return copy;
    }

    private static String sha512Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-512").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM provides SHA-512", e);
        }
    }
}
