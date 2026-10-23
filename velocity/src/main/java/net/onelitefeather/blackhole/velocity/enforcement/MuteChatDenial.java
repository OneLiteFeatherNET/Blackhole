package net.onelitefeather.blackhole.velocity.enforcement;

import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import org.jetbrains.annotations.NotNull;

/**
 * Decides whether a muted player's chat message may be denied on the proxy.
 *
 * <p>Since Minecraft 1.19.1 chat is signed and Velocity disconnects a player whose signed message a
 * plugin cancels. The SignedVelocity proxy plugin (with its backend plugin) makes that safe: it runs
 * after every other handler, turns a denied result into "allowed" and tells the backend server to
 * drop the message. It has no API of its own - denying the event as usual is all it needs. Without
 * it a signed message must never be denied, so the player is only told about the mute.
 */
public final class MuteChatDenial {

    /** The Velocity plugin id of SignedVelocity. */
    public static final String RELAY_PLUGIN_ID = "signedvelocity";

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
     * Denies the message when that is safe: always for unsigned chat, for signed chat only when
     * SignedVelocity is present.
     *
     * @param event   the chat event of the muted player
     * @param version the player's protocol version
     * @return {@code true} if the message was denied, {@code false} if it is still delivered
     */
    public boolean deny(@NotNull PlayerChatEvent event, @NotNull ProtocolVersion version) {
        if (signed(version) && !this.relayPresent) {
            return false;
        }
        event.setResult(PlayerChatEvent.ChatResult.denied());
        return true;
    }
}
