package net.onelitefeather.blackhole.discord.command;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.LinkKind;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.LinkResult;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.UnlinkResult;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.WhoamiResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /link <code>}, {@code /unlink} and {@code /whoami}: the logic behind the slash commands, free of
 * any Discord type so it is tested with a fake backend. Each method answers with the text to show the
 * user (always ephemeral) and never throws for an expected failure.
 */
public final class AccountCommands {

    private final BackendGateway backend;

    public AccountCommands(@NotNull BackendGateway backend) {
        this.backend = backend;
    }

    /**
     * @param lang          the language of the reply
     * @param discordUserId the user who ran the command
     * @param code          what the user typed, possibly blank or null
     */
    public @NotNull String link(@NotNull Lang lang, @NotNull String discordUserId, @Nullable String code) {
        if (code == null || code.isBlank()) {
            return Messages.missingCode(lang);
        }
        return switch (this.backend.confirmLink(code.strip(), discordUserId)) {
            case LinkResult.Linked linked -> linked.kind() == LinkKind.PLAYER
                    ? Messages.linkedPlayer(lang)
                    : Messages.linkedStaff(lang, linked.displayName());
            case LinkResult.InvalidCode ignored -> Messages.invalidCode(lang);
            case LinkResult.Unavailable ignored -> Messages.unavailable(lang);
        };
    }

    public @NotNull String unlink(@NotNull Lang lang, @NotNull String discordUserId) {
        return switch (this.backend.unlink(discordUserId)) {
            case UnlinkResult.Removed ignored -> Messages.unlinked(lang);
            case UnlinkResult.NotLinked ignored -> Messages.notLinked(lang);
            case UnlinkResult.Unavailable ignored -> Messages.unavailable(lang);
        };
    }

    public @NotNull String whoami(@NotNull Lang lang, @NotNull String discordUserId) {
        return switch (this.backend.whoami(discordUserId)) {
            case WhoamiResult.Found found -> found.links().isEmpty()
                    ? Messages.whoamiNone(lang)
                    : Messages.whoami(lang, found.links());
            case WhoamiResult.Unavailable ignored -> Messages.unavailable(lang);
        };
    }
}
