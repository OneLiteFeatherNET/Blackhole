package net.onelitefeather.blackhole.velocity.enforcement;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.KickResponse;
import net.onelitefeather.blackhole.velocity.RecordingProxy;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.cloudnet.NoCloudNetView;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerResolver;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a proxy does with a kick event and what it reports back. The proxy is a recording fake and the
 * backend a stub; nothing is networked.
 */
class KickHandlerDecisionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);
    private static final String KICK_ID = "6f0c5d0e-0000-4000-8000-000000000001";

    private record Ack(String id, String proxyId, boolean found, boolean moved) {
    }

    private final SectionRegistry sections = TestData.standardRegistry();
    private final List<Ack> acks = new ArrayList<>();
    private final List<String> reads = new ArrayList<>();
    private String reason = "Please calm down";
    private ApiException readFailure;
    private ApiException ackFailure;

    private final BackendGateway backend = (BackendGateway) Proxy.newProxyInstance(
            KickHandlerDecisionTest.class.getClassLoader(), new Class<?>[]{BackendGateway.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "kickStatus" -> {
                    reads.add((String) args[0]);
                    if (readFailure != null) {
                        throw readFailure;
                    }
                    yield new KickResponse().id((String) args[0]).reason(reason);
                }
                case "acknowledgeKick" -> {
                    acks.add(new Ack((String) args[0], (String) args[1], (Boolean) args[2], (Boolean) args[3]));
                    if (ackFailure != null) {
                        throw ackFailure;
                    }
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });

    private KickHandler handler(RecordingProxy fixture) {
        var state = new PunishmentState(this.sections, new PunishmentMirror(CLOCK),
                owner -> Optional.empty(), CLOCK, () -> true, Duration.ofSeconds(60));
        var screens = RecordingProxy.screens(this.sections);
        var servers = new ServerResolver(new NoCloudNetView(), this.sections, warning -> { });
        var actions = new EnforcementActions(fixture.proxy(fixture.player()), this.sections, state, new BlackholeConfig(), CLOCK,
                screens, servers);
        return new KickHandler(actions, this.sections, servers, screens, this.backend, "proxy-1");
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static String ownerOf(RecordingProxy fixture) {
        return UUIDConverter.convertToSHA(fixture.uuid);
    }

    @Test
    void aPlayerNotConnectedHereIsReportedAsNotFound() {
        var fixture = new RecordingProxy("lobby", "lobby");

        handler(fixture).kick(new KickMessage(KICK_ID, "f".repeat(128), "NETWORK", null));

        assertEquals(List.of(new Ack(KICK_ID, "proxy-1", false, false)), this.acks);
        assertTrue(fixture.disconnects.isEmpty());
        assertTrue(this.reads.isEmpty(), "no reason is read for a player that is not here");
    }

    @Test
    void aNetworkKickDisconnectsThePlayerWithTheKickScreen() {
        var fixture = new RecordingProxy("bedwars-1", "lobby", "bedwars-1");

        handler(fixture).kick(new KickMessage(KICK_ID, ownerOf(fixture), "NETWORK", null));

        assertEquals(1, fixture.disconnects.size());
        assertTrue(plain(fixture.disconnects.getFirst()).contains("removed from the network"), plain(fixture.disconnects.getFirst()));
        assertTrue(plain(fixture.disconnects.getFirst()).contains("Please calm down"), "the reason of the backend is shown");
        assertTrue(fixture.movedTo.isEmpty());
        assertEquals(List.of(new Ack(KICK_ID, "proxy-1", true, true)), this.acks);
    }

    @Test
    void aKickOfTheServerThePlayerIsOnMovesThemToTheFallback() {
        var fixture = new RecordingProxy("bedwars-1", "lobby", "bedwars-1");

        handler(fixture).kick(new KickMessage(KICK_ID, ownerOf(fixture), "SERVER", "bedwars-1"));

        assertEquals(List.of("lobby"), fixture.movedTo);
        assertTrue(fixture.disconnects.isEmpty());
        assertEquals(List.of(new Ack(KICK_ID, "proxy-1", true, true)), this.acks);
    }

    @Test
    void aKickOfASectionTheServerIsCoveredByMovesThemToTheFallback() {
        var fixture = new RecordingProxy("bedwars-1", "lobby", "bedwars-1");

        handler(fixture).kick(new KickMessage(KICK_ID, ownerOf(fixture), "SECTION", "bedwars"));

        assertEquals(List.of("lobby"), fixture.movedTo);
        assertEquals(List.of(new Ack(KICK_ID, "proxy-1", true, true)), this.acks);
    }

    @Test
    void aKickOfAnotherServerLeavesThePlayerAloneAndIsNotOnTarget() {
        var fixture = new RecordingProxy("lobby", "lobby", "bedwars-1");

        handler(fixture).kick(new KickMessage(KICK_ID, ownerOf(fixture), "SERVER", "bedwars-1"));

        assertTrue(fixture.movedTo.isEmpty());
        assertTrue(fixture.disconnects.isEmpty());
        assertTrue(fixture.messages.isEmpty());
        assertEquals(List.of(new Ack(KICK_ID, "proxy-1", true, false)), this.acks);
    }

    @Test
    void aKickOfASectionThatDoesNotCoverTheServerIsNotOnTarget() {
        var fixture = new RecordingProxy("lobby", "lobby", "bedwars-1");

        handler(fixture).kick(new KickMessage(KICK_ID, ownerOf(fixture), "SECTION", "bedwars"));

        assertTrue(fixture.movedTo.isEmpty());
        assertEquals(List.of(new Ack(KICK_ID, "proxy-1", true, false)), this.acks);
    }

    @Test
    void aFallbackThatIsTheKickedServerDisconnectsInstead() {
        var fixture = new RecordingProxy("lobby", "lobby");

        handler(fixture).kick(new KickMessage(KICK_ID, ownerOf(fixture), "SERVER", "lobby"));

        assertTrue(fixture.movedTo.isEmpty(), "no move onto the server being kicked from");
        assertEquals(1, fixture.disconnects.size());
        assertEquals(List.of(new Ack(KICK_ID, "proxy-1", true, true)), this.acks);
    }

    @Test
    void aFailedReasonReadKicksWithTheDefaultTextAndStillAcknowledges() {
        this.readFailure = new ApiException(503, "down");
        var fixture = new RecordingProxy("bedwars-1", "lobby", "bedwars-1");

        handler(fixture).kick(new KickMessage(KICK_ID, ownerOf(fixture), "NETWORK", null));

        assertEquals(1, fixture.disconnects.size());
        assertTrue(plain(fixture.disconnects.getFirst()).contains("You were removed from the network"), plain(fixture.disconnects.getFirst()));
        assertEquals(List.of(new Ack(KICK_ID, "proxy-1", true, true)), this.acks);
    }

    @Test
    void aFailedAcknowledgementDoesNotStopTheHandler() {
        this.ackFailure = new ApiException(503, "down");
        var fixture = new RecordingProxy("bedwars-1", "lobby", "bedwars-1");

        handler(fixture).kick(new KickMessage(KICK_ID, ownerOf(fixture), "NETWORK", null));

        assertEquals(1, fixture.disconnects.size(), "the player is gone all the same");
        assertEquals(1, this.acks.size(), "the answer was tried");
    }

    @Test
    void anUnknownScopeDoesNothing() {
        var fixture = new RecordingProxy("bedwars-1", "lobby", "bedwars-1");

        handler(fixture).kick(new KickMessage(KICK_ID, ownerOf(fixture), "GALAXY", "x"));

        assertTrue(fixture.disconnects.isEmpty());
        assertTrue(fixture.movedTo.isEmpty());
    }

    @Test
    void aMassKickMovesThePlayersOnTheTargetAndCountsThemInOneAnswer() {
        var fixture = new RecordingProxy("bedwars-1", "lobby", "bedwars-1");

        handler(fixture).mass(new MassKickMessage(KICK_ID, "SECTION", "bedwars"));

        assertEquals(List.of("lobby"), fixture.movedTo);
        assertEquals(List.of(new Ack(KICK_ID, "proxy-1", true, true)), this.acks);
    }

    @Test
    void aMassKickWithNobodyOnTheTargetIsAnsweredAsFoundNobody() {
        var fixture = new RecordingProxy("lobby", "lobby", "bedwars-1");

        handler(fixture).mass(new MassKickMessage(KICK_ID, "SERVER", "bedwars-1"));

        assertTrue(fixture.movedTo.isEmpty());
        assertEquals(List.of(new Ack(KICK_ID, "proxy-1", false, false)), this.acks);
    }
}
