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
}
