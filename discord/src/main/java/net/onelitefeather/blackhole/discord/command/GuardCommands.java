package net.onelitefeather.blackhole.discord.command;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.GuardOutcome;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.StaffLink;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * {@code /guard <level>}: the logic behind the slash command, free of any Discord type so it is tested with a fake backend.
 * Only a caller with the staff Discord role gets past the first check. The level is then set for the staff member the Discord
 * account is linked to, and the backend decides whether that member may act. The answer names the level in force.
 */
public final class GuardCommands {

    private static final List<String> LEVELS = List.of("OFF", "STRICT", "LOCKDOWN");

    private final BackendGateway backend;

    public GuardCommands(@NotNull BackendGateway backend) {
        this.backend = backend;
    }

    /**
     * @param lang          the language of the reply
     * @param discordUserId the user who ran the command
     * @param staffRole     whether that user holds the staff Discord role
     * @param level         what the user chose, possibly null
     * @return the ephemeral reply
     */
    public @NotNull String set(@NotNull Lang lang, @NotNull String discordUserId, boolean staffRole, @Nullable String level) {
        if (!staffRole) {
            return Messages.guardStaffOnly(lang);
        }
        String chosen = level == null ? "" : level.strip().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(chosen)) {
            return Messages.guardUsage(lang);
        }
        StaffLink link = this.backend.staffLink(discordUserId);
        if (link instanceof StaffLink.NotLinked) {
            return Messages.guardLinkHint(lang);
        }
        if (link instanceof StaffLink.Unavailable) {
            return Messages.unavailable(lang);
        }
        // A linked member whose role snapshot is stale is not refused here: the write goes out, and the backend names the reason.
        String staffOid = ((StaffLink.Linked) link).oid();
        return switch (this.backend.setGuardLevel(chosen, staffOid)) {
            case GuardOutcome.Applied applied -> Messages.guardApplied(lang, applied.manualLevel(), applied.effectiveLevel());
            case GuardOutcome.Refused refused -> refusal(lang, refused);
            case GuardOutcome.Unavailable ignored -> Messages.unavailable(lang);
        };
    }

    private static String refusal(Lang lang, GuardOutcome.Refused refused) {
        String text = refused.text() == null ? "" : refused.text();
        if (text.startsWith("STAFF_ROLE_STALE")) {
            return Messages.guardSignIn(lang);
        }
        if (text.startsWith("STAFF_NOT_ELIGIBLE")) {
            return Messages.guardNotEligible(lang);
        }
        return Messages.guardTryAgain(lang);
    }
}
