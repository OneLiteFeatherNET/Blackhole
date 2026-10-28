package net.onelitefeather.blackhole.velocity.enforcement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Whether SignedVelocity is loaded on this proxy, read once at startup. Without it a muted player's
 * signed chat and signed mute commands are stopped by disconnecting the sender, and the operator must
 * be told so; the backend is told the same state so that staff see it on the panel.
 *
 * @param present whether SignedVelocity is loaded
 * @param version the SignedVelocity version, null when absent or unknown
 */
public record RelayState(boolean present, @Nullable String version) {

    /** The error line for a missing relay: it names the consequence, so the operator knows what the missing plugin costs. */
    static final String MISSING_MESSAGE = "SignedVelocity is not loaded on this proxy. Signed chat and signed commands of muted players "
            + "cannot be cancelled without it, so a muted player who sends one is disconnected with the mute screen instead. "
            + "Install SignedVelocity on the proxy and on every backend server.";

    /**
     * @param loaded  whether the plugin manager reports SignedVelocity as loaded
     * @param version its version, if known
     * @param error   receives the error line when the relay is missing
     * @return the relay state of this proxy
     */
    public static @NotNull RelayState check(boolean loaded, @Nullable String version, @NotNull Consumer<String> error) {
        if (!loaded) {
            error.accept(MISSING_MESSAGE);
            return new RelayState(false, null);
        }
        return new RelayState(true, version);
    }
}
