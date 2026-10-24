package net.onelitefeather.blackhole.backend.discord.ingest;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventDraft;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The writes of the Discord ingest. Each call is <b>one statement</b> that appends the case event, writes the message link and advances
 * the ticket's cursor together, so a crash between them cannot leave a recorded message without its link or the other way round.
 */
public interface DiscordIngestStore {

    /**
     * What is known about a Discord message of a ticket.
     *
     * @param messageId the case message ({@code subject_id} of its events)
     * @param direction {@code INBOUND} or {@code RELAYED}
     * @param textHash  the hash of the latest known text of an inbound message
     * @param deleted   whether the Discord message is gone
     */
    record Link(UUID messageId, long discordMessageId, String direction, @Nullable String textHash, boolean deleted) {
    }

    Optional<Link> link(UUID ticketId, long discordMessageId);

    /**
     * Records a message written in the channel: its {@code message.added}, its link and the cursor, and moves a {@code WAITING} case
     * to {@code IN_REVIEW} when {@code promoteWaiting} is set (a player wrote).
     *
     * @return the appended events, the message first; empty when the message was recorded before
     */
    List<CaseChanges.Appended> add(UUID ticketId, long discordMessageId, String textHash, CaseEventDraft message, boolean promoteWaiting, long now);

    /**
     * Records an edit when the text differs from the latest known: updates the link's hash and appends {@code message.edited}, which
     * keeps the author and visibility of the original. Nothing happens for an unknown, deleted or redacted message or an equal text.
     */
    Optional<CaseChanges.Appended> edit(UUID ticketId, String punishmentIdentifier, long discordMessageId, String textHash, String text,
                                        long occurredAt, String dedupeKey);

    /**
     * Records the deletion of a known message: marks the link and appends {@code message.deleted}, which keeps the last text readable.
     * Nothing happens when it was deleted before.
     */
    Optional<CaseChanges.Appended> delete(UUID ticketId, String punishmentIdentifier, long discordMessageId, long occurredAt,
                                          boolean detectedAtCatchup, String dedupeKey);

    /**
     * Records the deletion of a message that was never recorded: a link marked deleted and a {@code message.deleted} flagged
     * {@code contentUnavailable}, staff visibility.
     */
    Optional<CaseChanges.Appended> deleteUnknown(UUID ticketId, String punishmentIdentifier, long discordMessageId, UUID messageId,
                                                 long occurredAt, String dedupeKey, UUID recordedByOid);

    /** The inbound, not deleted messages of the ticket whose Discord id is not in {@code present} (deleted while the bot was down). */
    List<Long> missing(UUID ticketId, long[] present);
}
