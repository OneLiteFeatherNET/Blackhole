package net.onelitefeather.blackhole.velocity.enforcement;

import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import org.jetbrains.annotations.NotNull;

/**
 * Stops a muted player's chat message on the proxy.
 *
 * <p>Since Minecraft 1.19.1 chat is signed, and Velocity disconnects a player whose signed message a
 * plugin cancels. The SignedVelocity proxy plugin (with its backend plugin) makes a cancel safe: it runs
 * after every other handler, turns a denied result into "allowed" and tells the backend server to drop
 * the message. Without it a signed message cannot be stopped without the sender: the proxy denies the
 * event and disconnects the sender with the mute screen ({@link Outcome#DISCONNECT}). Unsigned chat (before
 * 1.19.1) is simply denied.</p>
 */
public final class MuteChatDenial {

    /** The Velocity plugin id of SignedVelocity. */
    public static final String RELAY_PLUGIN_ID = "signedvelocity";

    /** What the listener must do after the event was denied. */
    public enum Outcome {
        /** The message is denied; the player is told about the mute. */
        DENIED,
        /** The message is denied and the sender must be disconnected with the mute screen. */
        DISCONNECT
    }

    private final boolean relayPresent;

    /**
     * @param relayPresent whether SignedVelocity is loaded on the proxy
     */
    public MuteChatDenial(boolean relayPresent) {
        this.relayPresent = relayPresent;
    }

    /**
     * @return whether SignedVelocity is loaded on the proxy
     */
    public boolean relayPresent() {
        return this.relayPresent;
    }

    /**
     * @param version the player's protocol version
     * @return whether the player's chat messages are signed (1.19.1 and newer)
     */
    public static boolean signed(@NotNull ProtocolVersion version) {
        return !version.lessThan(ProtocolVersion.MINECRAFT_1_19_1);
    }

    /**
     * Denies the message. For signed chat without SignedVelocity the sender has to be disconnected as well,
     * because that is the only way the proxy can keep the message from the other players.
     *
     * @param event   the chat event of the muted player; its result is set to denied
     * @param version the player's protocol version
     * @return what the caller must do in addition to the denial
     */
    public Outcome deny(@NotNull PlayerChatEvent event, @NotNull ProtocolVersion version) {
        event.setResult(PlayerChatEvent.ChatResult.denied());
        return signed(version) && !this.relayPresent ? Outcome.DISCONNECT : Outcome.DENIED;
    }
}
