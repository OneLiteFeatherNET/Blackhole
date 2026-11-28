package net.onelitefeather.blackhole.discord.notify;

import com.fasterxml.jackson.databind.JsonNode;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Field;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MessageSpec;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a join guard or area ban event into a team message, from its payload only. Every value is read by
 * a key named here, so an address, an owner hash or a player name in a payload is never shown. The footer
 * ends with the event marker, by which a redelivered event finds its message again.
 */
public final class GuardMessageRenderer {

    static final int RED = 0xC62828;
    static final int AMBER = 0xF9A825;
    static final int GREEN = 0x2E7D32;
    private static final int TOP_LINE_LIMIT = 60;
    private static final String NONE = "—";

    private final String panelUrl;

    /** @param panelUrl the admin panel base URL without a trailing slash, for the link button */
    public GuardMessageRenderer(@NotNull String panelUrl) {
        this.panelUrl = panelUrl;
    }

    /** @return the footer marker of the message of this event */
    public static @NotNull String marker(@NotNull String eventId) {
        return "ref:guard:" + eventId;
    }

    public @NotNull MessageSpec render(@NotNull GuardEvent event) {
        JsonNode p = event.payload();
        return switch (event.eventType()) {
            case "guard.level_changed" -> levelChanged(event, p);
            case "area_ban.created" -> areaBanCreated(event, p);
            case "area_ban.lifted" -> areaBanLifted(event, p);
            case "guard.denials.summary" -> denialSummary(event, p);
            default -> throw new IllegalArgumentException("not a guard event: " + event.eventType());
        };
    }

    private MessageSpec levelChanged(GuardEvent event, JsonNode p) {
        String level = text(p, "level");
        List<Field> fields = List.of(
                new Field("Level · Stufe", text(p, "previous") + " → " + level, true),
                new Field("Source · Auslöser", levelSource(text(p, "source")), true),
                new Field("Changed · Geändert", time(event.occurredAt()), true));
        return spec(event, "Join guard · Beitrittsschutz", levelColor(level), fields, "/guard");
    }

    private MessageSpec areaBanCreated(GuardEvent event, JsonNode p) {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Area · Gebiet", area(p), true));
        fields.add(new Field("Source · Quelle", banSource(text(p, "source")), true));
        fields.add(new Field("Expires · Läuft ab", p.hasNonNull("expiresAt") ? time(p.path("expiresAt").asLong()) : "Never · Nie", true));
        return spec(event, "Area ban · Gebietssperre", RED, fields, "/area-bans");
    }

    private MessageSpec areaBanLifted(GuardEvent event, JsonNode p) {
        List<Field> fields = List.of(
                new Field("Area · Gebiet", area(p), true),
                new Field("Lifted · Aufgehoben", time(p.path("liftedAt").asLong(event.occurredAt())), true));
        return spec(event, "Area ban lifted · Gebietssperre aufgehoben", GREEN, fields, "/area-bans");
    }

    private MessageSpec denialSummary(GuardEvent event, JsonNode p) {
        StringBuilder top = new StringBuilder();
        for (JsonNode entry : p.path("top")) {
            if (top.length() > 0) {
                top.append('\n');
            }
            top.append("`AS").append(clip(text(entry, "asn"))).append("` · ")
                    .append(clip(text(entry, "organisation"))).append(" · ")
                    .append(clip(text(entry, "countryCode"))).append(" · ")
                    .append(entry.path("count").asLong(0));
        }
        List<Field> fields = List.of(
                new Field("Refused · Abgewiesen", String.valueOf(p.path("total").asLong(0)), true),
                new Field("Window · Zeitraum", time(p.path("windowStart").asLong(0)) + " – " + time(p.path("windowEnd").asLong(0)), true),
                new Field("Top ASNs · Top-ASNs", top.length() == 0 ? NONE : top.toString(), false));
        return spec(event, "Refused joins · Abgewiesene Beitritte", AMBER, fields, "/guard");
    }

    private MessageSpec spec(GuardEvent event, String title, int color, List<Field> fields, String panelPath) {
        return new MessageSpec(
                null,
                title,
                color,
                fields,
                "Blackhole · " + marker(event.eventId()),
                Instant.ofEpochMilli(event.occurredAt()),
                "Open in panel · Im Panel öffnen",
                this.panelUrl + panelPath);
    }

    private static String area(JsonNode p) {
        String value = clip(text(p, "value"));
        return "ASN".equals(text(p, "kind")) ? "ASN " + value : "Country · Land " + value;
    }

    private static String levelSource(String source) {
        return switch (source) {
            case "UNATTENDED" -> "Nobody from the team online · Team nicht online";
            case "CLEARED" -> "Staff back online · Team wieder online";
            case "MANUAL" -> "Set by staff · Vom Team gesetzt";
            default -> clip(source);
        };
    }

    private static String banSource(String source) {
        return switch (source) {
            case "MANUAL" -> "Staff · Team";
            case "AUTO_BURST" -> "Automatic, burst rule · Automatisch, Burst-Regel";
            default -> clip(source);
        };
    }

    private static int levelColor(String level) {
        return switch (level) {
            case "LOCKDOWN" -> RED;
            case "STRICT" -> AMBER;
            default -> GREEN;
        };
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isValueNode() && !value.isNull() ? value.asText() : NONE;
    }

    private static String clip(String value) {
        String safe = value.replace("`", "'").replace("\n", " ").strip();
        if (safe.isEmpty()) {
            return NONE;
        }
        return safe.length() <= TOP_LINE_LIMIT ? safe : safe.substring(0, TOP_LINE_LIMIT - 1) + "…";
    }

    private static String time(long epochMillis) {
        return "<t:" + epochMillis / 1000 + ":f>";
    }
}
