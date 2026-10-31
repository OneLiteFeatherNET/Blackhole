package net.onelitefeather.blackhole.velocity.component.theme;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.kyori.adventure.text.Component.text;

/**
 * The look of every staff-facing chat message, defined once as MiniMessage tags in the {@code bh}
 * namespace ({@code <bh:player>Name</bh:player>}), so the texts in the bundles say what a value is and
 * never which colour it has. Styling tags wrap their content, inserting tags stand alone (self-closing, so they never colour the text after them).
 */
public final class BlackholeTheme implements TagResolver {

    public static final NamedTextColor SUCCESS = NamedTextColor.GREEN;
    public static final NamedTextColor ERROR = NamedTextColor.RED;
    public static final NamedTextColor WARNING = NamedTextColor.YELLOW;
    public static final NamedTextColor INFO = NamedTextColor.GRAY;
    public static final NamedTextColor MUTED = NamedTextColor.DARK_GRAY;
    public static final NamedTextColor PLAYER = NamedTextColor.WHITE;
    public static final NamedTextColor SECTION = NamedTextColor.AQUA;
    public static final NamedTextColor DURATION = NamedTextColor.YELLOW;
    public static final NamedTextColor ID = NamedTextColor.GOLD;
    public static final NamedTextColor KEY = NamedTextColor.LIGHT_PURPLE;
    public static final NamedTextColor UP = NamedTextColor.GREEN;
    public static final NamedTextColor DOWN = NamedTextColor.RED;

    private static final String NAMESPACE = "bh";

    /**
     * The OneLiteFeather brand gradient: orange, pink, purple. The fourth brand stop, deep blue
     * (#2A388F), is left out because it has too little contrast on the dark chat background.
     */
    private static final List<TextColor> BRAND_GRADIENT = List.of(
            TextColor.color(0xF7931D), TextColor.color(0xEC008B), TextColor.color(0x91268F));

    private static final Component PREFIX = gradient("Blackhole").append(text(" » ", MUTED));

    private static final Map<String, Tag> TAGS = Map.ofEntries(
            Map.entry("prefix", Tag.selfClosingInserting(PREFIX)),
            Map.entry("ok", Tag.selfClosingInserting(text("✔ ", SUCCESS))),
            Map.entry("err", Tag.selfClosingInserting(text("✖ ", ERROR))),
            Map.entry("warn", Tag.selfClosingInserting(text("⚠ ", WARNING))),
            Map.entry("sep", Tag.selfClosingInserting(text(" · ", MUTED))),
            Map.entry("success", Tag.styling(SUCCESS)),
            Map.entry("error", Tag.styling(ERROR)),
            Map.entry("warning", Tag.styling(WARNING)),
            Map.entry("info", Tag.styling(INFO)),
            Map.entry("muted", Tag.styling(MUTED)),
            Map.entry("player", Tag.styling(PLAYER, TextDecoration.BOLD)),
            Map.entry("section", Tag.styling(SECTION)),
            Map.entry("duration", Tag.styling(DURATION)),
            Map.entry("id", Tag.styling(ID)),
            Map.entry("key", Tag.styling(KEY)),
            Map.entry("up", Tag.styling(UP)),
            Map.entry("down", Tag.styling(DOWN)));

    private static final BlackholeTheme INSTANCE = new BlackholeTheme();

    private static final MiniMessage MINI_MESSAGE = MiniMessage.builder()
            .tags(TagResolver.resolver(StandardTags.defaults(), INSTANCE))
            .build();

    private BlackholeTheme() {
    }

    /** The word with one colour per character, spread evenly over the brand gradient stops. */
    private static Component gradient(String word) {
        Component result = Component.empty();
        int last = word.length() - 1;
        int segments = BRAND_GRADIENT.size() - 1;
        for (int i = 0; i <= last; i++) {
            double position = last == 0 ? 0 : (double) i / last * segments;
            int segment = Math.min((int) position, segments - 1);
            TextColor colour = TextColor.lerp((float) (position - segment), BRAND_GRADIENT.get(segment), BRAND_GRADIENT.get(segment + 1));
            result = result.append(text(word.charAt(i), colour));
        }
        return result;
    }

    /** @return the resolver that knows every {@code bh:} tag */
    public static @NotNull TagResolver resolver() {
        return INSTANCE;
    }

    /** @return the MiniMessage with the standard tags plus the {@code bh:} tags, for the translation store */
    public static @NotNull MiniMessage miniMessage() {
        return MINI_MESSAGE;
    }

    /**
     * The message with the Blackhole prefix in front, the same component the {@code <bh:prefix>} tag inserts. For texts
     * that are built in code rather than read from a bundle.
     *
     * @param message the text after the prefix
     * @return the prefix followed by the message
     */
    public static @NotNull Component prefixed(@NotNull Component message) {
        return PREFIX.append(message);
    }

    /** @return the names after {@code bh:} that the theme defines */
    public static @NotNull Set<String> tagNames() {
        return TAGS.keySet();
    }

    @Override
    public @Nullable Tag resolve(@NotNull String name, @NotNull ArgumentQueue arguments, @NotNull Context ctx) {
        // MiniMessage splits "bh:player" into the tag "bh" and the argument "player".
        if (!NAMESPACE.equals(name) || !arguments.hasNext()) {
            return null;
        }
        return TAGS.get(arguments.pop().value());
    }

    @Override
    public boolean has(@NotNull String name) {
        return NAMESPACE.equals(name);
    }
}
