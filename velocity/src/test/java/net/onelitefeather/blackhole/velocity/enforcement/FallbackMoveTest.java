package net.onelitefeather.blackhole.velocity.enforcement;

import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.RecordingProxy;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.cloudnet.NoCloudNetView;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerResolver;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one fallback rule: what happens to a player taken off a server, no matter why. No proxy runs. */
class FallbackMoveTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);
    private final SectionRegistry sections = TestData.standardRegistry();

    private EnforcementActions actions(RecordingProxy fixture) {
        var state = new PunishmentState(this.sections, new PunishmentMirror(CLOCK),
                owner -> Optional.empty(), CLOCK, () -> true, java.time.Duration.ofSeconds(60));
        return new EnforcementActions(fixture.proxy(fixture.player()), this.sections, state, new BlackholeConfig(), CLOCK,
                RecordingProxy.screens(this.sections), new ServerResolver(new NoCloudNetView(), this.sections, warning -> { }));
    }

    @Test
    void aUsableFallbackServerReceivesThePlayer() {
        var fixture = new RecordingProxy("bedwars-1", "lobby", "bedwars-1");
        List<Boolean> asked = new ArrayList<>();

        boolean moved = actions(fixture).moveToFallback(fixture.player(), result -> {
            asked.add(result);
            return Component.text("screen");
        }, name -> false).join();

        assertTrue(moved, "reported as moved");
        assertEquals(List.of("lobby"), fixture.movedTo);
        assertEquals(List.of(true), asked, "the moved screen was asked for");
        assertEquals(1, fixture.messages.size(), "told in chat");
        assertTrue(fixture.disconnects.isEmpty());
    }

    @Test
    void anUnusableFallbackServerDisconnectsThePlayer() {
        var fixture = new RecordingProxy("bedwars-1", "lobby", "bedwars-1");

        boolean moved = actions(fixture).moveToFallback(fixture.player(), result -> Component.text("screen"),
                name -> name.equals("lobby")).join();

        assertFalse(moved, "reported as disconnected");
        assertTrue(fixture.movedTo.isEmpty(), "no move");
        assertEquals(1, fixture.disconnects.size());
    }

    @Test
    void aMissingFallbackServerDisconnectsThePlayer() {
        var fixture = new RecordingProxy("bedwars-1", "bedwars-1");

        boolean moved = actions(fixture).moveToFallback(fixture.player(), result -> Component.text("screen"), name -> false).join();

        assertFalse(moved);
        assertEquals(1, fixture.disconnects.size());
    }

    @Test
    void aServerPunishmentMovesThroughTheSameRule() {
        var fixture = new RecordingProxy("bedwars-1", "lobby", "bedwars-1");
        var message = new PunishmentSyncMessage(UUIDConverter.convertToSHA(fixture.uuid), "bedwars", EnforcementKind.SERVER.name(),
                PunishmentSyncMessage.STATE_SET, "a1b2c3d4-0000-4000-8000-000000000001", null, "Griefing", null, false);

        actions(fixture).enforce(message);

        assertEquals(List.of("lobby"), fixture.movedTo);
        assertEquals(1, fixture.messages.size());
    }
}
