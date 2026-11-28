package net.onelitefeather.blackhole.discord.command;

import net.onelitefeather.blackhole.discord.backend.BackendGateway.LinkEntry;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.LinkKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Every reply text of the account commands, English first and German second. Replies are ephemeral, so
 * a staff member's name is only shown to that person.
 */
final class Messages {

    private Messages() {
    }

    static @NotNull String linkedStaff(@NotNull Lang lang, @Nullable String displayName) {
        return switch (lang) {
            case EN -> displayName == null
                    ? "Your Discord account is now linked to your staff account."
                    : "Your Discord account is now linked to the staff account **" + displayName + "**.";
            case DE -> displayName == null
                    ? "Dein Discord-Account ist jetzt mit deinem Team-Account verknüpft."
                    : "Dein Discord-Account ist jetzt mit dem Team-Account **" + displayName + "** verknüpft.";
        };
    }

    static @NotNull String linkedPlayer(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "Your Discord account is now linked to your Minecraft account.";
            case DE -> "Dein Discord-Account ist jetzt mit deinem Minecraft-Account verknüpft.";
        };
    }

    static @NotNull String invalidCode(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "That code is invalid or has expired. Staff get a new code with \"Link Discord\" in the admin panel, "
                    + "players with `/blackhole discord` in game.";
            case DE -> "Der Code ist ungültig oder abgelaufen. Das Team erzeugt einen neuen Code mit \"Discord verknüpfen\" im "
                    + "Admin-Panel, Spieler mit `/blackhole discord` im Spiel.";
        };
    }

    static @NotNull String missingCode(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "Please enter your link code, for example `/link ABCD-EFGH`.";
            case DE -> "Bitte gib deinen Code an, zum Beispiel `/link ABCD-EFGH`.";
        };
    }

    static @NotNull String unavailable(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "The ban system is not reachable right now. Please try again later.";
            case DE -> "Das Bannsystem ist gerade nicht erreichbar. Bitte versuche es später erneut.";
        };
    }

    static @NotNull String unlinked(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "Your Discord link was removed.";
            case DE -> "Deine Discord-Verknüpfung wurde entfernt.";
        };
    }

    static @NotNull String notLinked(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "This Discord account is not linked.";
            case DE -> "Dieser Discord-Account ist nicht verknüpft.";
        };
    }

    static @NotNull String whoamiNone(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "This Discord account is not linked. Staff use \"Link Discord\" in the admin panel, players `/blackhole discord` in game, "
                    + "then `/link <code>` here.";
            case DE -> "Dieser Discord-Account ist nicht verknüpft. Das Team nutzt \"Discord verknüpfen\" im Admin-Panel, Spieler "
                    + "`/blackhole discord` im Spiel, danach `/link <Code>` hier.";
        };
    }

    static @NotNull String whoami(@NotNull Lang lang, @NotNull List<LinkEntry> links) {
        StringBuilder text = new StringBuilder(lang == Lang.DE ? "Dein Discord-Account ist verknüpft als:" : "Your Discord account is linked as:");
        for (LinkEntry link : links) {
            text.append("\n- ");
            if (link.kind() == LinkKind.STAFF) {
                String name = link.displayName() == null ? "" : " **" + link.displayName() + "**";
                text.append(lang == Lang.DE ? "Team-Mitglied" : "staff member").append(name);
            } else {
                text.append(lang == Lang.DE ? "Spieler (Minecraft-Account)" : "player (Minecraft account)");
            }
        }
        return text.toString();
    }

    static @NotNull String guildOnly(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "Please use this command in the server.";
            case DE -> "Bitte nutze diesen Befehl auf dem Server.";
        };
    }

    // ---- appeal tickets ----

    static @NotNull String appealMissingBanId(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "Please enter the ban ID from your ban screen.";
            case DE -> "Bitte gib die Ban-ID von deinem Bannbildschirm ein.";
        };
    }

    /** One text for every reason a ticket cannot be opened: a wrong ID, a leaked ID, an ID bound to someone else. */
    static @NotNull String appealNotEligible(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "We could not open an appeal with these details. Check the ban ID on your ban screen and try again. "
                    + "If the ban ID was ever posted publicly, you cannot appeal with it here.";
            case DE -> "Mit diesen Angaben konnte kein Einspruch eröffnet werden. Prüfe die Ban-ID auf deinem Bannbildschirm und "
                    + "versuche es erneut. Wurde die Ban-ID jemals öffentlich gepostet, kannst du damit hier keinen Einspruch einlegen.";
        };
    }

    static @NotNull String appealStatementRequired(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "Please write a statement: why should your ban be reviewed? Then send the form again.";
            case DE -> "Bitte schreibe eine Begründung: Warum soll dein Bann überprüft werden? Sende das Formular dann erneut.";
        };
    }

    static @NotNull String appealNotYet(@NotNull Lang lang, long availableFromMillis) {
        long seconds = availableFromMillis / 1000;
        return switch (lang) {
            case EN -> "You can appeal again from <t:" + seconds + ":F>.";
            case DE -> "Du kannst ab <t:" + seconds + ":F> erneut Einspruch einlegen.";
        };
    }

    static @NotNull String appealOpened(@NotNull Lang lang, long channelId, boolean created) {
        return switch (lang) {
            case EN -> (created ? "Your appeal is open. " : "You already have an open appeal. ") + "Continue in <#" + channelId + ">.";
            case DE -> (created ? "Dein Einspruch ist eröffnet. " : "Du hast bereits einen offenen Einspruch. ") + "Weiter geht es in <#" + channelId + ">.";
        };
    }

    static @NotNull String appealPreparing(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "Your appeal is open. Your private channel is being prepared and appears in a moment; if it does not, run `/appeal` again.";
            case DE -> "Dein Einspruch ist eröffnet. Dein privater Kanal wird gerade vorbereitet und erscheint gleich; falls nicht, nutze `/appeal` noch einmal.";
        };
    }

    static @NotNull String appealModalTitle(@NotNull Lang lang) {
        return lang == Lang.DE ? "Einspruch einlegen" : "Appeal a ban";
    }

    static @NotNull String appealBanIdLabel(@NotNull Lang lang) {
        return lang == Lang.DE ? "Ban-ID (von deinem Bannbildschirm)" : "Ban ID (from your ban screen)";
    }

    static @NotNull String appealStatementLabel(@NotNull Lang lang) {
        return lang == Lang.DE ? "Deine Begründung" : "Your statement";
    }

    static @NotNull String appealPanelTitle() {
        return "Appeal a ban · Einspruch einlegen";
    }

    static @NotNull String appealPanelBody() {
        return "Press the button and enter the ban ID from your ban screen. You get a private channel where you and the team can talk.\n"
                + "Drücke den Knopf und gib die Ban-ID von deinem Bannbildschirm ein. Du bekommst einen privaten Kanal, in dem du und das Team schreiben können.";
    }

    static @NotNull String appealPanelButton() {
        return "Appeal · Einspruch";
    }

    // ---- /guard ----

    static @NotNull String guardStaffOnly(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "Only the moderation team can set the join guard.";
            case DE -> "Nur das Moderationsteam kann den Join-Guard setzen.";
        };
    }

    static @NotNull String guardUsage(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "The level must be OFF, STRICT or LOCKDOWN.";
            case DE -> "Die Stufe muss OFF, STRICT oder LOCKDOWN sein.";
        };
    }

    static @NotNull String guardLinkHint(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "Your Discord account is not linked to a moderator account. Run /link with a code from the panel, then try again.";
            case DE -> "Dein Discord-Account ist mit keinem Moderator-Account verknüpft. Verknüpfe ihn mit /link und einem Code aus dem Panel "
                    + "und versuche es dann erneut.";
        };
    }

    static @NotNull String guardApplied(@NotNull Lang lang, @NotNull String manual, @NotNull String effective) {
        return switch (lang) {
            case EN -> "Join guard set to **" + manual + "**. Effective level now: **" + effective + "**.";
            case DE -> "Join-Guard auf **" + manual + "** gesetzt. Jetzt wirksam: **" + effective + "**.";
        };
    }

    static @NotNull String guardSignIn(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "Your moderator role is not recent enough for Discord. Sign in to the team panel once, then try again.";
            case DE -> "Deine Moderator-Rolle ist für Discord nicht mehr aktuell. Melde dich einmal im Team-Panel an und versuche es dann erneut.";
        };
    }

    static @NotNull String guardNotEligible(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "Your staff account does not hold the moderator or admin role, so the join guard stays as it is.";
            case DE -> "Dein Team-Account hat weder die Moderator- noch die Admin-Rolle, der Join-Guard bleibt unverändert.";
        };
    }

    static @NotNull String guardTryAgain(@NotNull Lang lang) {
        return switch (lang) {
            case EN -> "The level could not be set. Try again.";
            case DE -> "Die Stufe konnte nicht gesetzt werden. Bitte versuche es erneut.";
        };
    }
}
