package net.onelitefeather.blackhole.discord.backend;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
}
