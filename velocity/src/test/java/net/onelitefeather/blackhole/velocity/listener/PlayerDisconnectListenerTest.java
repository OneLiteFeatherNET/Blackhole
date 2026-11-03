package net.onelitefeather.blackhole.velocity.listener;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.SessionOutcome;
import net.onelitefeather.blackhole.velocity.RecordingProxy;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNoticeCooldown;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNotices;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.evidence.ChatBuffer;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import net.onelitefeather.blackhole.velocity.intel.RecordingSessionBackend;
import net.onelitefeather.blackhole.velocity.intel.SessionPlayer;
import net.onelitefeather.blackhole.velocity.intel.SessionReporter;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Leaving the proxy ends the session and drops the player's mirrored state; nothing else of anyone is touched. */
class PlayerDisconnectListenerTest {

    private final MutableClock clock = new MutableClock();
    private final PunishmentMirror mirror = new PunishmentMirror(clock);
    private final RecordingSessionBackend backend = new RecordingSessionBackend();
    private final SessionReporter sessions = new SessionReporter(this.backend, Runnable::run, UUID::randomUUID);
    private final ChatBuffer buffer = new ChatBuffer(this.clock, new ChatCaptureConfig());
    private final MuteNotices notices = new MuteNotices(RecordingProxy.screens(TestData.standardRegistry()),
            new MuteNoticeCooldown(this.clock, Duration.ofSeconds(30)));
    private final PlayerDisconnectListener listener = new PlayerDisconnectListener(this.mirror, this.buffer, this.notices, this.sessions);

    private static DisconnectEvent leaving(Player player) {
        return new DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);
    }

    @Test
    void aDisconnectDropsTheMirroredStateOfThePlayer() {
        Player alice = SessionPlayer.alice();
        String owner = UUIDConverter.convertToSHA(alice.getUniqueId());
        this.mirror.load(owner, List.of(TestData.active("network", EnforcementKind.BAN, "p1", null)));

        this.listener.onDisconnect(leaving(alice));

        assertFalse(this.mirror.isLoaded(owner), "a player who left must not stay in the mirror");
    }

    @Test
    void aDisconnectLeavesTheMirrorOfEveryoneElseAlone() {
        Player alice = SessionPlayer.alice();
        String bob = UUIDConverter.convertToSHA(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
        this.mirror.load(bob, List.of());

        this.listener.onDisconnect(leaving(alice));

        assertTrue(this.mirror.isLoaded(bob), "only the leaving player is forgotten");
    }

    @Test
    void aDisconnectEndsTheSessionOfThePlayer() {
        Player alice = SessionPlayer.alice();
        this.sessions.reportStart(alice, SessionOutcome.ALLOWED);

        this.listener.onDisconnect(leaving(alice));

        assertEquals(1, this.backend.updates().size(), "the session is reported as ended once");
        assertTrue(this.backend.updates().getFirst().request().getEnded(), "the update marks the session as ended");
    }
}
