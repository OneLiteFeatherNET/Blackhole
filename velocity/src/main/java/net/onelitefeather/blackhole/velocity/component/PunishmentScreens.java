package net.onelitefeather.blackhole.velocity.component;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.component.tag.DurationTag;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.UUID;

/**
 * The text a punished player sees: what happened, why, for how long, in which area and the
 * punishment's short ID to quote in an appeal. The same layout serves disconnect screens (plain: no
 * hover or click there) and chat messages (hover shows the full ID, the appeal link is clickable).
 * Never contains the player's UUID or owner hash.
 */
public final class PunishmentScreens {

    /** Where a message is shown; decides whether hover and click events are attached. */
    public enum Surface { DISCONNECT, CHAT }

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Clock clock;
    private final BlackholeConfig config;
    private final SectionRegistry sections;

    public PunishmentScreens(@NotNull Clock clock, @NotNull BlackholeConfig config, @NotNull SectionRegistry sections) {
        this.clock = clock;
        this.config = config;
        this.sections = sections;
    }

    /**
     * @return the locale of the player, English if the client has not reported one (yet)
     */
    public static @NotNull Locale localeOf(@NotNull Player player) {
        Locale locale = player.getEffectiveLocale();
        return locale == null ? Locale.ENGLISH : locale;
    }

    /**
     * The screen for a punishment of any enforcement kind.
     *
     * @param movedToFallback for SERVER punishments: whether the player was moved to the fallback server
     */
    public @NotNull Component screen(
            @NotNull PunishmentSyncMessage punishment, @NotNull Locale locale, @NotNull Surface surface, boolean movedToFallback
    ) {
        SectionInfo section = this.sections.find(punishment.section()).orElse(null);
        EnforcementKind kind = parseKind(punishment.enforcement(), section);

        Component out = Component.translatable(switch (kind) {
            case MUTE -> "punishment.screen.title.mute";
            case SERVER -> "punishment.screen.title.server";
            default -> "punishment.screen.title.ban";
        });
        out = out.append(line("punishment.screen.reason", reason(punishment)));
        out = out.append(duration(punishment, locale));
        String sectionName = section != null ? section.displayName() : punishment.section();
        if (sectionName != null && !sectionName.isBlank()) {
            out = out.append(line("punishment.screen.section", Component.text(sectionName)));
        }
        if (kind == EnforcementKind.SERVER && section != null && !section.servers().isEmpty()) {
            out = out.append(line("punishment.screen.servers", Component.text(String.join(", ", section.servers()))));
            if (movedToFallback) {
                out = out.append(line("punishment.screen.moved"));
            }
        }
        Component id = id(punishment.punishmentIdentifier(), surface);
        if (id != null) {
            out = out.append(line("punishment.screen.id", id));
        }
        Component appeal = appeal(surface);
        if (appeal != null) {
            out = out.append(line("punishment.screen.appeal", appeal));
        }
        return out;
    }

    /**
     * @return the first eight hex characters of the punishment's UUID, upper case, prefixed with
     * {@code #}; null if there is no identifier
     */
    public static @Nullable String shortId(@Nullable String punishmentIdentifier) {
        if (punishmentIdentifier == null || punishmentIdentifier.isBlank()) {
            return null;
        }
        String hex = punishmentIdentifier.replace("-", "");
        return "#" + hex.substring(0, Math.min(8, hex.length())).toUpperCase(Locale.ROOT);
    }

    private static EnforcementKind parseKind(String enforcement, SectionInfo section) {
        if (enforcement != null) {
            try {
                return EnforcementKind.valueOf(enforcement);
            } catch (IllegalArgumentException ignored) {
                // fall through to the section
            }
        }
        return section != null && section.enforcement() != null ? section.enforcement() : EnforcementKind.BAN;
    }

    private static Component line(String key, Component... args) {
        return Component.newline().append(Component.translatable(key, args));
    }

    private Component reason(PunishmentSyncMessage punishment) {
        if (punishment.reason() == null || punishment.reason().isBlank()) {
            return Component.translatable("punishment.reason.default");
        }
        TagResolver duration = punishment.expiresAt() == null
                ? TagResolver.empty()
                : DurationTag.resolver(punishment.expiresAt(), this.clock.millis());
        return MINI.deserialize(punishment.reason(), duration);
    }

    private Component duration(PunishmentSyncMessage punishment, Locale locale) {
        if (punishment.expiresAt() == null) {
            return line("punishment.screen.duration.permanent");
        }
        Duration remaining = Duration.ofMillis(punishment.expiresAt() - this.clock.millis());
        DateTimeFormatter format = new DateTimeFormatterBuilder()
                .appendLocalized(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .appendLiteral(' ')
                .appendZoneText(TextStyle.SHORT)
                .toFormatter(locale);
        String until = format.format(Instant.ofEpochMilli(punishment.expiresAt()).atZone(this.config.getZone()));
        return line("punishment.screen.duration.temporary", DurationTag.remaining(remaining), Component.text(until));
    }

    private @Nullable Component id(@Nullable String identifier, Surface surface) {
        String shortId = shortId(identifier);
        if (shortId == null) {
            return null;
        }
        Component text = Component.text(shortId);
        if (surface == Surface.CHAT) {
            text = text.hoverEvent(HoverEvent.showText(Component.text(fullId(identifier))));
        }
        return text;
    }

    private static String fullId(String identifier) {
        try {
            return UUID.fromString(identifier).toString();
        } catch (IllegalArgumentException e) {
            return identifier;
        }
    }

    private @Nullable Component appeal(Surface surface) {
        String url = this.config.getAppealUrl();
        if (url == null) {
            return null;
        }
        String label = this.config.getAppealText();
        if (surface == Surface.CHAT) {
            return Component.text(label != null ? label : url).clickEvent(ClickEvent.openUrl(url));
        }
        return Component.text(label != null ? label + " (" + url + ")" : url);
    }
}
