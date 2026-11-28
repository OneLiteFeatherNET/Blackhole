package net.onelitefeather.blackhole.backend.inbox;

import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;

/**
 * What another staff member changed last. A fixed value, never free text: a note or a message is only ever {@link #NOTE}
 * or {@link #MESSAGE}. Every case event type without its own value is {@link #OTHER}.
 */
public enum InboxChange {
    STATUS,
    ASSIGNEE,
    PRIORITY,
    TAGS,
    NOTE,
    MESSAGE,
    APPEAL_FILED,
    APPEAL_DECIDED,
    OTHER;

    /** The change value of a case event type. */
    public static InboxChange of(String type) {
        return switch (type) {
            case CaseEventPayload.STATUS_CHANGED -> STATUS;
            case CaseEventPayload.ASSIGNEE_CHANGED -> ASSIGNEE;
            case CaseEventPayload.PRIORITY_CHANGED -> PRIORITY;
            case CaseEventPayload.TAGS_CHANGED -> TAGS;
            case CaseEventPayload.NOTE_ADDED, CaseEventPayload.NOTE_EDITED, CaseEventPayload.NOTE_REDACTED -> NOTE;
            case CaseEventPayload.MESSAGE_ADDED, CaseEventPayload.MESSAGE_EDITED, CaseEventPayload.MESSAGE_DELETED,
                 CaseEventPayload.MESSAGE_REDACTED -> MESSAGE;
            case CaseEventPayload.APPEAL_FILED -> APPEAL_FILED;
            case CaseEventPayload.APPEAL_DECIDED -> APPEAL_DECIDED;
            default -> OTHER;
        };
    }
}
