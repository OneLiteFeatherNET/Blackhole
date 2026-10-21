package net.onelitefeather.blackhole.velocity.enforcement;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static net.onelitefeather.blackhole.velocity.TestData.OWNER;
import static net.onelitefeather.blackhole.velocity.TestData.active;
import static net.onelitefeather.blackhole.velocity.TestData.standardRegistry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentStateTest {

    private static final long NOW = 1_000_000L;

    private final PunishmentMirror mirror = new PunishmentMirror();
    private final Map<String, PunishmentSyncMessage> redis = new HashMap<>();
    private boolean redisDown;
    private Optional<PunishProfileDTO> profile = Optional.empty();
    private boolean backendDown;
    private int profileCalls;

    private final PunishmentState state = new PunishmentState(
            standardRegistry(), mirror,
            (section, owner) -> {
                if (this.redisDown) {
                    throw new IllegalStateException("Redis is not connected");
                }
                return Optional.ofNullable(this.redis.get(section + ":" + owner));
            },
            owner -> {
                this.profileCalls++;
                if (this.backendDown) {
                    throw new IOException("backend down");
                }
                return this.profile;
            },
            Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

    private void givenRedisHolds(PunishmentSyncMessage message) {
        this.redis.put(message.section() + ":" + message.owner(), message);
    }

    private static PunishProfileDTO profileWithBan(String section, long expiresAt) {
        PunishEntryDTO entry = new PunishEntryDTO().identifier("p1").section(section).permanent(false).lifted(false)
                .metaData(new HashMap<>(Map.of("reason", "cheating", "expiration_date", expiresAt)));
        return new PunishProfileDTO().owner(OWNER).active(Map.of(section, entry));
    }

    @Test
    void aBanInRedisDeniesTheLogin() {
        givenRedisHolds(active("network", EnforcementKind.BAN, "p1", NOW + 1000));

        assertEquals("p1", state.onLogin(OWNER).orElseThrow().punishmentIdentifier());
        assertEquals(0, profileCalls, "Redis answered, no HTTP call needed");
    }

    @Test
    void aPlayerWithoutPunishmentsMayLogIn() {
        assertTrue(state.onLogin(OWNER).isEmpty());
    }

    @Test
    void aMuteInRedisDoesNotDenyTheLoginButIsKnownForChat() {
        givenRedisHolds(active("chat", EnforcementKind.MUTE, "p1", null));

        assertTrue(state.onLogin(OWNER).isEmpty(), "a mute keeps nobody off the proxy");
        assertTrue(state.activeMute(OWNER).isPresent(), "the login loaded the mute, so chat needs no further lookup");
        assertEquals(0, profileCalls);
    }

    @Test
    void whenRedisIsDownTheBackendProfileDecidesTheLogin() {
        redisDown = true;
        profile = Optional.of(profileWithBan("network", NOW + 5000));

        assertTrue(state.onLogin(OWNER).isPresent(), "the same decision as with Redis");
        assertEquals(1, profileCalls);
    }

    @Test
    void whenRedisIsDownAndTheBackendHasNoProfileTheLoginIsAllowed() {
        redisDown = true;

        assertTrue(state.onLogin(OWNER).isEmpty());
    }

    @Test
    void whenNothingAnswersTheLoginStateIsReportedUnavailable() {
        redisDown = true;
        backendDown = true;

        assertThrows(PunishmentState.Unavailable.class, () -> state.onLogin(OWNER), "the caller decides to allow the login");
    }

    @Test
    void aKnownMuteStateIsServedFromTheMirrorWithoutAnyLookup() {
        state.onLogin(OWNER);
        redisDown = true;
        backendDown = true;

        assertTrue(state.activeMute(OWNER).isEmpty(), "the empty state loaded at login is definitive");
        assertEquals(0, profileCalls);
    }

    @Test
    void anUnknownMuteStateIsFetchedOnceAndCached() {
        redisDown = true;
        profile = Optional.of(new PunishProfileDTO().owner(OWNER).active(Map.of()));

        state.activeMute(OWNER);
        state.activeMute(OWNER);

        assertEquals(1, profileCalls, "the second message is served from the mirror");
    }

    @Test
    void anUnknownMuteStateWithTheBackendDownIsReportedUnavailable() {
        redisDown = true;
        backendDown = true;

        assertThrows(PunishmentState.Unavailable.class, () -> state.activeMute(OWNER));
    }

    @Test
    void aServerBanForAnUnknownStateWithTheBackendDownDoesNotLockThePlayerOut() {
        redisDown = true;
        backendDown = true;

        assertTrue(state.activeServerBan(OWNER, "bedwars-1").isEmpty());
    }

    @Test
    void aServerBanDeniesOnlyTheMatchingServer() {
        givenRedisHolds(active("bedwars", EnforcementKind.SERVER, "p1", null));
        state.onLogin(OWNER);

        assertTrue(state.activeServerBan(OWNER, "bedwars-2").isPresent());
        assertTrue(state.activeServerBan(OWNER, "lobby-1").isEmpty());
    }

    @Test
    void aPunishmentAnnouncedAfterLoginIsPickedUpFromTheMirror() {
        state.onLogin(OWNER);

        mirror.apply(active("chat", EnforcementKind.MUTE, "p9", null));

        assertTrue(state.activeMute(OWNER).isPresent(), "the next chat message is denied without contacting the backend");
    }
}
