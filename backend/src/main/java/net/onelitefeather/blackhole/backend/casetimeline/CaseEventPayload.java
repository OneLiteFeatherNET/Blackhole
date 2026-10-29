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

    /**
     * The version of a {@code message.added} by a staff member who had a display name at write time: the author carries
     * {@code staffDisplayName}. Renames never rewrite it. Every other payload stays at {@link #CURRENT_VERSION}.
     */
    int NAMED_STAFF_MESSAGE_VERSION = 2;

    String APPLIED = "punishment.applied";
    String REVOKED = "punishment.revoked";
    String LIFTED = "punishment.lifted";
    String RETEMPLATED = "punishment.retemplated";
    String EXPIRED = "punishment.expired";
    String KICKED = "punishment.kicked";
    String WARNED = "punishment.warned";
    String ACKNOWLEDGED = "punishment.acknowledged";
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
    String MESSAGE_ADDED = "message.added";
    String MESSAGE_EDITED = "message.edited";
    String MESSAGE_DELETED = "message.deleted";
    String MESSAGE_REDACTED = "message.redacted";
    String EVIDENCE_SHARED = "evidence.shared";
    String EVIDENCE_SHARE_REVOKED = "evidence.share_revoked";
    String BAN_ID_LEAK_MARKED = "ban_id.leak_marked";
    String BAN_ID_REISSUED = "ban_id.reissued";
    String TRANSCRIPT_EXPORTED = "transcript.exported";
    String PROTOCOL_STORED = "protocol.stored";
    String TICKET_OPENED = "ticket.opened";
    String TICKET_TRANSCRIPT_COMPLETE = "ticket.transcript_complete";
    String TICKET_ARCHIVED = "ticket.archived";
    String TICKET_CHANNEL_DELETED = "ticket.channel_deleted";
    String TICKET_FROZEN = "ticket.frozen";

    /**
     * The only event types a player-facing reader may return, on top of the {@code PLAYER} visibility
     * filter. Both are applied in the query itself, so a type added later is invisible to players until
     * it is listed here. The lifecycle events {@code appeal.filed} and {@code appeal.decided} are listed but
     * written with {@code STAFF} visibility, so they pass through {@link #PORTAL_ANY_VISIBILITY_TYPES}
     * instead (decision of the public API change). Of the ticket markers only "opened" and "closed"
     * ({@code ticket.transcript_complete}) are listed; archived and deleted are staff facts.
     */
    List<String> PORTAL_VISIBLE_TYPES = List.of(MESSAGE_ADDED, MESSAGE_EDITED, MESSAGE_DELETED, MESSAGE_REDACTED,
            APPEAL_FILED, APPEAL_DECIDED, BAN_ID_LEAK_MARKED, BAN_ID_REISSUED, TICKET_OPENED, TICKET_TRANSCRIPT_COMPLETE,
            EVIDENCE_SHARED, EVIDENCE_SHARE_REVOKED);

    /**
     * The lifecycle facts a player sees whatever the event's visibility: the player's own appeal was filed and
     * decided. Their events stay {@code STAFF} visibility (nothing is rewritten, no migration, and the panel
     * keeps its meaning of the word), carry only ids, a flag and an outcome, and a player-facing reader turns
     * them into a bare marker without reading the payload. This is the single exception to the visibility
     * filter and it is a fixed list of two.
     */
    List<String> PORTAL_ANY_VISIBILITY_TYPES = List.of(APPEAL_FILED, APPEAL_DECIDED);

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

    /** An imported punishment was linked to another template; no score, step or duration changed. */
    record Retemplated(String templateId, @Nullable String fromTemplateId) implements CaseEventPayload {
        @Override
        public String type() {
            return RETEMPLATED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("templateId", templateId);
            put(map, "fromTemplateId", fromTemplateId);
            return map;
        }
    }

    /**
     * The player was kicked: a moment, not a sanction. Names where the kick took the player from and what its
     * template step cost; never the reason (it is shown to the player and kept on the kick itself).
     *
     * @param scope  {@code NETWORK}, {@code SERVER} or {@code SECTION}
     * @param target the server or section for a scoped kick
     */
    record Kicked(@Nullable String scope, @Nullable String target, @Nullable Integer eloCost) implements CaseEventPayload {
        @Override
        public String type() {
            return KICKED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            put(map, "scope", scope);
            put(map, "target", target);
            put(map, "eloCost", eloCost);
            return map;
        }
    }

    /**
     * A warning was given: the template it came from and the ELO cost of its step. The reason is on the warning itself.
     *
     * @param templateKey the key of the template the warning was applied from
     * @param eloCost     the ELO cost of the warning's step, if any
     */
    record Warned(@Nullable String templateKey, @Nullable Integer eloCost) implements CaseEventPayload {
        @Override
        public String type() {
            return WARNED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            put(map, "templateKey", templateKey);
            put(map, "eloCost", eloCost);
            return map;
        }
    }

    /** The player confirmed a warning: a moment, the time is the event's own. */
    record Acknowledged() implements CaseEventPayload {
        @Override
        public String type() {
            return ACKNOWLEDGED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            return new LinkedHashMap<>();
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
     * A conversation message. {@code text} is {@code null} once the message was redacted; the author
     * kind, channel and ids stay. The one payload (with the edit) that holds a player's words.
     */
    record MessageAdded(String messageId, @Nullable String appealId, MessageAuthorKind authorKind, @Nullable String participantId,
                        MessageChannel channel, @Nullable String text, List<MessageAttachment> attachments, List<MessageEmbed> embeds,
                        @Nullable String staffDisplayName)
            implements CaseEventPayload {

        public MessageAdded {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            embeds = embeds == null ? List.of() : List.copyOf(embeds);
        }

        /** Without a display name snapshot (every message written before staff profiles, and every player message). */
        public MessageAdded(String messageId, @Nullable String appealId, MessageAuthorKind authorKind, @Nullable String participantId,
                            MessageChannel channel, @Nullable String text, List<MessageAttachment> attachments, List<MessageEmbed> embeds) {
            this(messageId, appealId, authorKind, participantId, channel, text, attachments, embeds, null);
        }

        /** A message without attachments or embeds, which is every portal and panel message. */
        public MessageAdded(String messageId, @Nullable String appealId, MessageAuthorKind authorKind, @Nullable String participantId,
                            MessageChannel channel, @Nullable String text) {
            this(messageId, appealId, authorKind, participantId, channel, text, List.of(), List.of(), null);
        }

        @Override
        public String type() {
            return MESSAGE_ADDED;
        }

        @Override
        public int version() {
            return staffDisplayName != null && authorKind == MessageAuthorKind.STAFF ? NAMED_STAFF_MESSAGE_VERSION : CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messageId", messageId);
            put(map, "appealId", appealId);
            Map<String, Object> author = new LinkedHashMap<>();
            author.put("kind", authorKind.name());
            put(author, "participantId", participantId);
            put(author, "staffDisplayName", staffDisplayName);
            map.put("author", author);
            map.put("channel", channel.name());
            put(map, "text", text);
            if (!attachments.isEmpty()) {
                map.put("attachments", attachments.stream().map(MessageAttachment::toMap).toList());
            }
            if (!embeds.isEmpty()) {
                map.put("embeds", embeds.stream().map(MessageEmbed::toMap).toList());
            }
            return map;
        }
    }

    /**
     * Staff shared one evidence item with the player. Ids, kind, mode and a line count only: never the lines, a file name or a speaker.
     *
     * @param evidenceKind {@code CHAT_LOG} or {@code FILE}
     * @param mode         {@code EXCERPT} or {@code FILE}
     * @param lineCount    the number of shared lines of an excerpt
     */
    record EvidenceShared(String shareId, String evidenceKind, String mode, @Nullable Integer lineCount) implements CaseEventPayload {
        @Override
        public String type() {
            return EVIDENCE_SHARED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("shareId", shareId);
            map.put("evidenceKind", evidenceKind);
            map.put("mode", mode);
            put(map, "lineCount", lineCount);
            return map;
        }
    }

    /** A share was withdrawn. {@code reason} is a code ({@code MISTAKE}, {@code PRIVACY}, {@code OTHER}). */
    record EvidenceShareRevoked(String shareId, String reason) implements CaseEventPayload {
        @Override
        public String type() {
            return EVIDENCE_SHARE_REVOKED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            return Map.of("shareId", shareId, "reason", reason);
        }
    }

    /** A message was edited; {@code text} is the new full text ({@code null} after a redaction). */
    record MessageEdited(String messageId, @Nullable String text) implements CaseEventPayload {
        @Override
        public String type() {
            return MESSAGE_EDITED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messageId", messageId);
            put(map, "text", text);
            return map;
        }
    }

    /**
     * A message was deleted; the earlier versions stay. {@code deletedBy} is the kind of author who deleted it.
     * {@code contentUnavailable}: the message was never recorded, so there is no text. {@code detectedAtCatchup}: the
     * deletion was noticed by a full rescan, not seen when it happened.
     */
    record MessageDeleted(String messageId, MessageAuthorKind deletedBy, boolean contentUnavailable, boolean detectedAtCatchup)
            implements CaseEventPayload {

        public MessageDeleted(String messageId, MessageAuthorKind deletedBy) {
            this(messageId, deletedBy, false, false);
        }

        @Override
        public String type() {
            return MESSAGE_DELETED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messageId", messageId);
            map.put("deletedBy", deletedBy.name());
            if (contentUnavailable) {
                map.put("contentUnavailable", true);
            }
            if (detectedAtCatchup) {
                map.put("detectedAtCatchup", true);
            }
            return map;
        }
    }

    /** Every version of a message was redacted; carries the reason code and never the text. */
    record MessageRedacted(String messageId, String reason) implements CaseEventPayload {
        @Override
        public String type() {
            return MESSAGE_REDACTED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            return Map.of("messageId", messageId, "reason", reason);
        }
    }

    /**
     * The Ban ID was marked leaked. Never carries the Ban ID. {@code afterFiling}: an appeal had been filed.
     * {@code poster} is the opaque participant who posted it and {@code posterIsOwner} whether that was the
     * ticket's bound player; both only for a leak the Discord bot found.
     */
    record BanIdLeakMarked(BanIdLeakReason reason, BanIdLeakSource source, boolean afterFiling, @Nullable String poster,
                           @Nullable Boolean posterIsOwner) implements CaseEventPayload {

        public BanIdLeakMarked(BanIdLeakReason reason, BanIdLeakSource source, boolean afterFiling) {
            this(reason, source, afterFiling, null, null);
        }

        @Override
        public String type() {
            return BAN_ID_LEAK_MARKED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("reason", reason.name());
            map.put("source", source.name());
            map.put("afterFiling", afterFiling);
            put(map, "poster", poster);
            put(map, "posterIsOwner", posterIsOwner);
            return map;
        }
    }

    /** A new Ban ID was issued; generation numbers only, never either Ban ID. */
    record BanIdReissued(BanIdReissueReason reason, int fromGeneration, int toGeneration) implements CaseEventPayload {
        @Override
        public String type() {
            return BAN_ID_REISSUED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            return Map.of("reason", reason.name(), "fromGeneration", fromGeneration, "toGeneration", toGeneration);
        }
    }

    /** The conversation was exported: format and entry count, never content. */
    record TranscriptExported(String format, int entries, boolean includeNotes) implements CaseEventPayload {
        @Override
        public String type() {
            return TRANSCRIPT_EXPORTED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            return Map.of("format", format, "entries", entries, "includeNotes", includeNotes);
        }
    }

    /**
     * A version of an appeal's protocol was stored as evidence. Ids, a count and the digest of the source
     * events, never content. Staff visibility, and deliberately not in {@link #PORTAL_VISIBLE_TYPES}.
     */
    record ProtocolStored(String appealId, int version, String evidenceId, int entries, String digest) implements CaseEventPayload {
        @Override
        public String type() {
            return PROTOCOL_STORED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("appealId", appealId);
            map.put("version", version);
            map.put("evidenceId", evidenceId);
            map.put("entries", entries);
            map.put("digest", digest);
            return map;
        }
    }

    /** A Discord ticket was opened for an appeal. {@code ticketNo} is the number in the channel name. */
    record TicketOpened(String ticketId, String appealId, long ticketNo) implements CaseEventPayload {
        @Override
        public String type() {
            return TICKET_OPENED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ticketId", ticketId);
            map.put("appealId", appealId);
            map.put("ticketNo", ticketNo);
            return map;
        }
    }

    /**
     * The transcript of a ticket channel was verified complete: how many messages the channel held, the highest
     * Discord message id (a string, snowflakes exceed what JSON numbers keep exactly) and how many edits and deletes
     * the record holds. Never a message id list and never text.
     */
    record TicketTranscriptComplete(String ticketId, String appealId, int count, @Nullable String lastMessageId, int edits, int deletes)
            implements CaseEventPayload {
        @Override
        public String type() {
            return TICKET_TRANSCRIPT_COMPLETE;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ticketId", ticketId);
            map.put("appealId", appealId);
            map.put("count", count);
            put(map, "lastMessageId", lastMessageId);
            map.put("edits", edits);
            map.put("deletes", deletes);
            return map;
        }
    }

    /** The ticket channel was moved to the archive; the protocol version that made that allowed. */
    record TicketArchived(String ticketId, String appealId, int protocolVersion) implements CaseEventPayload {
        @Override
        public String type() {
            return TICKET_ARCHIVED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ticketId", ticketId);
            map.put("appealId", appealId);
            map.put("protocolVersion", protocolVersion);
            return map;
        }
    }

    /**
     * The staff lock of the ticket channel was set or lifted. {@code freezeSource} is {@code STAFF} while locked, {@code null} once unlocked.
     * Staff only; carries no Discord id.
     */
    record TicketFrozen(String ticketId, String appealId, boolean frozen, @Nullable String freezeSource) implements CaseEventPayload {
        @Override
        public String type() {
            return TICKET_FROZEN;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ticketId", ticketId);
            map.put("appealId", appealId);
            map.put("frozen", frozen);
            put(map, "freezeSource", freezeSource);
            return map;
        }
    }

    /**
     * The ticket channel is gone. {@code transcriptComplete} is false when a person deleted it before the transcript was
     * verified; {@code lastRecordedMessageAt} then tells the panel where the record ends.
     */
    record TicketChannelDeleted(String ticketId, String appealId, boolean transcriptComplete, @Nullable Long lastRecordedMessageAt)
            implements CaseEventPayload {
        @Override
        public String type() {
            return TICKET_CHANNEL_DELETED;
        }

        @Override
        public int version() {
            return CURRENT_VERSION;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ticketId", ticketId);
            map.put("appealId", appealId);
            map.put("transcriptComplete", transcriptComplete);
            put(map, "lastRecordedMessageAt", lastRecordedMessageAt);
            return map;
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
