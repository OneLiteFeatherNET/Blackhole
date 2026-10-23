package net.onelitefeather.blackhole.discord.notify;

import net.onelitefeather.blackhole.discord.backend.BackendGateway.Notification;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.NotificationKind;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Field;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MessageSpec;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the facts of a report or an appeal into the team message. The team channel is shared, so every
 * label is English first and German second. The message shows a name or a short pseudonymous reference,
 * a category or section, a short reason and the time; never a raw UUID, a full hash or a Ban ID. The
 * footer ends with a marker ({@code ref:report:<id>}) by which the bot finds its message again.
 */
public final class EmbedRenderer {

    static final int OPEN_COLOR = 0xF9A825;
    static final int DONE_COLOR = 0x2E7D32;
    static final int REJECTED_COLOR = 0xC62828;

    private final String panelUrl;
    private final long staffRoleId;

    /**
     * @param panelUrl    the admin panel base URL without a trailing slash
     * @param staffRoleId the role pinged for an item that needs attention
     */
    public EmbedRenderer(@NotNull String panelUrl, long staffRoleId) {
        this.panelUrl = panelUrl;
        this.staffRoleId = staffRoleId;
    }

    /** @return the footer marker that identifies the message of this item */
    public static @NotNull String marker(@NotNull NotificationKind kind, @NotNull String identifier) {
        return "ref:" + kind.name().toLowerCase(Locale.ROOT) + ":" + identifier;
    }

    /** @return whether the item still needs the team's attention */
    public static boolean open(@NotNull Notification n) {
        return switch (n.status()) {
            case "OPEN", "UNDER_REVIEW", "SUBMITTED", "ELIGIBLE_PENDING_REVIEW", "IN_REVIEW" -> true;
            default -> false;
        };
    }

    public @NotNull MessageSpec render(@NotNull Notification n) {
        boolean open = open(n);
        boolean report = n.kind() == NotificationKind.REPORT;
        String title;
        if (open) {
            title = report ? "New report · Neue Meldung" : "New appeal · Neuer Einspruch";
        } else {
            title = (report ? "Report" : "Appeal") + " · " + (report ? "Meldung" : "Einspruch") + ": " + outcome(n.status());
        }
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Player · Spieler", player(n), true));
        fields.add(new Field(report ? "Category · Kategorie" : "Section · Bereich", topic(n), true));
        fields.add(new Field("Reason · Grund", n.text() == null ? "—" : n.text(), false));
        fields.add(new Field(report ? "Reported · Gemeldet" : "Filed · Eingereicht", time(n.createdAt()), true));
        if (!open) {
            fields.add(new Field("Outcome · Ergebnis", outcome(n.status()), true));
            fields.add(new Field("Resolved · Erledigt", time(n.updatedAt()), true));
        }
        String url = report ? this.panelUrl + "/reports" : this.panelUrl + "/cases/" + n.punishmentIdentifier();
        return new MessageSpec(
                open ? "<@&" + this.staffRoleId + ">" : null,
                title,
                open ? OPEN_COLOR : color(n.status()),
                fields,
                "Blackhole · " + marker(n.kind(), n.identifier()),
                Instant.ofEpochMilli(n.createdAt()),
                "Open in panel · Im Panel öffnen",
                url);
    }

    private static String player(Notification n) {
        return n.playerName() != null ? n.playerName() : "Unknown · Unbekannt (`" + n.playerRef() + "`)";
    }

    private static String topic(Notification n) {
        String topic = n.topic().toLowerCase(Locale.ROOT).replace('_', ' ');
        return topic.isEmpty() ? "—" : Character.toUpperCase(topic.charAt(0)) + topic.substring(1);
    }

    private static String time(long epochMillis) {
        long seconds = epochMillis / 1000;
        return "<t:" + seconds + ":f> (<t:" + seconds + ":R>)";
    }

    private static int color(String status) {
        return switch (status) {
            case "DISMISSED", "DENIED", "INELIGIBLE" -> REJECTED_COLOR;
            default -> DONE_COLOR;
        };
    }

    static String outcome(String status) {
        return switch (status) {
            case "ACTIONED" -> "Actioned · Bearbeitet";
            case "DISMISSED" -> "Dismissed · Abgewiesen";
            case "GRANTED_FULL_LIFT" -> "Granted, lifted · Stattgegeben, aufgehoben";
            case "GRANTED_DURATION_REDUCTION" -> "Granted, shortened · Stattgegeben, verkürzt";
            case "DENIED" -> "Denied · Abgelehnt";
            case "INELIGIBLE" -> "Not eligible · Nicht zulässig";
            default -> status;
        };
    }
}
