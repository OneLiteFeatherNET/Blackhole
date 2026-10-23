package net.onelitefeather.blackhole.velocity.component;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.renderer.ComponentRenderer;
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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
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
    private final ComponentRenderer<Locale> renderer;

    /**
     * @param renderer translates the finished message, e.g. {@code GlobalTranslator.renderer()}
     */
    public PunishmentScreens(
            @NotNull Clock clock, @NotNull BlackholeConfig config, @NotNull SectionRegistry sections,
            @NotNull ComponentRenderer<Locale> renderer
    ) {
        this.renderer = renderer;
        this.clock = clock;
        this.config = config;
        this.sections = sections;
    }

    /**
     * The one locale decision for a player's message: both the translation and the date format use
     * it. This is the player's locale, or, while the client has not reported one (login), the proxy's
     * default locale - the same fallback Velocity itself uses to translate a message.
     */
    public static @NotNull Locale localeOf(@NotNull Player player) {
        Locale locale = player.getEffectiveLocale();
        return locale == null ? Locale.getDefault() : locale;
    }

    /**
     * The screen for a punishment of any enforcement kind, already translated: translation and date
     * format use the given locale. A disconnect screen is grouped by blank lines (it is centred);
     * a chat message uses plain line breaks to stay compact.
     *
     * @param movedToFallback for SERVER punishments: whether the player was moved to the fallback server
     */
    public @NotNull Component screen(
            @NotNull PunishmentSyncMessage punishment, @NotNull Locale locale, @NotNull Surface surface, boolean movedToFallback
    ) {
        return screen(punishment, locale, surface, movedToFallback, 0);
    }

    /**
     * Like {@link #screen(PunishmentSyncMessage, Locale, Surface, boolean)}, and tells the player how
     * many pieces of evidence exist - the number only, never their content.
     *
     * @param evidenceCount how many valid evidence items the punishment has; nothing is shown for zero or less
     */
    public @NotNull Component screen(
            @NotNull PunishmentSyncMessage punishment, @NotNull Locale locale, @NotNull Surface surface,
            boolean movedToFallback, long evidenceCount
    ) {
        SectionInfo section = this.sections.find(punishment.section()).orElse(null);
        EnforcementKind kind = parseKind(punishment.enforcement(), section);

        List<Component> paragraphs = new ArrayList<>();
        paragraphs.add(Component.translatable(switch (kind) {
            case MUTE -> "punishment.screen.title.mute";
            case SERVER -> "punishment.screen.title.server";
            default -> "punishment.screen.title.ban";
        }));
        paragraphs.add(Component.translatable("punishment.screen.reason", reason(punishment)));
        paragraphs.add(duration(punishment, locale));

        List<Component> where = new ArrayList<>();
        String sectionName = section != null ? section.displayName() : punishment.section();
        boolean hasSection = sectionName != null && !sectionName.isBlank();
        Component id = id(punishment.punishmentIdentifier(), surface);
        if (hasSection && id != null) {
            where.add(Component.translatable("punishment.screen.section_id", Component.text(sectionName), id));
        } else if (hasSection) {
            where.add(Component.translatable("punishment.screen.section", Component.text(sectionName)));
        } else if (id != null) {
            where.add(Component.translatable("punishment.screen.id", id));
        }
        if (kind == EnforcementKind.SERVER && section != null && !section.servers().isEmpty()) {
            Component targets = Component.text(String.join(", ", section.servers()));
            where.add(switch (section.targetKind()) {
                case CLOUDNET_TASK -> Component.translatable("punishment.screen.target.task", targets);
                case CLOUDNET_GROUP -> Component.translatable("punishment.screen.target.group", targets);
                case CLOUDNET_SERVICE -> Component.translatable("punishment.screen.target.service", targets);
                case PATTERN -> Component.translatable("punishment.screen.servers", targets);
            });
            if (movedToFallback) {
                where.add(Component.translatable("punishment.screen.moved"));
            }
        }
        if (evidenceCount > 0) {
            where.add(Component.translatable("punishment.screen.evidence", Component.text(evidenceCount)));
        }
        if (!where.isEmpty()) {
            paragraphs.add(Component.join(JoinConfiguration.newlines(), where));
        }
        Component appeal = appeal(surface);
        if (appeal != null) {
            paragraphs.add(Component.translatable("punishment.screen.appeal", appeal));
        }
        JoinConfiguration separator = surface == Surface.DISCONNECT
                ? JoinConfiguration.separator(Component.newline().append(Component.newline()))
                : JoinConfiguration.newlines();
        return this.renderer.render(Component.join(separator, paragraphs), locale);
    }

    /**
     * The one-line reminder for a muted player who keeps chatting: the title, the remaining time (or
     * "permanent") and the short ID. The reason and the appeal hint are left to the full screen.
     */
    public @NotNull Component muteReminder(@NotNull PunishmentSyncMessage punishment, @NotNull Locale locale) {
        List<Component> parts = new ArrayList<>();
        parts.add(Component.translatable("punishment.screen.title.mute"));
        if (punishment.expiresAt() == null) {
            parts.add(Component.translatable("punishment.screen.duration.permanent"));
        } else {
            Duration remaining = Duration.ofMillis(punishment.expiresAt() - this.clock.millis());
            parts.add(Component.translatable("punishment.screen.remaining", DurationTag.remaining(remaining)));
        }
        Component id = id(punishment.punishmentIdentifier(), Surface.CHAT);
        if (id != null) {
            parts.add(Component.translatable("punishment.screen.id", id));
        }
        return this.renderer.render(
                Component.join(JoinConfiguration.separator(Component.text(" · ", NamedTextColor.GRAY)), parts), locale);
    }

    /**
     * The short ID shown to players: {@code #} and the first eight hex characters (upper case) of the
     * punishment's UUID. The backend's punishment identifier is that UUID as 22 character URL-safe
     * Base64 (16 bytes, no padding; {@code IdGenerator.generateId}), so the short ID is the hex of the
     * first four decoded bytes. Hex has no look-alike characters and is case-insensitive, and the
     * backend can search it: the first four bytes are the first five Base64 characters plus one of
     * four possible sixth characters.
     *
     * @param punishmentIdentifier the identifier as stored by the backend (a plain UUID is accepted too)
     * @return the short ID, null if the identifier is missing or in neither format
     */
    public static @Nullable String shortId(@Nullable String punishmentIdentifier) {
        byte[] bytes = identifierBytes(punishmentIdentifier);
        if (bytes == null) {
            return null;
        }
        return "#" + HexFormat.of().withUpperCase().formatHex(bytes, 0, 4);
    }

    private static byte @Nullable [] identifierBytes(@Nullable String identifier) {
        if (identifier == null) {
            return null;
        }
        try {
            if (identifier.length() == 36) {
                UUID uuid = UUID.fromString(identifier);
                return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
            }
            if (identifier.length() == 22) {
                byte[] bytes = Base64.getUrlDecoder().decode(identifier);
                return bytes.length == 16 ? bytes : null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return null;
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
            return Component.translatable("punishment.screen.duration.permanent");
        }
        Duration remaining = Duration.ofMillis(punishment.expiresAt() - this.clock.millis());
        DateTimeFormatter format = new DateTimeFormatterBuilder()
                .appendLocalized(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .toFormatter(locale);
        String until = format.format(Instant.ofEpochMilli(punishment.expiresAt()).atZone(this.config.getZone()));
        return Component.translatable("punishment.screen.duration.temporary", DurationTag.remaining(remaining), Component.text(until));
    }

    private @Nullable Component id(@Nullable String identifier, Surface surface) {
        String shortId = shortId(identifier);
        if (shortId == null) {
            return null;
        }
        Component text = Component.text(shortId);
        if (surface == Surface.CHAT) {
            text = text.hoverEvent(HoverEvent.showText(Component.text(identifier)));
        }
        return text;
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
