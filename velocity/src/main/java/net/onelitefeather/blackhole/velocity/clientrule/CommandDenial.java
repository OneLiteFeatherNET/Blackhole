package net.onelitefeather.blackhole.velocity.clientrule;

import com.velocitypowered.api.event.command.CommandExecuteEvent.SignedState;
import org.jetbrains.annotations.NotNull;

/**
 * Decides whether a command may be denied on the proxy.
 *
 * <p>The javadoc of {@code CommandExecuteEvent.SignedState} states that cancelling a command in state
 * {@code SIGNED_WITH_ARGS} makes Velocity kick the player. That is the same problem as signed chat and gets
 * the same answer as {@code MuteChatDenial}: SignedVelocity (loaded on the proxy, with its backend plugin)
 * carries the cancellation to the backend server, which then never sees a signed command it did not receive.
 * Without it a signed command must be delivered. {@code SIGNED_WITHOUT_ARGS}, {@code UNSIGNED} (clicks,
 * 1.20.5+) and {@code UNSUPPORTED} (before 1.19.3) are safe to deny either way.</p>
 */
public final class CommandDenial {

    private final boolean relayPresent;

    /**
     * @param relayPresent whether SignedVelocity is loaded on the proxy
     */
    public CommandDenial(boolean relayPresent) {
        this.relayPresent = relayPresent;
    }

    /**
     * @param state how the command was invoked
     * @return whether the command may be denied; false means it must be delivered and the hit recorded as not enforced
     */
    public boolean mayDeny(@NotNull SignedState state) {
        return state != SignedState.SIGNED_WITH_ARGS || this.relayPresent;
    }
}
