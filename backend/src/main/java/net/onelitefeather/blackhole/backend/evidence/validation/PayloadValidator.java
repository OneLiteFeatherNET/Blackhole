package net.onelitefeather.blackhole.backend.evidence.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evidence.EvidenceConfig;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceErrorKind;
import net.onelitefeather.blackhole.backend.utils.OwnerHash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validates the structured content of ANTICHEAT, CHAT_LOG, REPLAY and LINK evidence (payload schema
 * version {@value #SCHEMA_VERSION}). The schemas are small and fixed, so they are plain code rather
 * than a JSON-schema library: every kind lists the properties it declares and anything else is
 * rejected, so nobody can smuggle identifiers through unknown fields.
 *
 * <p>Order of the checks: size (413), shape (400), then personal data (422).
 */
@Singleton
public class PayloadValidator {

    /** The version stored in {@code payload_schema_version}. */
    public static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ANTICHEAT_KEYS = Set.of("check", "values", "violationLevel", "server", "detail");
    private static final Set<String> CHAT_KEYS = Set.of("lines");
    private static final Set<String> CHAT_LINE_KEYS = Set.of("timestamp", "speaker", "text");
    private static final Set<String> LINK_KEYS = Set.of("description");
    private static final int MAX_VALUES = 32;
    private static final int MAX_SHORT_TEXT = 64;
    private static final int MAX_VALUE_TEXT = 256;
    private static final int MAX_DESCRIPTION = 500;
    private static final int MAX_CHAT_TEXT = 256;
    private static final int MAX_ALIAS = 32;

    /**
     * The outcome of a validation: the sanitised content to store, or the error to answer.
     *
     * @param payload   the payload to store (chat text with IP addresses redacted), {@code null} for a link without description
     * @param reference the validated reference URL, {@code null} for kinds without one
     * @param error     why the content was rejected, {@code null} if it was accepted
     */
    public record Validated(Map<String, Object> payload, String reference, EvidenceDTO.Error error) {

        public boolean accepted() {
            return this.error == null;
        }
    }

    private final EvidenceConfig config;

    public PayloadValidator(EvidenceConfig config) {
        this.config = config;
    }

    /**
     * Validates the content of a JSON-kind evidence item.
     *
     * @param kind      the kind being attached (FILE is rejected: files have their own endpoint)
     * @param reference the REPLAY / LINK URL, otherwise expected to be {@code null}
     * @param payload   the structured payload
     */
    public Validated validate(EvidenceKind kind, String reference, Map<String, Object> payload) {
        if (kind == EvidenceKind.FILE) {
            return rejected(EvidenceErrorKind.VALIDATION, "files are uploaded through POST /evidence/file");
        }
        boolean linkKind = kind == EvidenceKind.REPLAY || kind == EvidenceKind.LINK;
        if (!linkKind && reference != null) {
            return rejected(EvidenceErrorKind.VALIDATION, "reference is only accepted for REPLAY and LINK evidence");
        }
        if (!linkKind && (payload == null || payload.isEmpty())) {
            return rejected(EvidenceErrorKind.VALIDATION, "payload is required for " + kind + " evidence");
        }
        Optional<EvidenceDTO.Error> tooLarge = checkSize(payload);
        if (tooLarge.isPresent()) {
            return new Validated(null, null, tooLarge.get());
        }

        return switch (kind) {
            case ANTICHEAT -> anticheat(payload);
            case CHAT_LOG -> chatLog(payload);
            case REPLAY, LINK -> link(reference, payload);
            case FILE -> throw new IllegalStateException("handled above");
        };
    }

    private Optional<EvidenceDTO.Error> checkSize(Map<String, Object> payload) {
        if (payload == null) {
            return Optional.empty();
        }
        try {
            int size = JSON.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8).length;
            if (size > this.config.getMaxPayloadBytes()) {
                return Optional.of(error(EvidenceErrorKind.PAYLOAD_TOO_LARGE,
                        "payload is " + size + " bytes, the limit is " + this.config.getMaxPayloadBytes()));
            }
        } catch (JsonProcessingException e) {
            return Optional.of(error(EvidenceErrorKind.VALIDATION, "payload is not valid JSON"));
        }
        return Optional.empty();
    }

    // ---- ANTICHEAT -----------------------------------------------------------------------------------------

    private Validated anticheat(Map<String, Object> payload) {
        Optional<EvidenceDTO.Error> undeclared = undeclared("", payload, ANTICHEAT_KEYS);
        if (undeclared.isPresent()) {
            return new Validated(null, null, undeclared.get());
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        Optional<EvidenceDTO.Error> failure = requiredText(payload, "check", MAX_SHORT_TEXT, clean)
                .or(() -> optionalText(payload, "server", MAX_SHORT_TEXT, clean))
                .or(() -> optionalText(payload, "detail", MAX_DESCRIPTION, clean))
                .or(() -> violationLevel(payload, clean))
                .or(() -> values(payload, clean));
        if (failure.isPresent()) {
            return new Validated(null, null, failure.get());
        }
        return personalData(clean);
    }

    private Optional<EvidenceDTO.Error> violationLevel(Map<String, Object> payload, Map<String, Object> clean) {
        Object level = payload.get("violationLevel");
        if (level == null) {
            return Optional.empty();
        }
        if (!(level instanceof Number)) {
            return Optional.of(error(EvidenceErrorKind.VALIDATION, "/violationLevel must be a number"));
        }
        clean.put("violationLevel", level);
        return Optional.empty();
    }

    private Optional<EvidenceDTO.Error> values(Map<String, Object> payload, Map<String, Object> clean) {
        Object raw = payload.get("values");
        if (raw == null) {
            return Optional.empty();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return Optional.of(error(EvidenceErrorKind.VALIDATION, "/values must be an object"));
        }
        if (map.size() > MAX_VALUES) {
            return Optional.of(error(EvidenceErrorKind.VALIDATION, "/values may have at most " + MAX_VALUES + " entries"));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (key.length() > MAX_SHORT_TEXT) {
                return Optional.of(error(EvidenceErrorKind.VALIDATION, "/values keys may be at most " + MAX_SHORT_TEXT + " characters"));
            }
            boolean scalar = value instanceof String || value instanceof Number || value instanceof Boolean;
            if (!scalar) {
                return Optional.of(error(EvidenceErrorKind.VALIDATION,
                        "/values/" + key + " must be a string, number or boolean (the map is flat)"));
            }
            if (value instanceof String text && text.length() > MAX_VALUE_TEXT) {
                return Optional.of(error(EvidenceErrorKind.VALIDATION, "/values/" + key + " may be at most " + MAX_VALUE_TEXT + " characters"));
            }
            values.put(key, value);
        }
        clean.put("values", values);
        return Optional.empty();
    }

    // ---- CHAT_LOG ------------------------------------------------------------------------------------------

    private Validated chatLog(Map<String, Object> payload) {
        Optional<EvidenceDTO.Error> undeclared = undeclared("", payload, CHAT_KEYS);
        if (undeclared.isPresent()) {
            return new Validated(null, null, undeclared.get());
        }
        if (!(payload.get("lines") instanceof List<?> lines)) {
            return rejected(EvidenceErrorKind.VALIDATION, "/lines must be a list");
        }
        if (lines.isEmpty() || lines.size() > this.config.getMaxChatLines()) {
            return rejected(EvidenceErrorKind.VALIDATION, "/lines must have between 1 and " + this.config.getMaxChatLines() + " lines");
        }
        List<Map<String, Object>> clean = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String pointer = "/lines/" + i;
            if (!(lines.get(i) instanceof Map<?, ?> raw)) {
                return rejected(EvidenceErrorKind.VALIDATION, pointer + " must be an object");
            }
            Map<String, Object> line = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                line.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            Optional<EvidenceDTO.Error> lineUndeclared = undeclared(pointer, line, CHAT_LINE_KEYS);
            if (lineUndeclared.isPresent()) {
                return new Validated(null, null, lineUndeclared.get());
            }
            Map<String, Object> cleanLine = new LinkedHashMap<>();
            Optional<EvidenceDTO.Error> failure = chatLine(pointer, line, cleanLine);
            if (failure.isPresent()) {
                return new Validated(null, null, failure.get());
            }
            clean.add(cleanLine);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lines", clean);
        return new Validated(result, null, null);
    }

    private Optional<EvidenceDTO.Error> chatLine(String pointer, Map<String, Object> line, Map<String, Object> clean) {
        Object timestamp = line.get("timestamp");
        if (!(timestamp instanceof Number) && !(timestamp instanceof String text && !text.isBlank() && text.length() <= 40)) {
            return Optional.of(error(EvidenceErrorKind.VALIDATION, pointer + "/timestamp must be epoch millis or an ISO-8601 string"));
        }
        clean.put("timestamp", timestamp);

        Object speaker = line.get("speaker");
        if (speaker != null) {
            if (!(speaker instanceof String name) || name.isBlank()) {
                return Optional.of(error(EvidenceErrorKind.VALIDATION, pointer + "/speaker must be a string or null"));
            }
            boolean hash = OwnerHash.isValid(name);
            if (!hash && (PiiGuard.containsUuid(name) || PiiGuard.containsIp(name))) {
                return Optional.of(error(EvidenceErrorKind.UNPROCESSABLE,
                        pointer + "/speaker looks like a UUID or an IP address; send an owner hash or an alias"));
            }
            if (!hash && name.length() > MAX_ALIAS) {
                return Optional.of(error(EvidenceErrorKind.VALIDATION,
                        pointer + "/speaker must be an owner hash or an alias of at most " + MAX_ALIAS + " characters"));
            }
        }
        clean.put("speaker", speaker);

        if (!(line.get("text") instanceof String text) || text.isEmpty() || text.length() > MAX_CHAT_TEXT) {
            return Optional.of(error(EvidenceErrorKind.VALIDATION, pointer + "/text is required and may be at most " + MAX_CHAT_TEXT + " characters"));
        }
        // Players type IP addresses into chat; redact instead of rejecting so the evidence survives.
        clean.put("text", PiiGuard.redactIps(text));
        return Optional.empty();
    }

    // ---- REPLAY / LINK -------------------------------------------------------------------------------------

    private Validated link(String reference, Map<String, Object> payload) {
        Optional<String> invalid = UrlValidator.check(reference);
        if (invalid.isPresent()) {
            return rejected(EvidenceErrorKind.VALIDATION, invalid.get());
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        if (payload != null && !payload.isEmpty()) {
            Optional<EvidenceDTO.Error> undeclared = undeclared("", payload, LINK_KEYS);
            if (undeclared.isPresent()) {
                return new Validated(null, null, undeclared.get());
            }
            Optional<EvidenceDTO.Error> failure = optionalText(payload, "description", MAX_DESCRIPTION, clean);
            if (failure.isPresent()) {
                return new Validated(null, null, failure.get());
            }
        }
        Validated checked = personalData(clean);
        if (!checked.accepted()) {
            return checked;
        }
        return new Validated(clean.isEmpty() ? null : clean, reference, null);
    }

    // ---- shared --------------------------------------------------------------------------------------------

    private static Optional<EvidenceDTO.Error> undeclared(String pointer, Map<String, Object> map, Set<String> declared) {
        for (String key : map.keySet()) {
            if (!declared.contains(key)) {
                return Optional.of(error(EvidenceErrorKind.VALIDATION, "undeclared property " + pointer + "/" + key));
            }
        }
        return Optional.empty();
    }

    private static Optional<EvidenceDTO.Error> requiredText(Map<String, Object> payload, String key, int max, Map<String, Object> clean) {
        if (!(payload.get(key) instanceof String text) || text.isBlank()) {
            return Optional.of(error(EvidenceErrorKind.VALIDATION, "/" + key + " is required"));
        }
        if (text.length() > max) {
            return Optional.of(error(EvidenceErrorKind.VALIDATION, "/" + key + " may be at most " + max + " characters"));
        }
        clean.put(key, text);
        return Optional.empty();
    }

    private static Optional<EvidenceDTO.Error> optionalText(Map<String, Object> payload, String key, int max, Map<String, Object> clean) {
        Object value = payload.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String text)) {
            return Optional.of(error(EvidenceErrorKind.VALIDATION, "/" + key + " must be a string"));
        }
        if (text.length() > max) {
            return Optional.of(error(EvidenceErrorKind.VALIDATION, "/" + key + " may be at most " + max + " characters"));
        }
        clean.put(key, text);
        return Optional.empty();
    }

    /** Rejects structured content that carries a raw UUID or IP address. */
    private static Validated personalData(Map<String, Object> clean) {
        Optional<String> pointer = PiiGuard.firstViolation("", clean);
        if (pointer.isPresent()) {
            return rejected(EvidenceErrorKind.UNPROCESSABLE,
                    "the value at " + pointer.get() + " looks like a UUID or an IP address; send hashes, not raw identifiers");
        }
        return new Validated(clean, null, null);
    }

    private static Validated rejected(EvidenceErrorKind kind, String message) {
        return new Validated(null, null, error(kind, message));
    }

    private static EvidenceDTO.Error error(EvidenceErrorKind kind, String message) {
        return new EvidenceDTO.Error(message, kind);
    }
}
