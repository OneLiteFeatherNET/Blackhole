package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The body of a case event, one record per event type. Payloads hold ids, enums and numbers, never
 * free text (that is for note types only) and never an appeal statement or decision note.
 *
 * <p>Stored as a JSON object next to the event's {@code type} (the discriminator) and
 * {@code payload_version}; {@link CaseEventPayloads#read} turns the stored form back into a record, or
 * into an {@link Unknown} for a type or version this release does not know.</p>
 */
public sealed interface CaseEventPayload {

    /** The schema version every payload of this release is written with. */
    int CURRENT_VERSION = 1;

    String APPLIED = "punishment.applied";
    String REVOKED = "punishment.revoked";
    String LIFTED = "punishment.lifted";
    String EXPIRED = "punishment.expired";
    String EVIDENCE_ATTACHED = "evidence.attached";
    String EVIDENCE_LIFTED = "evidence.lifted";
    String EVIDENCE_REVOKED = "evidence.revoked";
    String APPEAL_FILED = "appeal.filed";
    String APPEAL_DECIDED = "appeal.decided";
    String STATUS_CHANGED = "case.status_changed";
    String ASSIGNEE_CHANGED = "case.assignee_changed";
    String PRIORITY_CHANGED = "case.priority_changed";
    String TAGS_CHANGED = "case.tags_changed";
    String NOTE_ADDED = "note.added";
    String NOTE_EDITED = "note.edited";
    String NOTE_REDACTED = "note.redacted";

    /** The dotted lower case type, such as {@code punishment.applied}. */
    String type();

    /** The schema version of this payload. */
    int version();

    /** The JSON object to store; entries without a value are left out. */
    Map<String, Object> toMap();

    /** {@code stepIndex} is the zero based index of the escalation step, as stored on the punishment. */
    record Applied(@Nullable String section, @Nullable Integer stepIndex, @Nullable Integer stepCount, @Nullable Integer eloCost,
                   @Nullable Integer scoreBefore, @Nullable Integer scoreAfter, boolean permanent, boolean escalated,
                   @Nullable String templateId, @Nullable Long expiresAt) implements CaseEventPayload {
        @Override
        public String type() {
            return APPLIED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            put(map, "section", section);
            put(map, "stepIndex", stepIndex);
            put(map, "stepCount", stepCount);
            put(map, "eloCost", eloCost);
            put(map, "scoreBefore", scoreBefore);
            put(map, "scoreAfter", scoreAfter);
            map.put("permanent", permanent);
            map.put("escalated", escalated);
            put(map, "templateId", templateId);
            put(map, "expiresAt", expiresAt);
            return map;
        }
    }

    /** Staff revoked the punishment. {@code approximate} is set when the time is not known exactly. */
    record Revoked(boolean approximate) implements CaseEventPayload {
        @Override
        public String type() {
            return REVOKED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (approximate) {
                map.put("approximate", true);
            }
            return map;
        }
    }

    /** A granted full-lift appeal lifted the punishment. */
    record Lifted(@Nullable String appealId, boolean approximate) implements CaseEventPayload {
        @Override
        public String type() {
            return LIFTED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            put(map, "appealId", appealId);
            if (approximate) {
                map.put("approximate", true);
            }
            return map;
        }
    }

    /** The punishment ran out. */
    record Expired(long expiredAt) implements CaseEventPayload {
        @Override
        public String type() {
            return EXPIRED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            return Map.of("expiredAt", expiredAt);
        }
    }

    /** Evidence was attached: ids and enums only, no content and no file name. */
    record EvidenceAttached(String evidenceId, @Nullable String kind, @Nullable String sourceSystem) implements CaseEventPayload {
        @Override
        public String type() {
            return EVIDENCE_ATTACHED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("evidenceId", evidenceId);
            put(map, "kind", kind);
            put(map, "sourceSystem", sourceSystem);
            return map;
        }
    }

    /** Evidence was lifted by a granted full-lift appeal. */
    record EvidenceLifted(String evidenceId, @Nullable String appealId, boolean approximate) implements CaseEventPayload {
        @Override
        public String type() {
            return EVIDENCE_LIFTED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("evidenceId", evidenceId);
            put(map, "appealId", appealId);
            if (approximate) {
                map.put("approximate", true);
            }
            return map;
        }
    }

    /** Evidence was revoked together with its punishment. */
    record EvidenceRevoked(String evidenceId, boolean approximate) implements CaseEventPayload {
        @Override
        public String type() {
            return EVIDENCE_REVOKED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("evidenceId", evidenceId);
            if (approximate) {
                map.put("approximate", true);
            }
            return map;
        }
    }

    /** An appeal was filed. The statement stays in the appeal; the timeline links by id. */
    record AppealFiled(String appealId, boolean eligible) implements CaseEventPayload {
        @Override
        public String type() {
            return APPEAL_FILED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            return Map.of("appealId", appealId, "eligible", eligible);
        }
    }

    /** An appeal was decided. The decision note stays in the appeal. */
    record AppealDecided(String appealId, String decision, @Nullable Long newExpirationAt) implements CaseEventPayload {
        @Override
        public String type() {
            return APPEAL_DECIDED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("appealId", appealId);
            map.put("decision", decision);
            put(map, "newExpirationAt", newExpirationAt);
            return map;
        }
    }

    /** The case status changed (this release only writes the automatic reopen). */
    record StatusChanged(String from, String to) implements CaseEventPayload {
        @Override
        public String type() {
            return STATUS_CHANGED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            return Map.of("from", from, "to", to);
        }
    }

    /** The case was assigned, or unassigned ({@code null}); the values are staff {@code oid}s. */
    record AssigneeChanged(@Nullable String from, @Nullable String to) implements CaseEventPayload {
        @Override
        public String type() {
            return ASSIGNEE_CHANGED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            put(map, "from", from);
            put(map, "to", to);
            return map;
        }
    }

    /** The case priority changed. */
    record PriorityChanged(String from, String to) implements CaseEventPayload {
        @Override
        public String type() {
            return PRIORITY_CHANGED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            return Map.of("from", from, "to", to);
        }
    }

    /** Tags were added to or removed from the case; both lists are sorted. */
    record TagsChanged(List<String> added, List<String> removed) implements CaseEventPayload {
        @Override
        public String type() {
            return TAGS_CHANGED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            return Map.of("added", added, "removed", removed);
        }
    }

    /** A staff note: the only payload that holds free text. */
    record NoteAdded(String noteId, String text) implements CaseEventPayload {
        @Override
        public String type() {
            return NOTE_ADDED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            return Map.of("noteId", noteId, "text", text);
        }
    }

    /** A note was edited; {@code text} is the new full text. */
    record NoteEdited(String noteId, String text) implements CaseEventPayload {
        @Override
        public String type() {
            return NOTE_EDITED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            return Map.of("noteId", noteId, "text", text);
        }
    }

    /** Every version of a note was redacted; carries the reason code and never the text. */
    record NoteRedacted(String noteId, String reason) implements CaseEventPayload {
        @Override
        public String type() {
            return NOTE_REDACTED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            return Map.of("noteId", noteId, "reason", reason);
        }
    }

    /**
     * A row this release cannot type: an unknown type, a newer payload version, a redacted payload or
     * a body that does not match its type. It is kept and shown generically, never dropped, so an older
     * backend can read what a newer one wrote.
     */
    record Unknown(String type, int version, Map<String, Object> body) implements CaseEventPayload {
        @Override
        public Map<String, Object> toMap() {
            return body;
        }
    }

    private static void put(Map<String, Object> map, String key, @Nullable Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
