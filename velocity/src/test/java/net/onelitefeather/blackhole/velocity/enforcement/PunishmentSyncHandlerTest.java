package net.onelitefeather.blackhole.velocity.enforcement;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.RecordingProxy;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.cloudnet.NoCloudNetView;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerResolver;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sync message keeps the mirror current; a newly active BAN disconnects the player if they are connected here. */
class PunishmentSyncHandlerTest {

    private final MutableClock clock = new MutableClock();
    private final SectionRegistry sections = TestData.standardRegistry();
    private final RecordingProxy recording = new RecordingProxy(null, "lobby");
    private final Player player = this.recording.player();
    private final String owner = UUIDConverter.convertToSHA(this.recording.uuid);
    private final PunishmentMirror mirror = new PunishmentMirror(this.clock);
    private final PunishmentState state = new PunishmentState(this.sections, this.mirror, owner -> Optional.empty(), this.clock,
            () -> true, Duration.ofMinutes(1));
    private boolean playerOnline = true;
    private final EnforcementActions actions = new EnforcementActions(
            connectedProxy(), this.sections, this.state,
            new BlackholeConfig(), this.clock, RecordingProxy.screens(this.sections),
            new ServerResolver(new NoCloudNetView(), this.sections, ignored -> { }));
    private final PunishmentSyncHandler handler = new PunishmentSyncHandler(this.mirror, this.actions);

    private ProxyServer connectedProxy() {
        return (ProxyServer) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ProxyServer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAllPlayers" -> this.playerOnline ? List.of(this.player) : List.of();
                    case "getServer" -> Optional.empty();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private PunishmentSyncMessage set(String section, String kind, String id) {
        return new PunishmentSyncMessage(this.owner, section, kind, PunishmentSyncMessage.STATE_SET, id, null, "reason", null, false);
    }

    private PunishmentSyncMessage cleared(String section, String kind, String id) {
        return new PunishmentSyncMessage(this.owner, section, kind, PunishmentSyncMessage.STATE_CLEARED, id, null, null, null, false);
    }

    @Test
    void aNewBanUpdatesTheMirrorAndDisconnectsTheConnectedPlayer() {
        this.mirror.load(this.owner, List.of());

        this.handler.accept(set("network", "BAN", "p1"));

        assertEquals(1, this.mirror.active(this.owner).size(), "the ban is in the mirror");
        assertEquals(1, this.recording.disconnects.size(), "the connected player is removed");
    }

    @Test
    void aClearedMessageEmptiesTheSectionWithoutDisconnecting() {
        this.mirror.load(this.owner, List.of(set("network", "BAN", "p1")));

        this.handler.accept(cleared("network", "BAN", "p1"));

        assertTrue(this.mirror.active(this.owner).isEmpty(), "the lifted ban leaves the mirror");
        assertTrue(this.recording.disconnects.isEmpty(), "nothing is enforced when a punishment ends");
    }

    @Test
    void aNewMuteIsKnownToChatButDoesNotDisconnect() {
        this.mirror.load(this.owner, List.of());

        this.handler.accept(set("chat", "MUTE", "p2"));

        assertEquals(1, this.mirror.active(this.owner).size());
        assertTrue(this.recording.disconnects.isEmpty(), "a mute is checked by chat, never by a disconnect");
    }

    @Test
    void aMessageForAPlayerOfAnotherProxyIsNotKeptAndNotEnforced() {
        this.playerOnline = false;

        this.handler.accept(set("network", "BAN", "p1"));

        assertFalse(this.mirror.isLoaded(this.owner), "players that are not loaded here must not pile up in the mirror");
        assertTrue(this.recording.disconnects.isEmpty());
    }
}
