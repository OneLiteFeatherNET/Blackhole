package net.onelitefeather.blackhole.velocity.punish;

import org.jetbrains.annotations.NotNull;

/**
 * The player argument of a staff command names nobody who is online or known to the backend. It stays an
 * {@link IllegalArgumentException}, which is what Cloud turns into an argument error, and carries the typed
 * name so the error message can repeat it.
 */
public final class TargetNotFoundException extends IllegalArgumentException {

    private final String input;

    public TargetNotFoundException(@NotNull String input) {
        super("Player(%s) not found".formatted(input));
        this.input = input;
    }

    /** @return what the staff member typed */
    public @NotNull String input() {
        return this.input;
    }
}
