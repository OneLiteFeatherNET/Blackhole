package net.onelitefeather.blackhole.backend.inbox;

/** The four sections of the inbox, in the order the page shows them. */
public enum InboxSection {
    /** Appeal chats whose last visible message is from the player. */
    NEEDS_ANSWER,
    /** Pending appeals without a decision. */
    OPEN_APPEALS,
    /** Reports that are still open. */
    NEW_REPORTS,
    /** Open cases without an assignee. */
    UNASSIGNED_CASES
}
