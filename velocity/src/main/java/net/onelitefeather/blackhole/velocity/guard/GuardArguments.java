package net.onelitefeather.blackhole.velocity.guard;

import org.jetbrains.annotations.NotNull;

/**
 * Reads what follows {@code /guard}: nothing shows the status, a level name sets the manual level. Free of the
 * command framework and the proxy, so it can be tested with plain strings.
 */
public final class GuardArguments {

    /** What a staff member gets when the level is not one of the three. */
    public static final String USAGE = "Usage: /guard [off|strict|lockdown]";

    /** What the arguments mean, or why they do not. */
    public sealed interface Result permits Status, SetLevel, Invalid {
    }

    /** Show the level in force. */
    public record Status() implements Result {
    }

    /**
     * @param level the manual level to set
     */
    public record SetLevel(@NotNull GuardLevel level) implements Result {
    }

    /**
     * @param message what to tell the staff member
     */
    public record Invalid(@NotNull String message) implements Result {
    }

    private GuardArguments() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @param input everything after {@code /guard}
     */
    public static @NotNull Result parse(@NotNull String input) {
        String text = input.strip();
        if (text.isEmpty()) {
            return new Status();
        }
        GuardLevel level = GuardLevel.parse(text);
        return level == null ? new Invalid(USAGE) : new SetLevel(level);
    }
}
