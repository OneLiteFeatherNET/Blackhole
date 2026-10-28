package net.onelitefeather.blackhole.velocity.config;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The mute settings ({@code mute} in {@code config.json}). {@code commands} lists the commands a muted
 * player may not run (private messages and emotes by default); matched like the client rule command
 * words, so {@code minecraft:msg} is {@code msg}. An explicit empty list turns the command gate off.
 */
public final class MuteConfig {

    /** The commands a mute covers when the setting is absent. */
    static final List<String> DEFAULT_COMMANDS = List.of("msg", "tell", "w", "whisper", "me", "r", "reply");

    private List<String> commands;

    /**
     * @return the commands a muted player may not run, lower-cased without a leading slash; the defaults
     * when the setting is absent
     */
    public @NotNull List<String> getCommands() {
        if (commands == null) {
            return DEFAULT_COMMANDS;
        }
        return commands.stream()
                .filter(Objects::nonNull)
                .map(command -> command.strip().replaceFirst("^/+", "").toLowerCase(Locale.ROOT))
                .filter(command -> !command.isEmpty())
                .toList();
    }

    public void setCommands(@NotNull List<String> commands) {
        this.commands = commands;
    }
}
