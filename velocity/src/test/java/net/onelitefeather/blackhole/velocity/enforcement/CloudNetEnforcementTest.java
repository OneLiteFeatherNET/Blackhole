package net.onelitefeather.blackhole.velocity.enforcement;

import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.ServerTargetKind;
import net.onelitefeather.blackhole.velocity.RecordingProxy;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.cloudnet.CloudNetView;
import net.onelitefeather.blackhole.velocity.cloudnet.FakeCloudNetView;
import net.onelitefeather.blackhole.velocity.cloudnet.NoCloudNetView;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerResolver;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.listener.ServerConnectListener;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CloudNet sections on the two enforcement paths: denying a connect and moving a player off a server
 * that has just become banned. CloudNet is a fake view; no proxy runs.
 */
class CloudNetEnforcementTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);

    private final List<String> warnings = new ArrayList<>();
    private final Map<String, PunishmentSyncMessage> redis = new HashMap<>();
    private final FakeCloudNetView cloudNet = new FakeCloudNetView(true)
            .service("lobby-1", "Lobby", "Global", "Minigames")
            .service("BedWars-3", "BedWars", "Global", "Minigames")
            .service("BedWars-4", "BedWars", "Global", "Minigames")
            .service("SkyWars-1", "SkyWars", "Global");

    private static SectionInfo section(String key, ServerTargetKind kind, String... names) {
        return new SectionInfo(key, key, EnforcementKind.SERVER, List.of(names), kind);
    }

    private PunishmentSyncMessage ban(RecordingProxy fixture, String section) {
        String owner = UUIDConverter.convertToSHA(fixture.uuid);
        PunishmentSyncMessage message = new PunishmentSyncMessage(owner, section, "SERVER", PunishmentSyncMessage.STATE_SET,
                "a1b2c3d4-0000-4000-8000-000000000001", null, "Griefing", null);
        this.redis.put(section + ":" + owner, message);
        return message;
    }

    private PunishmentState state(SectionRegistry sections) {
        return new PunishmentState(sections, new PunishmentMirror(), (section, owner) -> Optional.ofNullable(this.redis.get(section + ":" + owner)),
                owner -> Optional.empty(), CLOCK);
    }

    private ServerPreConnectEvent connect(CloudNetView view, SectionRegistry sections, RecordingProxy fixture, String target) {
        ServerResolver servers = new ServerResolver(view, sections, this.warnings::add);
        ServerConnectListener listener = new ServerConnectListener(state(sections), RecordingProxy.screens(sections), servers);
        ServerPreConnectEvent event = new ServerPreConnectEvent(fixture.player(), RecordingProxy.server(target));
        listener.onServerPreConnect(event);
        return event;
    }

    private ServerPreConnectEvent connect(SectionRegistry sections, RecordingProxy fixture, String target) {
        return connect(this.cloudNet, sections, fixture, target);
    }

    private EnforcementActions actions(CloudNetView view, SectionRegistry sections, RecordingProxy fixture) {
        Player player = fixture.player();
        return new EnforcementActions(fixture.proxy(player), sections, state(sections), new BlackholeConfig(), CLOCK,
                RecordingProxy.screens(sections), new ServerResolver(view, sections, this.warnings::add));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void aTaskBanDeniesAServiceOfTheTaskAndNamesIt() {
        var sections = TestData.registry(section("bedwars", ServerTargetKind.CLOUDNET_TASK, "BedWars"));
        var fixture = new RecordingProxy("lobby-1");
        ban(fixture, "bedwars");

        var event = connect(sections, fixture, "BedWars-3");

        assertFalse(event.getResult().isAllowed(), "the connect is denied");
        assertTrue(plain(fixture.messages.get(0)).contains("You are banned from BedWars"), plain(fixture.messages.get(0)));
        assertTrue(fixture.disconnects.isEmpty(), "the player stays in the lobby");
    }

    @Test
    void aTaskBanLeavesAnotherTaskOpen() {
        var sections = TestData.registry(section("bedwars", ServerTargetKind.CLOUDNET_TASK, "BedWars"));
        var fixture = new RecordingProxy("lobby-1");
        ban(fixture, "bedwars");

        assertTrue(connect(sections, fixture, "SkyWars-1").getResult().isAllowed());
    }

    @Test
    void aGroupBanDeniesEveryServiceInTheGroupAndNamesIt() {
        var sections = TestData.registry(section("minigames", ServerTargetKind.CLOUDNET_GROUP, "Minigames"));
        var fixture = new RecordingProxy("lobby-1");
        ban(fixture, "minigames");

        assertFalse(connect(sections, fixture, "BedWars-3").getResult().isAllowed());
        assertTrue(plain(fixture.messages.get(0)).contains("You are banned from the group Minigames"), plain(fixture.messages.get(0)));
        assertTrue(connect(sections, fixture, "SkyWars-1").getResult().isAllowed(), "SkyWars-1 is not in the group");
    }

    @Test
    void aServiceBanDeniesOnlyThatService() {
        var sections = TestData.registry(section("one", ServerTargetKind.CLOUDNET_SERVICE, "BedWars-3"));
        var fixture = new RecordingProxy("lobby-1");
        ban(fixture, "one");

        assertFalse(connect(sections, fixture, "BedWars-3").getResult().isAllowed());
        assertTrue(plain(fixture.messages.get(0)).contains("You are banned from the server BedWars-3"), plain(fixture.messages.get(0)));
        assertTrue(connect(sections, fixture, "BedWars-4").getResult().isAllowed());
    }

    @Test
    void aServiceBanFollowsTheNameAfterARestart() {
        var sections = TestData.registry(section("one", ServerTargetKind.CLOUDNET_SERVICE, "BedWars-3"));
        var fixture = new RecordingProxy("lobby-1");
        ban(fixture, "one");

        this.cloudNet.stop("BedWars-3");
        assertTrue(connect(sections, fixture, "BedWars-3").getResult().isAllowed(), "a stopped service is unknown, fail open");
        this.cloudNet.service("BedWars-3", "BedWars", "Global");

        assertFalse(connect(sections, fixture, "BedWars-3").getResult().isAllowed(), "the new instance carries the name");
    }

    @Test
    void aPlayerWithoutACurrentServerIsDisconnected() {
        var sections = TestData.registry(section("bedwars", ServerTargetKind.CLOUDNET_TASK, "BedWars"));
        var fixture = new RecordingProxy(null);
        ban(fixture, "bedwars");

        assertFalse(connect(sections, fixture, "BedWars-3").getResult().isAllowed());
        assertEquals(1, fixture.disconnects.size());
        assertTrue(plain(fixture.disconnects.get(0)).contains("You are banned from BedWars"));
    }

    @Test
    void aServerUnknownToCloudNetPassesAndWarnsOnce() {
        var sections = TestData.registry(section("bedwars", ServerTargetKind.CLOUDNET_TASK, "BedWars"));
        var fixture = new RecordingProxy("lobby-1");
        ban(fixture, "bedwars");
        var resolver = new ServerResolver(this.cloudNet, sections, this.warnings::add);
        var listener = new ServerConnectListener(state(sections), RecordingProxy.screens(sections), resolver);

        for (int i = 0; i < 2; i++) {
            var event = new ServerPreConnectEvent(fixture.player(), RecordingProxy.server("static-1"));
            listener.onServerPreConnect(event);
            assertTrue(event.getResult().isAllowed(), "fail open for a server CloudNet does not know");
        }

        assertEquals(1, this.warnings.size(), "one warning per server name: " + this.warnings);
    }

    @Test
    void withTheIntegrationOffCloudNetSectionsCoverNothing() {
        var sections = TestData.registry(section("bedwars", ServerTargetKind.CLOUDNET_TASK, "BedWars"));
        var fixture = new RecordingProxy("lobby-1");
        ban(fixture, "bedwars");

        assertTrue(connect(new NoCloudNetView(), sections, fixture, "BedWars-3").getResult().isAllowed());
        assertTrue(this.warnings.isEmpty(), "the startup line covers this: " + this.warnings);
    }

    @Test
    void aPatternSectionStillDeniesWithoutCloudNet() {
        var sections = TestData.standardRegistry();
        var fixture = new RecordingProxy("lobby-1");
        ban(fixture, "bedwars");

        var event = connect(new NoCloudNetView(), sections, fixture, "bedwars-1");

        assertFalse(event.getResult().isAllowed());
        assertTrue(plain(fixture.messages.get(0)).contains("Excluded servers: bedwars-*"), plain(fixture.messages.get(0)));
    }

    @Test
    void aNewTaskBanMovesThePlayerOffTheTaskToTheFallback() {
        var sections = TestData.registry(section("bedwars", ServerTargetKind.CLOUDNET_TASK, "BedWars"));
        var fixture = new RecordingProxy("BedWars-3", "lobby", "BedWars-3");
        var message = ban(fixture, "bedwars");

        actions(this.cloudNet, sections, fixture).enforce(message);

        assertEquals(List.of("lobby"), fixture.movedTo);
        assertTrue(fixture.disconnects.isEmpty());
        assertTrue(plain(fixture.messages.get(0)).contains("moved to the fallback server"), plain(fixture.messages.get(0)));
    }

    @Test
    void aGroupBanThatCoversTheFallbackToDisconnectsThePlayer() {
        var sections = TestData.registry(section("minigames", ServerTargetKind.CLOUDNET_GROUP, "Minigames"));
        var fixture = new RecordingProxy("BedWars-3", "lobby", "BedWars-3");
        var message = ban(fixture, "minigames");
        this.cloudNet.service("lobby", "Lobby", "Minigames");

        actions(this.cloudNet, sections, fixture).enforce(message);

        assertTrue(fixture.movedTo.isEmpty(), "no move into a deny loop");
        assertEquals(1, fixture.disconnects.size());
    }

    @Test
    void aPlayerOnAServerUnknownToCloudNetStaysInPlace() {
        var sections = TestData.registry(section("bedwars", ServerTargetKind.CLOUDNET_TASK, "BedWars"));
        var fixture = new RecordingProxy("static-1", "lobby", "static-1");
        var message = ban(fixture, "bedwars");

        actions(this.cloudNet, sections, fixture).enforce(message);

        assertTrue(fixture.movedTo.isEmpty());
        assertTrue(fixture.disconnects.isEmpty());
        assertTrue(fixture.messages.isEmpty());
    }

    @Test
    void aMissingFallbackServerDisconnectsThePlayer() {
        var sections = TestData.registry(section("bedwars", ServerTargetKind.CLOUDNET_TASK, "BedWars"));
        var fixture = new RecordingProxy("BedWars-3", "BedWars-3");
        var message = ban(fixture, "bedwars");

        actions(this.cloudNet, sections, fixture).enforce(message);

        assertEquals(1, fixture.disconnects.size());
    }
}
