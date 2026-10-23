package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Builds the arguments of the staff texts: a value with the hover and click it deserves. Colour comes
 * from the {@code bh:} tags around the argument, events from here. A click only ever suggests a command
 * or copies text, it never runs anything; {@link Mode#PLAIN} receivers get no events.
 */
public final class Fmt {

    /** The Unicode minus sign, which the default Minecraft font renders. */
    private static final char MINUS = '−';
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Fmt() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** A player name; a click suggests {@code /elo <name>}. */
    public static @NotNull Component player(@NotNull String name, @NotNull Mode mode) {
        Component component = Component.text(name);
        if (mode == Mode.PLAIN) {
            return component;
        }
        return component
                .clickEvent(ClickEvent.suggestCommand("/elo " + name))
                .hoverEvent(HoverEvent.showText(Component.translatable("staff.hover.player")));
    }

    /** The short id players quote ({@code #A1B2C3D4}) of a backend identifier; a click copies it. */
    public static @NotNull Component shortId(@Nullable String punishmentIdentifier, @NotNull Mode mode) {
        String shortId = PunishmentScreens.shortId(punishmentIdentifier);
        if (shortId == null) {
            return Component.empty();
        }
        Component component = Component.text(shortId);
        if (mode == Mode.PLAIN) {
            return component;
        }
        return component
                .clickEvent(ClickEvent.copyToClipboard(shortId))
                .hoverEvent(HoverEvent.showText(Component.translatable("staff.hover.copy")));
    }

    /** The display name of a section. */
    public static @NotNull Component section(@NotNull SectionInfo section) {
        return Component.text(section.displayName());
    }

    /** The signed change between two scores: {@code +100} green, {@code −100} red, {@code 0} uncoloured. */
    public static @NotNull Component scoreDelta(int before, int after) {
        int delta = after - before;
        if (delta > 0) {
            return Component.text("+" + delta, BlackholeTheme.UP);
        }
        if (delta < 0) {
            return Component.text(MINUS + Integer.toString(-delta), BlackholeTheme.DOWN);
        }
        return Component.text("0");
    }

    /** An absolute time in the given zone, such as {@code 2026-10-22 20:14 (Europe/Berlin)}. */
    public static @NotNull Component time(@NotNull Instant instant, @NotNull ZoneId zone) {
        return Component.text(TIME.format(instant.atZone(zone)) + " (" + zone.getId() + ")");
    }
}
