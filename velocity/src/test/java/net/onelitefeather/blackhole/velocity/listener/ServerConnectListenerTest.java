package net.onelitefeather.blackhole.velocity.listener;

import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import net.onelitefeather.blackhole.velocity.RecordingProxy;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.cloudnet.NoCloudNetView;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerResolver;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A SERVER punishment keeps the player off the covered servers; the backend being unreachable never does. */
class ServerConnectListenerTest {

    private final MutableClock clock = new MutableClock();
    private final SectionRegistry sections = TestData.standardRegistry();
    private final PunishmentMirror mirror = new PunishmentMirror(this.clock);
    private boolean backendDown;
    private int backendReads;
    private final PunishmentState state = new PunishmentState(this.sections, this.mirror, owner -> {
        this.backendReads++;
        if (this.backendDown) {
            throw new IllegalStateException("backend down");
        }
        return Optional.empty();
    }, this.clock, () -> true, Duration.ofMinutes(1));
    private final ServerResolver servers = new ServerResolver(new NoCloudNetView(), this.sections, ignored -> { });

    private void bedwarsBanOf(RecordingProxy recording) {
        String owner = UUIDConverter.convertToSHA(recording.uuid);
        this.mirror.load(owner, List.of(new PunishmentSyncMessage(owner, "bedwars", "SERVER", PunishmentSyncMessage.STATE_SET,
                "p1", null, "cheating", null, false)));
    }

    @Test
    void aServerBanDeniesTheConnectAndTheConnectedPlayerStaysWhereTheyAre() {
        RecordingProxy recording = new RecordingProxy("lobby", "bedwars-1", "lobby");
        bedwarsBanOf(recording);
        ServerPreConnectEvent event = new ServerPreConnectEvent(recording.player(), RecordingProxy.server("bedwars-1"));

        new ServerConnectListener(this.state, RecordingProxy.screens(this.sections), this.servers).onServerPreConnect(event);

        assertFalse(event.getResult().isAllowed(), "the connect to a banned server is denied");
        assertEquals(1, recording.messages.size(), "the player is told why, in chat");
        assertTrue(recording.disconnects.isEmpty(), "a player who stands somewhere is not disconnected");
    }

    @Test
    void aServerBanDisconnectsAPlayerWhoHasNoServerYet() {
        RecordingProxy recording = new RecordingProxy(null, "bedwars-1", "lobby");
        bedwarsBanOf(recording);
        ServerPreConnectEvent event = new ServerPreConnectEvent(recording.player(), RecordingProxy.server("bedwars-1"));

        new ServerConnectListener(this.state, RecordingProxy.screens(this.sections), this.servers).onServerPreConnect(event);

        assertFalse(event.getResult().isAllowed());
        assertEquals(1, recording.disconnects.size(), "there is nowhere to stay, so the player is disconnected");
    }

    @Test
    void aConnectWithoutAServerBanIsAllowed() {
        RecordingProxy recording = new RecordingProxy("lobby", "bedwars-1", "lobby");
        ServerPreConnectEvent event = new ServerPreConnectEvent(recording.player(), RecordingProxy.server("bedwars-1"));

        new ServerConnectListener(this.state, RecordingProxy.screens(this.sections), this.servers).onServerPreConnect(event);

        assertTrue(event.getResult().isAllowed(), "nothing denies the connect");
        assertTrue(recording.messages.isEmpty() && recording.disconnects.isEmpty());
    }

    @Test
    void aBackendOutageNeverDeniesTheConnect() {
        RecordingProxy recording = new RecordingProxy("lobby", "bedwars-1", "lobby");
        this.backendDown = true;
        ServerPreConnectEvent event = new ServerPreConnectEvent(recording.player(), RecordingProxy.server("bedwars-1"));

        new ServerConnectListener(this.state, RecordingProxy.screens(this.sections), this.servers).onServerPreConnect(event);

        assertTrue(event.getResult().isAllowed(), "an unknown server ban state lets the connect through");
        assertEquals(1, this.backendReads, "the state was asked for once, and the outage is not retried per connect");
    }
}
