package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.velocity.component.tag.DurationTag;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import net.onelitefeather.blackhole.velocity.punish.EloReport;
import net.onelitefeather.phoca.metadata.Expirable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The read-only views of a player: {@code /elo}, {@code info} and {@code history}. Like
 * {@link StaffMessages} they are built from resolved data only and the wording lives in the bundles;
 * {@link Mode#PLAIN} receivers get the hover details written inline instead.
 */
public final class ProfileMessages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String NONE = "-";

    private ProfileMessages() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * One row per section. The score is red below the permanent threshold or while a permanent punishment
     * is active, yellow at or below the soft threshold, green otherwise. The thresholds are the hover of
     * the section for players and written inline for everyone else.
     */
    public static @NotNull Component elo(@NotNull String playerName, @NotNull List<EloReport.Row> rows, @NotNull Mode mode) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("staff.elo.header", Fmt.player(playerName, mode)));
        if (rows.isEmpty()) {
            lines.add(Component.translatable("staff.elo.none"));
        }
        for (EloReport.Row row : rows) {
            Component permanent = Component.text(row.permanentThreshold());
            Component section = Component.text(row.displayName());
            Component score = Component.text(row.score(), scoreColor(row));
            Component marker = row.permanentActive() ? Component.translatable("staff.elo.permanent") : Component.empty();
            boolean hasSoft = row.softThreshold() != null;
            if (mode == Mode.RICH) {
                Component hover = hasSoft
                        ? Component.translatable("staff.hover.elo", Component.text(row.softThreshold()), permanent)
                        : Component.translatable("staff.hover.elo.no_soft", permanent);
                lines.add(Component.translatable("staff.elo.row", section.hoverEvent(HoverEvent.showText(hover)), score, marker));
            } else if (hasSoft) {
                lines.add(Component.translatable("staff.elo.row.plain", section, score, marker, Component.text(row.softThreshold()), permanent));
            } else {
                lines.add(Component.translatable("staff.elo.row.plain.no_soft", section, score, marker, permanent));
            }
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /** The player's active punishments, one line per section. */
    public static @NotNull Component info(
            @NotNull String playerName, @NotNull PunishProfileDTO profile, @NotNull Clock clock, @NotNull ZoneId zone, @NotNull Mode mode
    ) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("staff.info.header", Fmt.player(playerName, mode)));
        Map<String, PunishEntryDTO> active = profile.getActive() == null ? Map.of() : new TreeMap<>(profile.getActive());
        if (active.isEmpty()) {
            lines.add(Component.translatable("staff.info.none"));
        }
        active.values().forEach(entry -> lines.add(entry(entry, clock, zone, mode)));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /** All past and present punishments of the player in the order the backend returned them. */
    public static @NotNull Component history(
            @NotNull String playerName, @NotNull PunishProfileDTO profile, @NotNull Clock clock, @NotNull ZoneId zone, @NotNull Mode mode
    ) {
        List<PunishEntryDTO> history = profile.getHistory() == null ? List.of() : profile.getHistory();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("staff.history.header", Fmt.player(playerName, mode), Component.text(history.size())));
        if (history.isEmpty()) {
            lines.add(Component.translatable("staff.history.empty"));
        }
        history.forEach(entry -> lines.add(entry(entry, clock, zone, mode)));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private static NamedTextColor scoreColor(EloReport.Row row) {
        if (row.permanentActive() || row.score() < row.permanentThreshold()) {
            return BlackholeTheme.DOWN;
        }
        if (row.softThreshold() != null && row.score() <= row.softThreshold()) {
            return BlackholeTheme.WARNING;
        }
        return BlackholeTheme.UP;
    }

    private static Component entry(PunishEntryDTO entry, Clock clock, ZoneId zone, Mode mode) {
        String rawReason = entry.getTemplate() != null ? entry.getTemplate().getReason() : null;
        if (rawReason == null && entry.getMetaData() != null && entry.getMetaData().get("reason") instanceof String fromMeta) {
            rawReason = fromMeta;
        }
        Long expiresAt = entry.getMetaData() != null
                && entry.getMetaData().get(Expirable.META_DATA_KEY_EXPIRATION_DATE) instanceof Number number ? number.longValue() : null;
        String templateKey = entry.getTemplate() == null || entry.getTemplate().getKey() == null ? NONE : entry.getTemplate().getKey();
        boolean permanent = Boolean.TRUE.equals(entry.getPermanent());

        boolean automatic = entry.getMetaData() != null && Boolean.TRUE.equals(entry.getMetaData().get("automatic"));
        Component translated = DefaultReasons.translated(automatic, permanent, rawReason);
        Component reason = translated != null ? translated
                : rawReason == null ? Component.text(NONE) : MINI.deserialize(rawReason, expiryTag(expiresAt, clock));
        Component shortId = PunishmentScreens.shortId(entry.getIdentifier()) == null
                ? Component.text(NONE) : Fmt.shortId(entry.getIdentifier(), mode);
        Component end = permanent ? Component.translatable("duration.permanent")
                : expiresAt == null ? Component.text(NONE) : Fmt.time(Instant.ofEpochMilli(expiresAt), zone);
        Component created = entry.getCreatedAt() == null ? Component.text(NONE) : Fmt.date(Instant.ofEpochMilli(entry.getCreatedAt()), zone);
        Component section = Component.text(entry.getSection() == null ? "?" : entry.getSection());

        Component status = Component.translatable(Boolean.TRUE.equals(entry.getLifted()) ? "staff.profile.status.lifted"
                : permanent ? "staff.profile.status.permanent" : "staff.profile.status.temporary");
        if (mode == Mode.RICH) {
            Component plainReason = translated != null ? translated : Component.text(rawReason == null ? NONE : MINI.stripTags(rawReason));
            status = status.hoverEvent(HoverEvent.showText(
                    Component.translatable("staff.hover.punishment", plainReason, Component.text(templateKey), shortId, end)));
            return Component.translatable("staff.profile.entry", shortId, section, status, created, reason);
        }
        return Component.translatable("staff.profile.entry.plain", shortId, section, status, created, reason,
                Component.text(templateKey), end);
    }

    private static @NotNull TagResolver expiryTag(@Nullable Long expiresAt, Clock clock) {
        return expiresAt == null ? TagResolver.empty() : DurationTag.resolver(expiresAt, clock.millis());
    }
}
