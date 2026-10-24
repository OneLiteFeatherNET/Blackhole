package net.onelitefeather.blackhole.backend.casetimeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.Map;

/** Reads a stored payload into its record and (de)serialises payloads to JSON. */
public final class CaseEventPayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private CaseEventPayloads() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** The JSON text of a payload. */
    public static String toJson(CaseEventPayload payload) {
        try {
            return MAPPER.writeValueAsString(payload.toMap());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise a " + payload.type() + " payload", e);
        }
    }

    /** The JSON object of a stored payload; empty for {@code null} (a redacted payload). */
    public static Map<String, Object> parse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not read a stored case event payload", e);
        }
    }

    /**
     * The typed payload of a stored event. A type this release does not know, a payload of a newer
     * version, and a body that does not match its type all come back as {@link CaseEventPayload.Unknown}
     * with the body kept, never as an error.
     */
    public static CaseEventPayload read(String type, int version, Map<String, Object> body) {
        if (version != CaseEventPayload.CURRENT_VERSION) {
            return new CaseEventPayload.Unknown(type, version, body);
        }
        try {
            CaseEventPayload typed = switch (type) {
                case CaseEventPayload.APPLIED -> new CaseEventPayload.Applied(text(body, "section"), integer(body, "stepIndex"),
                        integer(body, "stepCount"), integer(body, "eloCost"), integer(body, "scoreBefore"), integer(body, "scoreAfter"),
                        flag(body, "permanent"), flag(body, "escalated"), text(body, "templateId"), number(body, "expiresAt"));
                case CaseEventPayload.REVOKED -> new CaseEventPayload.Revoked(flag(body, "approximate"));
                case CaseEventPayload.LIFTED -> new CaseEventPayload.Lifted(text(body, "appealId"), flag(body, "approximate"));
                case CaseEventPayload.RETEMPLATED -> new CaseEventPayload.Retemplated(
                        required(text(body, "templateId")), text(body, "fromTemplateId"));
                case CaseEventPayload.EXPIRED -> new CaseEventPayload.Expired(required(number(body, "expiredAt")));
                case CaseEventPayload.EVIDENCE_ATTACHED -> new CaseEventPayload.EvidenceAttached(
                        required(text(body, "evidenceId")), text(body, "kind"), text(body, "sourceSystem"));
                case CaseEventPayload.EVIDENCE_LIFTED -> new CaseEventPayload.EvidenceLifted(
                        required(text(body, "evidenceId")), text(body, "appealId"), flag(body, "approximate"));
                case CaseEventPayload.EVIDENCE_REVOKED -> new CaseEventPayload.EvidenceRevoked(
                        required(text(body, "evidenceId")), flag(body, "approximate"));
                case CaseEventPayload.APPEAL_FILED -> new CaseEventPayload.AppealFiled(required(text(body, "appealId")), flag(body, "eligible"));
                case CaseEventPayload.APPEAL_DECIDED -> new CaseEventPayload.AppealDecided(
                        required(text(body, "appealId")), required(text(body, "decision")), number(body, "newExpirationAt"));
                case CaseEventPayload.STATUS_CHANGED -> new CaseEventPayload.StatusChanged(
                        required(text(body, "from")), required(text(body, "to")));
                case CaseEventPayload.ASSIGNEE_CHANGED -> new CaseEventPayload.AssigneeChanged(text(body, "from"), text(body, "to"));
                case CaseEventPayload.PRIORITY_CHANGED -> new CaseEventPayload.PriorityChanged(
                        required(text(body, "from")), required(text(body, "to")));
                case CaseEventPayload.TAGS_CHANGED -> new CaseEventPayload.TagsChanged(texts(body, "added"), texts(body, "removed"));
                case CaseEventPayload.NOTE_ADDED -> new CaseEventPayload.NoteAdded(required(text(body, "noteId")), required(text(body, "text")));
                case CaseEventPayload.NOTE_EDITED -> new CaseEventPayload.NoteEdited(required(text(body, "noteId")), required(text(body, "text")));
                case CaseEventPayload.NOTE_REDACTED -> new CaseEventPayload.NoteRedacted(
                        required(text(body, "noteId")), required(text(body, "reason")));
                case CaseEventPayload.MESSAGE_ADDED -> {
                    Map<String, Object> author = map(body, "author");
                    yield new CaseEventPayload.MessageAdded(required(text(body, "messageId")), text(body, "appealId"),
                            MessageAuthorKind.valueOf(required(text(author, "kind"))), text(author, "participantId"),
                            MessageChannel.valueOf(required(text(body, "channel"))), text(body, "text"));
                }
                case CaseEventPayload.MESSAGE_EDITED -> new CaseEventPayload.MessageEdited(required(text(body, "messageId")), text(body, "text"));
                case CaseEventPayload.MESSAGE_DELETED -> new CaseEventPayload.MessageDeleted(
                        required(text(body, "messageId")), MessageAuthorKind.valueOf(required(text(body, "deletedBy"))));
                case CaseEventPayload.MESSAGE_REDACTED -> new CaseEventPayload.MessageRedacted(
                        required(text(body, "messageId")), required(text(body, "reason")));
                case CaseEventPayload.BAN_ID_LEAK_MARKED -> new CaseEventPayload.BanIdLeakMarked(
                        BanIdLeakReason.valueOf(required(text(body, "reason"))), BanIdLeakSource.valueOf(required(text(body, "source"))),
                        flag(body, "afterFiling"));
                case CaseEventPayload.BAN_ID_REISSUED -> new CaseEventPayload.BanIdReissued(
                        BanIdReissueReason.valueOf(required(text(body, "reason"))), required(integer(body, "fromGeneration")),
                        required(integer(body, "toGeneration")));
                case CaseEventPayload.TRANSCRIPT_EXPORTED -> new CaseEventPayload.TranscriptExported(
                        required(text(body, "format")), required(integer(body, "entries")), flag(body, "includeNotes"));
                case CaseEventPayload.PROTOCOL_STORED -> new CaseEventPayload.ProtocolStored(
                        required(text(body, "appealId")), required(integer(body, "version")), required(text(body, "evidenceId")),
                        required(integer(body, "entries")), required(text(body, "digest")));
                default -> null;
            };
            return typed != null ? typed : new CaseEventPayload.Unknown(type, version, body);
        } catch (IllegalArgumentException | ClassCastException e) {
            return new CaseEventPayload.Unknown(type, version, body);
        }
    }

    private static <T> T required(@Nullable T value) {
        if (value == null) {
            throw new IllegalArgumentException("a required payload field is missing");
        }
        return value;
    }

    private static @Nullable String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : value.toString();
    }

    private static @Nullable Long number(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : ((Number) value).longValue();
    }

    private static @Nullable Integer integer(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : ((Number) value).intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> body, String key) {
        return body.get(key) instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of();
    }

    private static List<String> texts(Map<String, Object> body, String key) {
        return body.get(key) instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static boolean flag(Map<String, Object> body, String key) {
        return body.get(key) instanceof Boolean value && value;
    }
}
