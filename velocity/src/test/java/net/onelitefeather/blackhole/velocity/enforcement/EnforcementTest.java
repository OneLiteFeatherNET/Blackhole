package net.onelitefeather.blackhole.velocity.enforcement;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static net.onelitefeather.blackhole.velocity.TestData.active;
import static net.onelitefeather.blackhole.velocity.TestData.standardRegistry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnforcementTest {

    private static final long NOW = 1_000_000L;

    private final SectionRegistry sections = standardRegistry();

    @Test
    void anActiveNetworkPunishmentDeniesTheLogin() {
        var ban = active("network", EnforcementKind.BAN, "p1", NOW + 1000);

        assertEquals("p1", Enforcement.loginBan(List.of(ban), sections, NOW).orElseThrow().punishmentIdentifier());
    }

    @Test
    void aMuteAndAServerBanDoNotDenyTheLogin() {
        var mute = active("chat", EnforcementKind.MUTE, "p1", null);
        var serverBan = active("bedwars", EnforcementKind.SERVER, "p2", null);

        assertTrue(Enforcement.loginBan(List.of(mute, serverBan), sections, NOW).isEmpty(),
                "only BAN sections keep a player off the proxy");
    }

    @Test
    void aPermanentBanDeniesTheLogin() {
        assertTrue(Enforcement.loginBan(List.of(active("network", EnforcementKind.BAN, "p1", null)), sections, NOW).isPresent());
    }

    @Test
    void anExpiredBanNoLongerDeniesTheLogin() {
        var expired = active("network", EnforcementKind.BAN, "p1", NOW - 1);

        assertTrue(Enforcement.loginBan(List.of(expired), sections, NOW).isEmpty(), "a ban past its expiry is over");
    }

    @Test
    void aClearedMessageIsNotAnActivePunishment() {
        var cleared = net.onelitefeather.blackhole.velocity.TestData.cleared("network", EnforcementKind.BAN, "p1");

        assertTrue(Enforcement.loginBan(List.of(cleared), sections, NOW).isEmpty());
    }

    @Test
    void anActiveChatPunishmentMutesThePlayer() {
        var mute = active("chat", EnforcementKind.MUTE, "p1", NOW + 1000);

        assertTrue(Enforcement.mute(List.of(mute), sections, NOW).isPresent());
    }

    @Test
    void aBanDoesNotMuteThePlayer() {
        assertTrue(Enforcement.mute(List.of(active("network", EnforcementKind.BAN, "p1", null)), sections, NOW).isEmpty());
    }

    @Test
    void aServerBanDeniesOnlyMatchingServers() {
        var serverBan = active("bedwars", EnforcementKind.SERVER, "p1", null);

        assertTrue(Enforcement.serverBan(List.of(serverBan), sections, "bedwars-3", Optional.empty(), NOW).isPresent(), "bedwars-3 matches bedwars-*");
        assertTrue(Enforcement.serverBan(List.of(serverBan), sections, "skywars-1", Optional.empty(), NOW).isEmpty(), "skywars-1 stays reachable");
    }

    @Test
    void aNetworkBanIsNotAServerBan() {
        assertTrue(Enforcement.serverBan(List.of(active("network", EnforcementKind.BAN, "p1", null)), sections, "bedwars-1", Optional.empty(), NOW).isEmpty());
    }

    @Test
    void theSectionAsTheProxyKnowsItDecidesTheEnforcement() {
        var message = new PunishmentSyncMessage("o", "chat", "BAN", PunishmentSyncMessage.STATE_SET, "p1", null, "r", null);

        assertEquals(EnforcementKind.MUTE, Enforcement.kindOf(message, sections).orElseThrow(),
                "chat is a MUTE section here, whatever the message claims");
    }

    @Test
    void theMessagesEnforcementIsUsedForASectionTheProxyDoesNotKnowYet() {
        var message = new PunishmentSyncMessage("o", "newsection", "BAN", PunishmentSyncMessage.STATE_SET, "p1", null, "r", null);

        assertEquals(EnforcementKind.BAN, Enforcement.kindOf(message, sections).orElseThrow());
    }

    @Test
    void anUnknownSectionWithoutEnforcementIsNotEnforced() {
        var message = new PunishmentSyncMessage("o", "newsection", null, PunishmentSyncMessage.STATE_SET, "p1", null, "r", null);

        assertTrue(Enforcement.kindOf(message, sections).isEmpty());
        assertFalse(Enforcement.loginBan(List.of(message), sections, NOW).isPresent());
    }

    @Test
    void aNewServerBanCoversThePlayersCurrentServerOnlyIfItMatches() {
        var serverBan = active("bedwars", EnforcementKind.SERVER, "p1", null);

        assertTrue(Enforcement.coversServer(serverBan, sections, "bedwars-1", Optional.empty(), NOW), "the player stands on a banned server");
        assertFalse(Enforcement.coversServer(serverBan, sections, "lobby-1", Optional.empty(), NOW), "the player stands elsewhere");
    }
}
