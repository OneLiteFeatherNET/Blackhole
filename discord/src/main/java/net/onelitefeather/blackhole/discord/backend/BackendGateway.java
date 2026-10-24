package net.onelitefeather.blackhole.discord.backend;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.onelitefeather.blackhole.discord.backend.Tickets.*;

import java.util.Collection;
import java.util.List;

/**
 * The backend calls the bot needs, in the bot's terms. The commands depend on this interface, not on
 * the generated client, so they are tested with a fake and never reach a network. Later phases
 * (notifications, tickets) add their calls here.
 */
public interface BackendGateway {

    /** Whose account a link belongs to. */
    enum LinkKind {
        STAFF, PLAYER
    }

    /** One link of a Discord user. */
    record LinkEntry(@NotNull LinkKind kind, @Nullable String displayName) {
    }

    /** How a code confirmation ended. */
    sealed interface LinkResult {
        /** @param displayName the staff member's name, only for a staff link and if the backend knows it */
        record Linked(@NotNull LinkKind kind, @Nullable String displayName) implements LinkResult {
        }

        /** The code is wrong, expired or used; the backend does not say which. */
        record InvalidCode() implements LinkResult {
        }

        /** The backend refused or could not be reached; {@code status} is the HTTP status or 0. */
        record Unavailable(int status) implements LinkResult {
        }
    }

    /** What a who-am-i lookup found. */
    sealed interface WhoamiResult {
        /** @param links the links of the Discord user, possibly none */
        record Found(@NotNull List<LinkEntry> links) implements WhoamiResult {
        }

        record Unavailable(int status) implements WhoamiResult {
        }
    }

    /** How an unlink ended. */
    sealed interface UnlinkResult {
        /** @param removed how many links were removed */
        record Removed(int removed) implements UnlinkResult {
        }

        record NotLinked() implements UnlinkResult {
        }

        record Unavailable(int status) implements UnlinkResult {
        }
    }

    /** Which kind of item a team notification is about. */
    enum NotificationKind {
        REPORT, APPEAL
    }

    /**
     * The display facts of a report or an appeal. Never a raw UUID or a Ban ID.
     *
     * @param kind                 report or appeal
     * @param identifier           the report or appeal
     * @param punishmentIdentifier the appealed punishment, only for an appeal (for the case link)
     * @param playerName           the player's name when one was recorded
     * @param playerRef            a short pseudonymous reference, shown when there is no name
     * @param topic                the report category or the appeal's section
     * @param text                 the shortened description or statement
     * @param status               the backend status such as OPEN, ACTIONED or DENIED
     * @param createdAt            epoch millis
     * @param updatedAt            epoch millis
     */
    record Notification(
            @NotNull NotificationKind kind, @NotNull String identifier, @Nullable String punishmentIdentifier,
            @Nullable String playerName, @NotNull String playerRef, @NotNull String topic, @Nullable String text,
            @NotNull String status, long createdAt, long updatedAt
    ) {
    }

    /** What a notification lookup found. */
    sealed interface NotificationResult {
        record Found(@NotNull Notification notification) implements NotificationResult {
        }

        record NotFound() implements NotificationResult {
        }

        record Unavailable(int status) implements NotificationResult {
        }
    }

    /** @return the facts for the embed of a report */
    @NotNull NotificationResult report(@NotNull String identifier);

    /** @return the facts for the embed of an appeal */
    @NotNull NotificationResult appeal(@NotNull String identifier);

    /**
     * Confirms the code the Discord user typed. Never throws for an expected failure.
     *
     * @param code          as typed; the backend normalises it
     * @param discordUserId the Discord user who typed it
     */
    @NotNull LinkResult confirmLink(@NotNull String code, @NotNull String discordUserId);

    /** @return what the Discord user is linked to */
    @NotNull WhoamiResult whoami(@NotNull String discordUserId);

    /** Removes every link of the Discord user. */
    @NotNull UnlinkResult unlink(@NotNull String discordUserId);

    // ---- tickets (group 3) ----

    /**
     * Opens or finds the ticket of a player. The Ban ID is passed through and never kept or logged. Every failure of
     * eligibility is the same {@code NOT_ELIGIBLE} answer.
     */
    @NotNull Result<Opened> openTicket(@NotNull String discordUserId, @Nullable String displayName, @NotNull String banId, @Nullable String statement);

    /** @return the tickets in any of the states, oldest first */
    @NotNull Result<List<Ticket>> tickets(@NotNull Collection<State> states);

    @NotNull Result<Ticket> ticket(@NotNull String id);

    /** Reports a change: the channel, a state, the freeze mirror or a channel that is gone. */
    @NotNull Result<Ticket> patchTicket(@NotNull String id, @NotNull Patch patch);

    /** @return the player-visible events after {@code afterSeq}, with their text, for relaying */
    @NotNull Result<Outbox> outbox(@NotNull String id, long afterSeq, int limit);

    /**
     * Acknowledges a relayed event; advances the ticket's relay cursor.
     *
     * @param messageId        the case message, only for an add
     * @param discordMessageId the Discord copy, only for an add
     * @return the cursor afterwards
     */
    @NotNull Result<Long> linkRelay(@NotNull String id, long seq, @Nullable String messageId, @Nullable String discordMessageId);

    /** Records messages in their current state (idempotent), or deletes. */
    @NotNull Result<Ingested> ingest(@NotNull String id, @NotNull IngestBatch batch);

    /** The completeness proof of a closing ticket; stores the protocol and answers its version. */
    @NotNull Result<Closed> close(@NotNull String id, int count, @Nullable String lastMessageId);

    /** Makes sure a current protocol is stored; the gate before a channel is deleted. */
    @NotNull Result<ProtocolEnsured> ensureProtocol(@NotNull String id);

    /** @return the Discord user ids of the live staff links, for the channel access reconcile */
    @NotNull Result<List<String>> staffLinks();
}
