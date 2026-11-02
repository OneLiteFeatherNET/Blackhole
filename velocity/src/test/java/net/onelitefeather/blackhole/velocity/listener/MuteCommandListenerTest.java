package net.onelitefeather.blackhole.velocity.listener;

import com.velocitypowered.api.event.command.CommandExecuteEvent;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.RecordingProxy;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.enforcement.MuteCommandGate;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNoticeCooldown;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNotices;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A muted player may not run the commands a mute covers; an unknown mute state never blocks a command. */
class MuteCommandListenerTest {

    private final MutableClock clock = new MutableClock();
    private final RecordingProxy recording = new RecordingProxy("lobby", "lobby");
    private final String owner = UUIDConverter.convertToSHA(this.recording.uuid);
    private final PunishmentMirror mirror = new PunishmentMirror(this.clock);
    private boolean backendDown;

    private final SectionRegistry registry = TestData.standardRegistry();
    private final PunishmentState state = new PunishmentState(this.registry, this.mirror, owner -> {
        if (this.backendDown) {
            throw new IllegalStateException("backend down");
        }
        return Optional.empty();
    }, this.clock, () -> true, Duration.ofMinutes(1));

    private final MuteCommandListener listener = new MuteCommandListener(
            new MuteCommandGate(List.of("msg", "tell", "w", "whisper", "me", "r", "reply"), false), this.state,
            identifier -> OptionalLong.empty(),
            new MuteNotices(RecordingProxy.screens(this.registry), new MuteNoticeCooldown(this.clock, Duration.ofSeconds(30))));

    private CommandExecuteEvent run(String command) {
        CommandExecuteEvent event = new CommandExecuteEvent(this.recording.player(), command);
        this.listener.onCommand(event);
        return event;
    }

    private void mute() {
        this.mirror.load(this.owner, List.of(new PunishmentSyncMessage(this.owner, "chat", EnforcementKind.MUTE.name(),
                PunishmentSyncMessage.STATE_SET, "p1", null, "spam", null, false)));
    }

    @Test
    void aMutedPlayerRunningAMutedCommandIsDeniedAndTold() {
        mute();

        CommandExecuteEvent event = run("msg bob hello");

        assertFalse(event.getResult().isAllowed(), "a muted player cannot whisper");
        assertEquals(1, this.recording.messages.size(), "the player is told about the mute, once");
    }

    @Test
    void aMutedPlayerMayStillRunACommandTheMuteDoesNotCover() {
        mute();

        CommandExecuteEvent event = run("help");

        assertTrue(event.getResult().isAllowed(), "only the configured commands are covered");
    }

    @Test
    void aPlayerWithoutAMuteMayRunAMutedCommand() {
        CommandExecuteEvent event = run("msg bob hello");

        assertTrue(event.getResult().isAllowed());
        assertTrue(this.recording.messages.isEmpty());
    }

    @Test
    void anUnknownMuteStateNeverBlocksTheCommand() {
        this.backendDown = true;

        CommandExecuteEvent event = run("msg bob hello");

        assertTrue(event.getResult().isAllowed(), "the mute state being unknown never blocks a player");
    }
}
