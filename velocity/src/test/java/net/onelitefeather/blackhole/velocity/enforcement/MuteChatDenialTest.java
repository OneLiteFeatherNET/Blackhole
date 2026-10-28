package net.onelitefeather.blackhole.velocity.enforcement;

import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.velocity.evidence.FakePlayer;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MuteChatDenialTest {

    private static final Player ALICE = FakePlayer.on(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "lobby");

    private static PlayerChatEvent chat() {
        return new PlayerChatEvent(ALICE, "hello");
    }

    @Test
    void aSignedMessageIsDeniedWhenSignedVelocityIsPresent() {
        PlayerChatEvent event = chat();

        MuteChatDenial.Outcome outcome = new MuteChatDenial(true).deny(event, ProtocolVersion.MINECRAFT_1_21);

        assertEquals(MuteChatDenial.Outcome.DENIED, outcome, "the relay turns the denial into a cancel on the backend, so no disconnect");
        assertFalse(event.getResult().isAllowed(), "the event must carry the denial for the relay to pick up");
    }

    @Test
    void aSignedMessageWithoutSignedVelocityIsDeniedAndTheSenderIsDisconnected() {
        PlayerChatEvent event = chat();

        MuteChatDenial.Outcome outcome = new MuteChatDenial(false).deny(event, ProtocolVersion.MINECRAFT_1_21);

        assertEquals(MuteChatDenial.Outcome.DISCONNECT, outcome, "a signed message can only be stopped by disconnecting the sender");
        assertFalse(event.getResult().isAllowed(), "the message must not be delivered");
    }

    @Test
    void theFirstSignedVersionWithoutSignedVelocityDisconnects() {
        MuteChatDenial.Outcome outcome = new MuteChatDenial(false).deny(chat(), ProtocolVersion.MINECRAFT_1_19_1);

        assertEquals(MuteChatDenial.Outcome.DISCONNECT, outcome, "1.19.1 is the first version Velocity refuses to cancel");
    }

    @Test
    void anUnsignedMessageIsDeniedWithoutSignedVelocity() {
        PlayerChatEvent event = chat();

        MuteChatDenial.Outcome outcome = new MuteChatDenial(false).deny(event, ProtocolVersion.MINECRAFT_1_19);

        assertEquals(MuteChatDenial.Outcome.DENIED, outcome, "before 1.19.1 chat is unsigned and can be cancelled directly, no disconnect");
        assertFalse(event.getResult().isAllowed(), "the message must be denied");
    }

    @Test
    void theDenialReportsWhetherTheRelayIsPresent() {
        assertTrue(new MuteChatDenial(true).relayPresent(), "present");
        assertFalse(new MuteChatDenial(false).relayPresent(), "absent");
    }
}
