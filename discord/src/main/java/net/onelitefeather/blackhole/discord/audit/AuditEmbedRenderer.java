package net.onelitefeather.blackhole.discord.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * Turns one staff action into the webhook body: one embed with the action as title, the staff member, the surface, the
 * target, the time, and the link to the panel as the last field. The renderer takes only the event's fields, so no other
 * text can reach the channel. Mentions are switched off. The footer carries the audit id, by which a duplicate is recognized.
 */
public final class AuditEmbedRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int COLOR = 0x1565C0;

    private final String panelUrl;

    /** @param panelUrl the panel base URL without a trailing slash */
    public AuditEmbedRenderer(@NotNull String panelUrl) {
        this.panelUrl = panelUrl;
    }

    /** @return the JSON body of the webhook call */
    public @NotNull String render(@NotNull StaffActionEvent event) {
        ObjectNode root = JSON.createObjectNode();
        root.putObject("allowed_mentions").putArray("parse");
        ObjectNode embed = root.putArray("embeds").addObject();
        embed.put("title", "Staff action · Team-Aktion: " + label(event.action()));
        embed.put("color", COLOR);
        String link = link(event);
        if (link != null) {
            embed.put("url", link);
        }
        ArrayNode fields = embed.putArray("fields");
        field(fields, "Staff · Team", blank(event.actorName()) ? "Unknown · Unbekannt" : event.actorName(), true);
        field(fields, "Surface · Oberfläche", surface(event.surface()), true);
        field(fields, "Target · Ziel", target(event), true);
        field(fields, "When · Wann", time(event.occurredAt()), true);
        if (link != null) {
            field(fields, "Team panel · Team-Panel", "[Open in panel · Im Panel öffnen](" + link + ")", false);
        }
        embed.putObject("footer").put("text", "Blackhole · audit " + event.auditId());
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A tree of strings always serializes", e);
        }
    }

    private String link(StaffActionEvent event) {
        return blank(event.panelPath()) ? null : this.panelUrl + event.panelPath();
    }

    private static String target(StaffActionEvent event) {
        return switch (event.targetKind()) {
            case "CASE" -> "Case · Fall `" + event.targetRef() + "`";
            case "PLAYER" -> blank(event.targetName()) ? "Player · Spieler `" + event.targetRef() + "`" : event.targetName();
            default -> blank(event.targetRef()) ? "—" : "`" + event.targetRef() + "`";
        };
    }

    private static String label(String action) {
        return switch (action) {
            case "kick.create" -> "Player kicked · Spieler gekickt";
            case "kick.mass" -> "Mass kick · Massenkick";
            case "punishment.revoke" -> "Punishment revoked · Strafe aufgehoben";
            case "staff-link.unlink" -> "Staff link removed · Staff-Verknüpfung entfernt";
            default -> action;
        };
    }

    private static String surface(String surface) {
        return switch (surface) {
            case "PANEL" -> "Panel";
            case "DISCORD" -> "Discord";
            case "IN_GAME" -> "In game · Im Spiel";
            case "API" -> "API";
            default -> surface;
        };
    }

    private static String time(long epochMillis) {
        long seconds = Instant.ofEpochMilli(epochMillis).getEpochSecond();
        return "<t:" + seconds + ":f> (<t:" + seconds + ":R>)";
    }

    private static void field(ArrayNode fields, String name, String value, boolean inline) {
        fields.addObject().put("name", name).put("value", value).put("inline", inline);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
