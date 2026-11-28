package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

/**
 * The text of a reason a staff member entered for a punishment. It is shown as written: a reason is text, never
 * markup, so a click, hover or colour tag in it does nothing. The one placeholder a reason may use, the remaining
 * duration, is replaced by the given component.
 */
final class Reasons {

    static final String DURATION_PLACEHOLDER = "<duration>";

    private Reasons() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * @param reason   the reason as entered
     * @param duration what the placeholder shows: the remaining time, or the text for a permanent punishment
     */
    static @NotNull Component text(@NotNull String reason, @NotNull Component duration) {
        String[] parts = reason.split(Pattern.quote(DURATION_PLACEHOLDER), -1);
        Component text = Component.empty();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                text = text.append(duration);
            }
            text = text.append(Component.text(parts[i]));
        }
        return text;
    }
}
