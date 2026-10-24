package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.core.annotation.Nullable;

import java.util.List;

/**
 * The read side of the player's conversation. The visibility rule and the type allow-list are part of the
 * query ({@code CaseEventPayload.PORTAL_VISIBLE_TYPES}, {@code PORTAL_ANY_VISIBILITY_TYPES}), never of a
 * mapper, so a row a player must not see does not leave the database.
 */
public interface PublicConversationReader {

    /**
     * One entry of the conversation as the portal shows it.
     *
     * @param kind      {@code MESSAGE}, or a marker: {@code APPEAL_FILED}, {@code APPEAL_DECIDED}, {@code FROZEN}, {@code FORFEITED}
     * @param author    {@code PLAYER}, {@code STAFF} (never a name) or {@code SYSTEM}
     * @param messageId the message, the same across its edits; null for a marker
     * @param text      the current text with every edit folded in; null for a marker and for a removed message
     * @param removed   whether the message was deleted or redacted
     * @param attachmentsJson the JSON array of the chat files the message sent (from its first version); null when none, or removed
     * @param share     for {@code SHARE} and {@code SHARE_WITHDRAWN}: the share as it stands now; null otherwise
     */
    record Entry(long seq, long occurredAt, String kind, String author, @Nullable String messageId, @Nullable String text, boolean removed,
                 @Nullable String attachmentsJson, @Nullable Share share) {

        /** Shares an entry without files or a share card. */
        public Entry(long seq, long occurredAt, String kind, String author, @Nullable String messageId, @Nullable String text, boolean removed) {
            this(seq, occurredAt, kind, author, messageId, text, removed, null, null);
        }
    }

    /**
     * A share as the player may see it. The excerpt lines are not here: they are rendered from {@code evidencePayload} by the service.
     *
     * @param shareId         the share id
     * @param mode            {@code FILE} or {@code EXCERPT}; null when the share row is gone
     * @param withdrawn       whether the share is revoked (or the entry is its withdrawal)
     * @param evidencePresent whether the evidence item still exists
     * @param selection       the selected line indexes of an excerpt, in order
     * @param appellant       the speaker shown as "You"
     * @param evidencePayload the evidence payload (JSON) the excerpt is rendered from; null when absent
     */
    record Share(String shareId, @Nullable String mode, boolean withdrawn, boolean evidencePresent, List<Integer> selection,
                 @Nullable String appellant, @Nullable String evidencePayload) {
    }

    /**
     * A page of entries.
     *
     * @param cursor the last {@code seq} read, to pass next time; the cursor that was passed when nothing is new; null while nothing was ever read
     */
    record Page(List<Entry> entries, @Nullable Long cursor) {
    }

    /**
     * Reads the entries of one punishment's case after a cursor, in {@code seq} order.
     *
     * @param afterSeq the last {@code seq} the caller has seen, or null for the start
     * @param limit    at most this many rows are read
     */
    Page read(String punishmentIdentifier, @Nullable Long afterSeq, int limit);
}
