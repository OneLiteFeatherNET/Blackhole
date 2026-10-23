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
