package net.onelitefeather.blackhole.backend.discord.ticket;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.MessageAuthorKind;
import net.onelitefeather.blackhole.backend.casetimeline.MessageChannel;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Where tickets, participants and message links live. Every method is one SQL statement (no
 * {@code @Transactional}, see CLAUDE.md): a change of a ticket and the case event that records it are one
 * data-modifying CTE, so the two never diverge.
 */
public interface DiscordTicketStore {

    /** A ticket about to be opened. */
    record NewTicket(UUID id, String punishmentIdentifier, UUID appealId, UUID ownerParticipantId, long now, UUID recordedByOid) {
    }

    /** What the backend counts for the completeness proof. */
    record Tally(int count, long lastMessageId, int edits, int deletes) {
    }

    /** One thing the bot must do in the channel to follow the case. */
    record OutboxItem(long seq, Kind kind, String messageId, @Nullable MessageAuthorKind authorKind, @Nullable MessageChannel channel,
                      @Nullable String text, @Nullable Long discordMessageId, long occurredAt, List<File> files) {

        /** A file to fetch and upload: the evidence id the file route takes, its name, type and size. */
        public record File(String attachmentId, String name, String contentType, long size) {
        }

        /** What to do. */
        @io.swagger.v3.oas.annotations.media.Schema(name = "DiscordOutboxItemKind")
        @io.micronaut.serde.annotation.Serdeable
        public enum Kind {
            /** Post a message. */
            ADD,
            /** Edit the Discord copy of a relayed message. */
            EDIT,
            /** Delete the Discord copy of a relayed message. */
            DELETE,
            /** Delete the Discord copy because the text was redacted. */
            REDACT,
            /** Post an evidence share: its excerpt text or its image file. */
            SHARE,
            /** Delete the Discord copy of a share and post that it was withdrawn. */
            SHARE_REVOKED
        }
    }

    /** The ticket already exists for this appeal and player (a concurrent open won). */
    final class DuplicateTicketException extends RuntimeException {
        public DuplicateTicketException(Throwable cause) {
            super("a live ticket exists for this appeal and player", cause);
        }
    }

    /** Another ticket already has this channel. */
    final class ChannelTakenException extends RuntimeException {
        public ChannelTakenException(Throwable cause) {
            super("another ticket has this channel", cause);
        }
    }

    // ---- participants ----

    /**
     * Finds or creates the participant for a Discord user in a case. An existing participant keeps its kind unless
     * {@code kind} is {@link ParticipantKind#PLAYER}, which wins (the user is the bound player of a ticket now).
     */
    Participant ensureParticipant(String punishmentIdentifier, ParticipantKind kind, @Nullable String discordUserId,
                                  @Nullable String displayName, boolean bot, long now);

    Optional<Participant> participant(UUID id);

    Optional<Participant> participantByDiscordUser(String punishmentIdentifier, String discordUserId);

    /**
     * Erases one participant: the Discord id and the name go, {@code erasedAt} is set.
     *
     * @return whether the participant existed in this case and was not erased yet
     */
    boolean eraseParticipant(String punishmentIdentifier, UUID participantId, long now);

    /**
     * Erases every {@link ParticipantKind#PLAYER} participant of the case (the player-side erase).
     *
     * @return how many were erased now
     */
    int erasePlayerParticipants(String punishmentIdentifier, long now);

    // ---- tickets ----

    Optional<DiscordTicket> ticket(UUID id);

    /** Tickets in any of the states, oldest first, at most {@code limit}. */
    List<DiscordTicket> tickets(Collection<TicketState> states, int limit);

    /** The tickets of the punishment that are pending, open or closing. */
    List<DiscordTicket> liveTicketsOf(String punishmentIdentifier);

    /**
     * Inserts the ticket as {@link TicketState#PENDING_CHANNEL} and appends {@code ticket.opened} in one statement.
     * The case row must exist.
     *
     * @throws DuplicateTicketException when the appeal already has a live ticket for the player
     */
    DiscordTicket open(NewTicket ticket);

    /**
     * Reports the channel: {@code PENDING_CHANNEL} becomes {@code OPEN}.
     *
     * @return the ticket, empty when it was not pending
     * @throws ChannelTakenException when another ticket has the channel
     */
    Optional<DiscordTicket> channelReady(UUID id, String channelId, @Nullable String guildId, long now);

    /**
     * Moves the ticket to {@code to} when it is in one of {@code from}. No case event (the events of closing, archiving and
     * deleting have their own methods).
     *
     * @return the ticket, empty when it was in another state
     */
    Optional<DiscordTicket> transition(UUID id, Set<TicketState> from, TicketState to, long now);

    /** Sets the freeze and its source ({@link DiscordTicket#FREEZE_LEAK}, {@link DiscordTicket#FREEZE_STAFF}, or {@code null} to thaw). */
    Optional<DiscordTicket> setFrozen(UUID id, boolean frozen, @Nullable String freezeSource, long now);

    /**
     * A staff lock ({@link DiscordTicket#FREEZE_STAFF}) or unlock, and the {@code ticket.frozen} case event, in one statement. Only a
     * change of the freeze is recorded; the caller checks the state first.
     */
    Optional<DiscordTicket> setStaffFrozen(UUID id, boolean frozen, long now, CaseActor actor);

    /** The newest ticket of the punishment, live or not, or empty when the case never had one. */
    Optional<DiscordTicket> latestOf(String punishmentIdentifier);

    /** {@code CLOSED} becomes {@code ARCHIVED} and {@code ticket.archived} is appended, in one statement. Empty when it was not closed. */
    Optional<DiscordTicket> archive(UUID id, int protocolVersion, long now, UUID recordedByOid);

    /**
     * Any state but {@code DELETED} becomes {@code DELETED} and {@code ticket.channel_deleted} is appended, in one statement,
     * with whether the transcript was verified and the time of the last recorded message. Empty when it was deleted already.
     */
    Optional<DiscordTicket> deleted(UUID id, long now, UUID recordedByOid);

    /**
     * Records the completeness proof in one statement: only when the ticket has none yet and the supplied count and last message id
     * equal the tally of inbound, not deleted messages. Appends {@code ticket.transcript_complete}, stores the transcript fields and
     * moves {@code OPEN}/{@code CLOSING} to {@code CLOSED}.
     *
     * @return whether it was recorded now
     */
    boolean complete(UUID id, int count, long lastMessageId, long now, UUID recordedByOid);

    /** The backend's own count of the channel's inbound messages. */
    Tally tally(UUID ticketId);

    // ---- relay ----

    /** The player-visible events to relay after {@code afterSeq}, in {@code seq} order, at most {@code limit}. */
    List<OutboxItem> outbox(UUID ticketId, long afterSeq, int limit);

    /**
     * Advances {@code relayed_seq} to at least {@code seq} and, when both ids are given, links the case message to the Discord
     * message the bot posted ({@code RELAYED}), in one statement.
     *
     * @return the ticket's {@code relayed_seq} afterwards, empty when there is no such ticket
     */
    Optional<Long> acknowledge(UUID ticketId, long seq, @Nullable UUID messageId, @Nullable Long discordMessageId, long now);
}
