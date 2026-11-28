package net.onelitefeather.blackhole.velocity.enforcement;

import com.velocitypowered.api.event.command.CommandExecuteEvent.SignedState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MuteCommandGateTest {

    private static final List<String> DEFAULTS = List.of("msg", "tell", "w", "whisper", "me", "r", "reply");

    private static MuteCommandGate gate(boolean relayPresent) {
        return new MuteCommandGate(DEFAULTS, relayPresent);
    }

    @Test
    void aListedCommandIsDeniedForAMutedSenderWithSignedVelocity() {
        MuteCommandGate.Verdict verdict = gate(true).verdict("msg Alex hello", SignedState.SIGNED_WITH_ARGS, true);

        assertEquals(MuteCommandGate.Verdict.DENY, verdict, "the relay carries the cancel to the backend, so the sender stays connected");
    }

    @Test
    void anUnsignedListedCommandIsDeniedWithoutSignedVelocityAndTheSenderStaysConnected() {
        MuteCommandGate.Verdict verdict = gate(false).verdict("me waves", SignedState.UNSIGNED, true);

        assertEquals(MuteCommandGate.Verdict.DENY, verdict, "unsigned commands are cancelled directly");
    }

    @Test
    void aSignedListedCommandWithArgumentsDisconnectsWithoutSignedVelocity() {
        MuteCommandGate.Verdict verdict = gate(false).verdict("msg Alex hello", SignedState.SIGNED_WITH_ARGS, true);

        assertEquals(MuteCommandGate.Verdict.DISCONNECT, verdict, "cancelling it would kick the sender, so the sender is disconnected with the mute screen");
    }

    @Test
    void aSignedListedCommandWithArgumentsIsDeniedWithSignedVelocity() {
        MuteCommandGate.Verdict verdict = gate(true).verdict("tell Alex hello", SignedState.SIGNED_WITH_ARGS, true);

        assertEquals(MuteCommandGate.Verdict.DENY, verdict, "the relay makes the cancel safe");
    }

    @Test
    void aSignedListedCommandWithoutArgumentsIsDeniedWithoutSignedVelocity() {
        MuteCommandGate.Verdict verdict = gate(false).verdict("reply", SignedState.SIGNED_WITHOUT_ARGS, true);

        assertEquals(MuteCommandGate.Verdict.DENY, verdict, "only SIGNED_WITH_ARGS makes Velocity kick on a cancel");
    }

    @Test
    void anUnlistedCommandIsAllowedForAMutedSender() {
        MuteCommandGate.Verdict verdict = gate(false).verdict("spawn", SignedState.SIGNED_WITH_ARGS, true);

        assertEquals(MuteCommandGate.Verdict.ALLOW, verdict, "a mute is about talk, so /spawn and friends keep working");
        assertFalse(gate(false).covers("spawn"), "not covered");
    }

    @Test
    void aNamespacedCommandIsMatchedAsItsLabel() {
        assertTrue(gate(true).covers("minecraft:msg Alex hello"), "minecraft:msg is msg");
        assertEquals(MuteCommandGate.Verdict.DENY, gate(true).verdict("minecraft:msg Alex hello", SignedState.UNSIGNED, true));
    }

    @Test
    void aLeadingSlashAndUpperCaseDoNotHideAListedCommand() {
        assertEquals(MuteCommandGate.Verdict.DENY, gate(true).verdict("/MSG Alex hello", SignedState.UNSIGNED, true));
    }

    @Test
    void aListedCommandIsAllowedForAPlayerWhoIsNotMuted() {
        MuteCommandGate.Verdict verdict = gate(false).verdict("msg Alex hello", SignedState.SIGNED_WITH_ARGS, false);

        assertEquals(MuteCommandGate.Verdict.ALLOW, verdict, "only muted senders are affected");
    }

    @Test
    void anEmptyListDeniesNothing() {
        MuteCommandGate none = new MuteCommandGate(List.of(), false);

        assertFalse(none.covers("msg Alex hello"), "no command is covered");
        assertEquals(MuteCommandGate.Verdict.ALLOW, none.verdict("msg Alex hello", SignedState.SIGNED_WITH_ARGS, true));
    }
}
