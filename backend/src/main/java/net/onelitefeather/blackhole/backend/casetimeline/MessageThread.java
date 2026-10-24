package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds the message events of a case into messages. The current text of a message is the latest
 * {@code message.added} or {@code message.edited} of its id; a {@code message.deleted} marks it
 * deleted without changing any row, and a redaction (a redacted row or a {@code message.redacted}
 * event) takes the text away from every version.
 */
public final class MessageThread {

    /**
     * One message as the thread sees it.
     *
     * @param first       its {@code message.added} event (author, channel, visibility, trust and time of the message)
     * @param text        the current text, {@code null} when redacted
     * @param edits       how many {@code message.edited} events it has
     * @param deleted     whether a {@code message.deleted} follows
     * @param redacted    whether the text was erased for data protection
     * @param authorKind  who wrote it
     */
    public record Message(CaseEventRecord first, @Nullable String text, int edits, boolean deleted, boolean redacted, MessageAuthorKind authorKind) {

        public String messageId() {
            return this.first.subjectId();
        }

        /** The visibility of the message and of every event about it. */
        public String visibility() {
            return this.first.visibility();
        }
    }

    private MessageThread() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** Whether the event type belongs to a message. */
    public static boolean isMessageEvent(String type) {
        return type.equals(CaseEventPayload.MESSAGE_ADDED) || type.equals(CaseEventPayload.MESSAGE_EDITED)
                || type.equals(CaseEventPayload.MESSAGE_DELETED) || type.equals(CaseEventPayload.MESSAGE_REDACTED);
    }

    /**
     * The messages of the given events, in the order their {@code message.added} came. Events of other
     * types are ignored; events of a message whose {@code message.added} is missing are ignored too.
     *
     * @param events events in timeline order
     */
    public static List<Message> of(List<CaseEventRecord> events) {
        Map<String, Builder> byId = new LinkedHashMap<>();
        for (CaseEventRecord event : events) {
            if (event.subjectId() == null || !isMessageEvent(event.type())) {
                continue;
            }
            if (event.type().equals(CaseEventPayload.MESSAGE_ADDED)) {
                byId.computeIfAbsent(event.subjectId(), id -> new Builder(event)).apply(event);
            } else if (byId.containsKey(event.subjectId())) {
                byId.get(event.subjectId()).apply(event);
            }
        }
        List<Message> messages = new ArrayList<>();
        byId.values().forEach(builder -> messages.add(builder.build()));
        return messages;
    }

    private static final class Builder {

        private final CaseEventRecord first;
        private MessageAuthorKind authorKind = MessageAuthorKind.EXTERNAL;
        private String text;
        private int edits;
        private boolean deleted;
        private boolean redacted;

        Builder(CaseEventRecord first) {
            this.first = first;
        }

        void apply(CaseEventRecord event) {
            if (event.redactedAt() != null) {
                this.redacted = true;
            }
            switch (event.payload()) {
                case CaseEventPayload.MessageAdded added -> {
                    this.authorKind = added.authorKind();
                    this.text = added.text();
                }
                case CaseEventPayload.MessageEdited edited -> {
                    this.edits++;
                    this.text = edited.text();
                }
                case CaseEventPayload.MessageDeleted ignored -> this.deleted = true;
                case CaseEventPayload.MessageRedacted ignored -> this.redacted = true;
                default -> {
                }
            }
        }

        Message build() {
            return new Message(this.first, this.redacted ? null : this.text, this.edits, this.deleted, this.redacted, this.authorKind);
        }
    }
}
