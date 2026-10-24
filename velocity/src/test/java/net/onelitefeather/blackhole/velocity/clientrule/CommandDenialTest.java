package net.onelitefeather.blackhole.velocity.clientrule;

import com.velocitypowered.api.event.command.CommandExecuteEvent.SignedState;
import net.onelitefeather.blackhole.velocity.enforcement.MuteChatDenial;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Cancelling a command in state SIGNED_WITH_ARGS makes Velocity kick the player, unless SignedVelocity carries the cancellation. */
class CommandDenialTest {

    @Test
    void everySignedStateAgainstSignedVelocityPresentAndAbsent() {
        // state, with SignedVelocity, without
        assertEquals(true, new CommandDenial(true).mayDeny(SignedState.SIGNED_WITH_ARGS), "signed with args, relay present");
        assertEquals(false, new CommandDenial(false).mayDeny(SignedState.SIGNED_WITH_ARGS), "signed with args, no relay: must be delivered");
        assertEquals(true, new CommandDenial(true).mayDeny(SignedState.SIGNED_WITHOUT_ARGS));
        assertEquals(true, new CommandDenial(false).mayDeny(SignedState.SIGNED_WITHOUT_ARGS));
        assertEquals(true, new CommandDenial(true).mayDeny(SignedState.UNSIGNED));
        assertEquals(true, new CommandDenial(false).mayDeny(SignedState.UNSIGNED), "a click-run command on 1.20.5+ can always be denied");
        assertEquals(true, new CommandDenial(true).mayDeny(SignedState.UNSUPPORTED));
        assertEquals(true, new CommandDenial(false).mayDeny(SignedState.UNSUPPORTED), "clients before 1.19.3 do not sign commands");
    }

    @Test
    void theRelayIsTheSamePluginTheMuteDenialLooksFor() {
        assertEquals("signedvelocity", MuteChatDenial.RELAY_PLUGIN_ID);
    }
}
