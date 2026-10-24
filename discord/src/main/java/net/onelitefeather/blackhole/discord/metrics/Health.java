package net.onelitefeather.blackhole.discord.metrics;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * What the health endpoint reports besides "connected": problems the permission self-check found. A problem names a
 * channel category and a permission, never content.
 */
public final class Health {

    private volatile List<String> problems = List.of();
    private volatile List<String> warnings = List.of();

    public void problems(@NotNull List<String> problems) {
        this.problems = List.copyOf(problems);
    }

    public @NotNull List<String> problems() {
        return this.problems;
    }

    /** Things that are wrong but do not stop the bot, such as a role that can see the ticket category. */
    public void warnings(@NotNull List<String> warnings) {
        this.warnings = List.copyOf(warnings);
    }

    public @NotNull List<String> warnings() {
        return this.warnings;
    }
}
