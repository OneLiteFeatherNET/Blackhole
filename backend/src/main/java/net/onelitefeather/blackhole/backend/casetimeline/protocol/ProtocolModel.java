package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRecord;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;
import net.onelitefeather.blackhole.backend.casetimeline.MessageAuthorKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The protocol of one appeal as data: the entries of its conversation and lifecycle markers, the source
 * events they came from and the digest of those. {@link ProtocolRenderer} turns the model into the text
 * and JSON files; nothing here reads a clock, a database or another system, so the same events always
 * give the same model.
 *
 * <p>The source set is the appeal's message events (all channels, visibility {@code PLAYER} and
 * {@code STAFF}, with edits, deletions and redactions) and its lifecycle markers ({@code appeal.*},
 * {@code ban_id.*}, {@code ticket.*}). Notes, evidence, status and punishment events are not part of it.
 * Only whitelisted fields are copied, so a Ban ID, a Discord user id or an owner hash cannot get in by
 * being present in some payload.</p>
 *
 * @param decision   the appeal's decision, such as {@code DENIED}
 * @param decidedAt  when the appeal was decided
 * @param entries    the entries in {@code seq} order
 * @param source     every source event in {@code seq} order; the digest is computed from these
 * @param ticket     the Discord ticket's completeness proof, {@code null} for an appeal that never had a ticket
 * @param digest     SHA-256 of {@link #digestInput(List)}
 * @param lastActivityAt when the newest source event happened
 */
public record ProtocolModel(
        UUID appealId,
        String punishmentIdentifier,
        String decision,
        long decidedAt,
        List<Entry> entries,
        List<SourceEvent> source,
        @Nullable Ticket ticket,
        String digest,
        long lastActivityAt
) {

    /** The label of a player, pseudonymous: the same for every player message of the case. */
    public static final String PLAYER_LABEL = "Player";

    /** The label of the system. */
    public static final String SYSTEM_LABEL = "System";

    /** The label of staff whose name was removed by an anonymisation. */
    public static final String ANONYMOUS_STAFF_LABEL = "Staff member (name removed)";

    /**
     * Who wrote an entry.
     *
     * @param label         what the protocol shows
     * @param kind          {@code PLAYER}, {@code STAFF}, {@code BOT}, {@code EXTERNAL} or {@code SYSTEM}
     * @param trust         the actor trust of the event
     * @param participantId the opaque id of a Discord participant, if the message has one
     */
    public record Author(String label, String kind, String trust, @Nullable String participantId) {
    }

    /** One text of a message: the first, or an edit. {@code text} is {@code null} once redacted. */
    public record Text(long seq, long at, @Nullable String text) {
    }

    /**
     * Attachment metadata: name, type and size, never a URL or bytes. A chat file also has the first 12 hex digits of its SHA-256
     * (read from the evidence, not from the event) and its provenance, which make its marker line.
     */
    public record Attachment(@Nullable String name, @Nullable String type, @Nullable Long size, boolean chatFile, @Nullable String sha12,
                             @Nullable String provenance) {

        public Attachment(@Nullable String name, @Nullable String type, @Nullable Long size) {
            this(name, type, size, false, null, null);
        }

        /** The marker line of a chat file: type, size, the start of the hash and the sanitised name. */
        public String marker() {
            return "[file: " + (this.type == null ? "unknown type" : this.type) + ", " + (this.size == null ? "unknown size" : this.size + " bytes")
                    + ", sha256 " + (this.sha12 == null ? "unavailable" : this.sha12) + ", " + (this.name == null ? "(unnamed)" : this.name) + "]";
        }
    }

    /** Embed metadata: its type and host, never its content. */
    public record Embed(@Nullable String type, @Nullable String host) {
    }

    /** One line of the protocol. */
    public sealed interface Entry {

        long seq();

        long at();

        String visibility();

        Author author();
    }

    /**
     * A conversation message.
     *
     * @param text      the current text, {@code null} when redacted
     * @param texts     the first text and every edit, oldest first; the last is the current one
     * @param deletedAt when it was deleted, {@code null} while it stands (the last text is kept)
     */
    public record MessageEntry(
            long seq, long at, String channel, Author author, String visibility, @Nullable String text, List<Text> texts,
            @Nullable Long deletedAt, boolean redacted, List<Attachment> attachments, List<Embed> embeds
    ) implements Entry {
    }

    /** A lifecycle marker, with a few whitelisted details (ids, enums, counts). */
    public record EventEntry(long seq, long at, String type, Author author, String visibility, Map<String, Object> details) implements Entry {
    }

    /** One stored event the protocol was made from. */
    public record SourceEvent(UUID eventId, long seq, String type, boolean redacted) {
    }

    /**
     * The completeness proof of a Discord ticket.
     *
     * @param complete      whether {@code ticket.transcript_complete} exists
     * @param count         its message count
     * @param lastMessageId the last Discord message id it recorded
     */
    public record Ticket(boolean complete, @Nullable Long count, @Nullable String lastMessageId) {
    }

    /** How many entries the protocol lists. */
    public int entryCount() {
        return this.entries.size();
    }

    /** The lowest {@code seq} of the source events, {@code null} when there are none. */
    public @Nullable Long firstSeq() {
        return this.source.isEmpty() ? null : this.source.getFirst().seq();
    }

    /** The highest {@code seq} of the source events, {@code null} when there are none. */
    public @Nullable Long lastSeq() {
        return this.source.isEmpty() ? null : this.source.getLast().seq();
    }

    /** Whether a Discord ticket exists whose transcript was never verified complete: no protocol may be made. */
    public boolean ticketIncomplete() {
        return this.ticket != null && !this.ticket.complete();
    }

    /**
     * What the digest is computed from: one line {@code eventId|seq|type|redacted} per source event, in
     * {@code seq} order, joined by a line feed, with {@code redacted} 1 or 0. The JSON carries the same
     * lines' fields, so a reader recomputes it.
     */
    public static String digestInput(List<SourceEvent> source) {
        List<String> lines = new ArrayList<>();
        for (SourceEvent event : source) {
            lines.add(event.eventId() + "|" + event.seq() + "|" + event.type() + "|" + (event.redacted() ? 1 : 0));
        }
        return String.join("\n", lines);
    }

    /** The lower case hex SHA-256 of the text, UTF-8. */
    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /**
     * Builds the model.
     *
     * @param appeal  the appeal to protocol
     * @param appeals every appeal of the punishment, oldest first; the conversation outside an appeal's own
     *                messages belongs to the appeal whose filing precedes it
     * @param entries every event of the case with its actor's name, in any order
     */
    public static ProtocolModel build(ProtocolSource.Appeal appeal, List<ProtocolSource.Appeal> appeals, List<CaseReader.Entry> entries) {
        return build(appeal, appeals, entries, Map.of());
    }

    /**
     * The same, with the SHA-256 of the case's chat files by evidence id, which the marker line of a file shows in short. The hash is
     * not part of the digest of the source events.
     */
    public static ProtocolModel build(ProtocolSource.Appeal appeal, List<ProtocolSource.Appeal> appeals, List<CaseReader.Entry> entries,
                                      Map<String, String> fileDigests) {
        return new Builder(appeal, appeals, entries, fileDigests).build();
    }

    private static final class Builder {

        private final ProtocolSource.Appeal appeal;
        private final String appealId;
        private final long windowStart;
        private final long windowEnd;
        private final List<CaseReader.Entry> sorted;
        private final Map<String, String> fileDigests;

        Builder(ProtocolSource.Appeal appeal, List<ProtocolSource.Appeal> appeals, List<CaseReader.Entry> entries, Map<String, String> fileDigests) {
            this.fileDigests = fileDigests;
            this.appeal = appeal;
            this.appealId = appeal.identifier().toString();
            this.windowStart = appeal.createdAt();
            this.windowEnd = appeals.stream().filter(other -> !other.identifier().equals(appeal.identifier()))
                    .filter(other -> other.createdAt() > appeal.createdAt()
                            || (other.createdAt() == appeal.createdAt() && other.identifier().compareTo(appeal.identifier()) > 0))
                    .mapToLong(ProtocolSource.Appeal::createdAt).min().orElse(Long.MAX_VALUE);
            this.sorted = entries.stream().sorted(Comparator.comparingLong(entry -> entry.event().seq())).toList();
        }

        private boolean inWindow(long at) {
            return at >= this.windowStart && at < this.windowEnd;
        }

        ProtocolModel build() {
            Map<String, MessageBuilder> messages = new LinkedHashMap<>();
            List<Entry> lifecycle = new ArrayList<>();
            List<SourceEvent> source = new ArrayList<>();
            Ticket ticket = null;
            long lastActivity = 0;

            for (CaseReader.Entry entry : this.sorted) {
                CaseEventRecord event = entry.event();
                String type = event.type();
                if (type.equals(CaseEventPayload.MESSAGE_ADDED) && event.payload() instanceof CaseEventPayload.MessageAdded added) {
                    boolean mine = added.appealId() != null ? added.appealId().equals(this.appealId) : inWindow(event.occurredAt());
                    if (mine) {
                        messages.put(added.messageId(), new MessageBuilder(entry, added, this.fileDigests));
                        include(source, event);
                    }
                } else if (MESSAGE_FOLLOW_UPS.contains(type) && event.subjectId() != null && messages.containsKey(event.subjectId())) {
                    messages.get(event.subjectId()).apply(entry);
                    include(source, event);
                } else if (isLifecycle(event)) {
                    lifecycle.add(lifecycleEntry(entry));
                    include(source, event);
                    if (type.startsWith("ticket.")) {
                        ticket = ticketAfter(ticket, event);
                    }
                } else {
                    continue;
                }
                lastActivity = Math.max(lastActivity, event.occurredAt());
            }

            List<Entry> all = new ArrayList<>(lifecycle);
            messages.values().forEach(message -> all.add(message.build()));
            all.sort(Comparator.comparingLong(Entry::seq));
            source.sort(Comparator.comparingLong(SourceEvent::seq));
            return new ProtocolModel(this.appeal.identifier(), this.appeal.punishmentIdentifier(), this.appeal.status().name(),
                    this.appeal.decidedAt(), List.copyOf(all), List.copyOf(source), ticket, sha256(digestInput(source)), lastActivity);
        }

        private static void include(List<SourceEvent> source, CaseEventRecord event) {
            source.add(new SourceEvent(event.identifier(), event.seq(), event.type(), event.redactedAt() != null));
        }

        /** Whether the event is an appeal, Ban ID or ticket marker of this appeal's part of the case. */
        private boolean isLifecycle(CaseEventRecord event) {
            String type = event.type();
            if (type.startsWith("appeal.")) {
                return this.appealId.equals(appealIdOf(event));
            }
            if (type.startsWith("ticket.")) {
                String ticketAppeal = appealIdOf(event);
                return ticketAppeal != null ? this.appealId.equals(ticketAppeal) : inWindow(event.occurredAt());
            }
            if (type.equals(CaseEventPayload.EVIDENCE_SHARED) || type.equals(CaseEventPayload.EVIDENCE_SHARE_REVOKED)) {
                return inWindow(event.occurredAt());
            }
            return type.startsWith("ban_id.") && inWindow(event.occurredAt());
        }

        private static @Nullable String appealIdOf(CaseEventRecord event) {
            Object value = event.body().get("appealId");
            return value == null ? null : value.toString();
        }

        private static Ticket ticketAfter(@Nullable Ticket before, CaseEventRecord event) {
            if (event.type().equals("ticket.transcript_complete")) {
                return new Ticket(true, number(event.body().get("count")), text(event.body().get("lastMessageId")));
            }
            return before != null ? before : new Ticket(false, null, null);
        }

        private EventEntry lifecycleEntry(CaseReader.Entry entry) {
            CaseEventRecord event = entry.event();
            return new EventEntry(event.seq(), event.occurredAt(), event.type(), eventAuthor(entry), event.visibility(), details(event));
        }
    }

    private static final List<String> MESSAGE_FOLLOW_UPS = List.of(
            CaseEventPayload.MESSAGE_EDITED, CaseEventPayload.MESSAGE_DELETED, CaseEventPayload.MESSAGE_REDACTED);

    /** Folds the events of one message into an entry. */
    private static final class MessageBuilder {

        private final CaseReader.Entry first;
        private final CaseEventPayload.MessageAdded added;
        private final List<Text> texts = new ArrayList<>();
        private Long deletedAt;
        private boolean redacted;
        private final Map<String, String> fileDigests;

        MessageBuilder(CaseReader.Entry first, CaseEventPayload.MessageAdded added, Map<String, String> fileDigests) {
            this.fileDigests = fileDigests;
            this.first = first;
            this.added = added;
            CaseEventRecord event = first.event();
            this.texts.add(new Text(event.seq(), event.occurredAt(), added.text()));
            this.redacted = event.redactedAt() != null;
        }

        void apply(CaseReader.Entry entry) {
            CaseEventRecord event = entry.event();
            if (event.redactedAt() != null) {
                this.redacted = true;
            }
            switch (event.payload()) {
                case CaseEventPayload.MessageEdited edited -> this.texts.add(new Text(event.seq(), event.occurredAt(), edited.text()));
                case CaseEventPayload.MessageDeleted ignored -> this.deletedAt = event.occurredAt();
                case CaseEventPayload.MessageRedacted ignored -> this.redacted = true;
                default -> {
                }
            }
        }

        MessageEntry build() {
            CaseEventRecord event = this.first.event();
            List<Text> shown = this.redacted
                    ? this.texts.stream().map(text -> new Text(text.seq(), text.at(), null)).toList()
                    : List.copyOf(this.texts);
            String current = this.redacted ? null : this.texts.getLast().text();
            Author author = new Author(authorLabel(this.added.authorKind(), event.actorTrust(), this.first.actorName()),
                    this.added.authorKind().name(), event.actorTrust().name(), this.added.participantId());
            return new MessageEntry(event.seq(), event.occurredAt(), this.added.channel().name(), author, event.visibility(), current, shown,
                    this.deletedAt, this.redacted, attachments(event.body(), this.fileDigests), embeds(event.body()));
        }
    }

    /**
     * The label of a message author: a player under the case's pseudonym, verified staff by display name
     * (a removed name shows as anonymous), everyone else by their kind.
     */
    static String authorLabel(MessageAuthorKind kind, ActorTrust trust, @Nullable String name) {
        return switch (kind) {
            case PLAYER -> PLAYER_LABEL;
            case BOT -> "Bot";
            case EXTERNAL -> "External participant";
            case STAFF -> switch (trust) {
                case VERIFIED -> name != null && !name.isBlank() ? name : ANONYMOUS_STAFF_LABEL;
                case SYSTEM -> SYSTEM_LABEL;
                case PLAYER -> PLAYER_LABEL;
                case UNVERIFIED -> "Unverified staff";
            };
        };
    }

    private static Author eventAuthor(CaseReader.Entry entry) {
        CaseEventRecord event = entry.event();
        ActorTrust trust = event.actorTrust();
        String kind;
        String label;
        switch (trust) {
            case SYSTEM -> {
                kind = "SYSTEM";
                label = SYSTEM_LABEL;
            }
            case PLAYER -> {
                kind = "PLAYER";
                label = PLAYER_LABEL;
            }
            case VERIFIED -> {
                kind = "STAFF";
                label = entry.actorName() != null && !entry.actorName().isBlank() ? entry.actorName() : ANONYMOUS_STAFF_LABEL;
            }
            default -> {
                boolean attributed = event.actorSource() != null || event.recordedByOid() != null;
                kind = attributed ? "STAFF" : "UNATTRIBUTED";
                label = attributed ? "Unverified staff" : "Not attributed";
            }
        }
        return new Author(label, kind, trust.name(), null);
    }

    /** The whitelisted details of a lifecycle event: ids, enums and counts, never text or a person's identifier. */
    private static Map<String, Object> details(CaseEventRecord event) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> body = event.body();
        switch (event.type()) {
            case CaseEventPayload.APPEAL_FILED -> copy(details, body, "eligible");
            case CaseEventPayload.APPEAL_DECIDED -> copy(details, body, "decision", "newExpirationAt");
            case CaseEventPayload.BAN_ID_LEAK_MARKED -> copy(details, body, "reason", "source", "afterFiling");
            case CaseEventPayload.BAN_ID_REISSUED -> copy(details, body, "reason", "fromGeneration", "toGeneration");
            case "ticket.transcript_complete" -> copy(details, body, "count", "lastMessageId", "edits", "deletes");
            case CaseEventPayload.EVIDENCE_SHARED -> copy(details, body, "evidenceKind", "mode", "lineCount");
            case CaseEventPayload.EVIDENCE_SHARE_REVOKED -> copy(details, body, "reason");
            default -> {
            }
        }
        return details;
    }

    private static void copy(Map<String, Object> to, Map<String, Object> from, String... keys) {
        for (String key : keys) {
            Object value = from.get(key);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                to.put(key, value);
            }
        }
    }

    private static List<Attachment> attachments(Map<String, Object> body, Map<String, String> fileDigests) {
        List<Attachment> found = new ArrayList<>();
        if (body.get("attachments") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String attachmentId = text(map.get("attachmentId"));
                    String digest = attachmentId == null ? null : fileDigests.get(attachmentId);
                    found.add(new Attachment(text(first(map, "name", "fileName")), text(first(map, "contentType", "type")),
                            number(first(map, "size", "bytes")), attachmentId != null,
                            digest == null ? null : digest.substring(0, Math.min(12, digest.length())), text(map.get("provenance"))));
                }
            }
        }
        return List.copyOf(found);
    }

    private static List<Embed> embeds(Map<String, Object> body) {
        List<Embed> found = new ArrayList<>();
        if (body.get("embeds") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    found.add(new Embed(text(map.get("type")), text(map.get("host"))));
                }
            }
        }
        return List.copyOf(found);
    }

    private static @Nullable Object first(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    private static @Nullable String text(@Nullable Object value) {
        return value instanceof String || value instanceof Number ? value.toString() : null;
    }

    private static @Nullable Long number(@Nullable Object value) {
        return value instanceof Number number ? Long.valueOf(number.longValue()) : null;
    }
}
