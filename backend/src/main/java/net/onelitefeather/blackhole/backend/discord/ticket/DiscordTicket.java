package net.onelitefeather.blackhole.backend.discord.ticket;

import io.micronaut.core.annotation.Nullable;

import java.util.UUID;

/**
 * One Discord ticket as stored.
 *
 * @param ingestedCursor           the highest Discord message id recorded (0 when none)
 * @param relayedSeq               the highest case event {@code seq} relayed to the channel (0 when none)
 * @param transcriptCount          messages the channel held when the transcript was verified, {@code null} before
 * @param transcriptLastMessageId  the highest Discord message id then, {@code null} for an empty channel or before
 * @param transcriptCompleteAt     when {@code ticket.transcript_complete} was recorded, {@code null} before
 */
public record DiscordTicket(
        UUID id,
        long ticketNo,
        String punishmentIdentifier,
        UUID appealId,
        UUID ownerParticipantId,
        TicketState state,
        @Nullable String channelId,
        @Nullable String guildId,
        boolean frozen,
        long ingestedCursor,
        long relayedSeq,
        @Nullable Integer transcriptCount,
        @Nullable Long transcriptLastMessageId,
        @Nullable Long transcriptCompleteAt,
        long createdAt,
        long updatedAt
) {

    public boolean transcriptComplete() {
        return this.transcriptCompleteAt != null;
    }

    /** The part of the dedupe keys that names the channel: its id, or the ticket number while the channel is not reported yet. */
    public String channelKey() {
        return this.channelId != null ? this.channelId : "t" + this.ticketNo;
    }
}
