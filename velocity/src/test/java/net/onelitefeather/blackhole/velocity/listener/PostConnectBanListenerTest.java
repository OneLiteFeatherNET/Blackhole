package net.onelitefeather.blackhole.velocity.listener;

import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.velocity.RecordingProxy;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.cloudnet.NoCloudNetView;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerResolver;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.enforcement.EnforcementActions;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncHandler;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ban that is committed while a player logs in must still disconnect them once the session exists.
 * The events are scripted: the login read, the ban message and the first connect happen in a fixed order.
 */
class PostConnectBanListenerTest {

    private final RecordingProxy recording = new RecordingProxy(null, "lobby");
    private final Player player = this.recording.player();
    private final String owner = UUIDConverter.convertToSHA(this.recording.uuid);
    private final MutableClock clock = new MutableClock();
    private final SectionRegistry sections = TestData.standardRegistry();
    private final PunishmentMirror mirror = new PunishmentMirror(clock);
    private final List<Player> connected = new ArrayList<>();
    private Optional<PunishProfileDTO> backend = Optional.empty();
    private Runnable duringLoginRead = () -> { };
    private int backendReads;

    private final PunishmentState state = new PunishmentState(sections, mirror, owner -> {
        this.backendReads++;
        Optional<PunishProfileDTO> snapshot = this.backend;
        Runnable scripted = this.duringLoginRead;
        this.duringLoginRead = () -> { };
        scripted.run();
        return snapshot;
    }, clock, () -> true, Duration.ofSeconds(60));

    private final ServerResolver servers = new ServerResolver(new NoCloudNetView(), sections, ignored -> { });
    private final EnforcementActions actions = new EnforcementActions(connectedProxy(), sections, state, new BlackholeConfig(), clock,
            RecordingProxy.screens(sections), servers);
    private final PunishmentSyncHandler sync = new PunishmentSyncHandler(mirror, actions);
    private final PostConnectBanListener listener = new PostConnectBanListener(state, actions);

    private ProxyServer connectedProxy() {
        return (ProxyServer) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ProxyServer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAllPlayers" -> List.copyOf(this.connected);
                    case "getServer" -> Optional.empty();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private PunishmentSyncMessage networkBanMessage() {
        return new PunishmentSyncMessage(this.owner, "network", "BAN", PunishmentSyncMessage.STATE_SET, "p1", null, "r", null, false);
    }

    private PunishProfileDTO networkBan() {
        PunishEntryDTO entry = new PunishEntryDTO().identifier("p1").section("network").permanent(true).lifted(false)
                .metaData(Map.of("reason", "cheating"));
        return new PunishProfileDTO().owner(this.owner).active(Map.of("network", entry));
    }

    @Test
    void aBanCommittedDuringTheLoginReadDisconnectsThePlayerOnTheFirstConnect() {
        this.duringLoginRead = () -> {
            this.backend = Optional.of(networkBan());
            this.sync.accept(networkBanMessage());
        };

        assertTrue(state.onLogin(this.owner).isEmpty(), "the read started before the ban, so the login is allowed");
        this.connected.add(this.player);
        this.listener.onServerPostConnect(new ServerPostConnectEvent(this.player, null));

        assertEquals(1, this.recording.disconnects.size(), "the ban committed during the login must not be lost");
    }

    @Test
    void aBanAppliedAfterTheLoginButBeforeTheFirstConnectDisconnectsThePlayer() {
        assertTrue(state.onLogin(this.owner).isEmpty());
        this.sync.accept(networkBanMessage());
        this.backend = Optional.of(networkBan());
        this.connected.add(this.player);

        this.listener.onServerPostConnect(new ServerPostConnectEvent(this.player, null));

        assertEquals(1, this.recording.disconnects.size(), "the player was not yet registered when the ban arrived");
    }

    @Test
    void aServerSwitchDoesNotReadTheBackendAgain() {
        assertTrue(state.onLogin(this.owner).isEmpty());
        this.connected.add(this.player);

        this.listener.onServerPostConnect(new ServerPostConnectEvent(this.player, RecordingProxy.server("lobby")));

        assertEquals(1, this.backendReads, "only the first connect after the login re-checks the ban");
    }

    @Test
    void aFirstConnectWithoutABanKeepsThePlayerConnected() {
        assertTrue(state.onLogin(this.owner).isEmpty());
        this.connected.add(this.player);

        this.listener.onServerPostConnect(new ServerPostConnectEvent(this.player, null));

        assertTrue(this.recording.disconnects.isEmpty(), "nothing to enforce, so the player stays");
    }
}
