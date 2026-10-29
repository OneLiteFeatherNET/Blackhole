package net.onelitefeather.blackhole.discord.ticket;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;

/**
 * The notices the bot posts into a ticket channel. A channel has a player and staff of unknown language, so each
 * notice is English first and German second. None of them carries a Ban ID or a name.
 */
final class ChannelTexts {

    private ChannelTexts() {
    }

    static @NotNull String welcome() {
        return "**Your appeal.** Everything written in this channel, in the player portal and by the moderation team is kept "
                + "as one conversation and stored with the case. Staff-internal talk belongs in the panel, not here. "
                + "Staff see this channel only with a linked Discord account (`/link`).\n"
                + "**Dein Einspruch.** Alles, was in diesem Kanal, im Spielerportal und vom Moderationsteam geschrieben wird, "
                + "bleibt eine gemeinsame Unterhaltung und wird mit dem Fall gespeichert. Interne Absprachen des Teams gehören "
                + "ins Panel, nicht hierher. Das Team sieht diesen Kanal nur mit verknüpftem Discord-Account (`/link`).";
    }

    static @NotNull String frozen() {
        return "This channel is frozen: the ban code of this case was published, so the player side is closed. The team can still write.\n"
                + "Dieser Kanal ist eingefroren: Der Bancode dieses Falls wurde veröffentlicht, die Spielerseite ist geschlossen. "
                + "Das Team kann weiter schreiben.";
    }

    static @NotNull String thawed() {
        return "This channel is open again.\nDieser Kanal ist wieder geöffnet.";
    }

    static @NotNull String staffLocked() {
        return "This channel is locked by the moderation team. The player side is closed; the team can still write.\n"
                + "Dieser Kanal wurde vom Moderationsteam gesperrt. Die Spielerseite ist geschlossen; das Team kann weiter schreiben.";
    }

    static @NotNull String staffUnlocked() {
        return "The moderation team unlocked this channel.\nDas Moderationsteam hat die Sperre dieses Kanals aufgehoben.";
    }

    static @NotNull String archived(@NotNull Duration deleteAfter, @NotNull Instant now) {
        if (deleteAfter.isZero()) {
            return "This appeal is decided and archived. The conversation is stored with the case.\n"
                    + "Dieser Einspruch ist entschieden und archiviert. Die Unterhaltung ist beim Fall gespeichert.";
        }
        long seconds = now.plus(deleteAfter).getEpochSecond();
        return "This appeal is decided and archived. The channel is deleted on <t:" + seconds + ":D>; the conversation stays stored with the case.\n"
                + "Dieser Einspruch ist entschieden und archiviert. Der Kanal wird am <t:" + seconds + ":D> gelöscht; die Unterhaltung "
                + "bleibt beim Fall gespeichert.";
    }
}
