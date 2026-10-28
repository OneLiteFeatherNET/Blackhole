package net.onelitefeather.blackhole.velocity.enforcement;

import com.velocitypowered.api.event.command.CommandExecuteEvent.SignedState;
import net.onelitefeather.blackhole.velocity.clientrule.CommandWords;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Set;

/**
 * Decides what happens to a command a muted player types. Only the configured commands are covered
 * (private messages and emotes by default); they are matched the way client rule command words are
 * ({@link CommandWords}), so {@code minecraft:msg} is {@code msg}.
 *
 * <p>A covered command is never run or forwarded. A command with signed arguments follows the rule of
 * {@link MuteChatDenial}: denied with SignedVelocity, otherwise the sender is disconnected, because
 * Velocity kicks on the cancel of such a command anyway.</p>
 */
public final class MuteCommandGate {

    /** What the listener must do with the command. */
    public enum Verdict {
        /** The command runs as usual. */
        ALLOW,
        /** The command is denied; the sender is told about the mute. */
        DENY,
        /** The command is denied and the sender is disconnected with the mute screen. */
        DISCONNECT
    }

    private final Set<String> commands;
    private final boolean relayPresent;

    /**
     * @param commands     the covered commands, lower-cased without a leading slash
     * @param relayPresent whether SignedVelocity is loaded on the proxy
     */
    public MuteCommandGate(@NotNull Collection<String> commands, boolean relayPresent) {
        this.commands = Set.copyOf(commands);
        this.relayPresent = relayPresent;
    }

    /**
     * @param line the command line as typed, without the sender's name or the leading slash required
     * @return whether the first word of the line is a covered command; only then does a mute need checking
     */
    public boolean covers(@NotNull String line) {
        return CommandWords.candidates(line).stream().anyMatch(this.commands::contains);
    }

    /**
     * @param line   the command line as typed
     * @param state  how the command was invoked
     * @param muted  whether the sender has an active mute
     * @return what to do with the command
     */
    public Verdict verdict(@NotNull String line, @NotNull SignedState state, boolean muted) {
        if (!muted || !covers(line)) {
            return Verdict.ALLOW;
        }
        if (state == SignedState.SIGNED_WITH_ARGS && !this.relayPresent) {
            return Verdict.DISCONNECT;
        }
        return Verdict.DENY;
    }
}
