package net.onelitefeather.blackhole.velocity.enforcement;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static net.onelitefeather.blackhole.velocity.TestData.OWNER;
import static net.onelitefeather.blackhole.velocity.TestData.active;
import static net.onelitefeather.blackhole.velocity.TestData.standardRegistry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The backend is the truth; the mirror is trusted only while the bus is connected and the entry is young. */
class PunishmentStateTest {

    private static final Duration MAX_AGE = Duration.ofSeconds(60);

    private final MutableClock clock = new MutableClock();
    private final PunishmentMirror mirror = new PunishmentMirror(clock);
    private boolean trusted = true;
    private Optional<PunishProfileDTO> profile = Optional.empty();
    private boolean backendDown;
    private int profileCalls;

    private final PunishmentState state = new PunishmentState(
            standardRegistry(), mirror,
            owner -> {
                this.profileCalls++;
                if (this.backendDown) {
                    throw new IOException("backend down");
                }
                return this.profile;
            },
            clock, () -> this.trusted, MAX_AGE);

    private static PunishProfileDTO profileWith(String section, boolean permanent, Long expiresAt) {
        Map<String, Object> meta = new HashMap<>(Map.of("reason", "cheating"));
        if (expiresAt != null) {
            meta.put("expiration_date", expiresAt);
        }
        PunishEntryDTO entry = new PunishEntryDTO().identifier("p1").section(section).permanent(permanent).lifted(false).metaData(meta);
        return new PunishProfileDTO().owner(OWNER).active(Map.of(section, entry));
    }

    private void givenTheBackendHoldsAMute() {
        this.profile = Optional.of(profileWith("chat", true, null));
    }

    @Test
    void aBanInTheProfileDeniesTheLogin() {
        profile = Optional.of(profileWith("network", true, null));

        assertEquals("p1", state.onLogin(OWNER).orElseThrow().punishmentIdentifier());
    }

    @Test
    void aPlayerWithoutPunishmentsMayLogIn() {
        assertTrue(state.onLogin(OWNER).isEmpty());
    }

    @Test
    void theLoginAlwaysReadsTheBackendEvenWithATrustedCache() {
        state.onLogin(OWNER);
        state.onLogin(OWNER);

        assertEquals(2, profileCalls, "a login is the seed of the cache, never served from it");
    }

    @Test
    void aMuteDoesNotDenyTheLoginButIsKnownForChat() {
        givenTheBackendHoldsAMute();

        assertTrue(state.onLogin(OWNER).isEmpty(), "a mute keeps nobody off the proxy");
        assertTrue(state.activeMute(OWNER).isPresent());
        assertEquals(1, profileCalls, "the login loaded the mute, so chat needs no further lookup");
    }

    @Test
    void whenNothingAnswersTheLoginStateIsReportedUnavailable() {
        backendDown = true;

        assertThrows(PunishmentState.Unavailable.class, () -> state.onLogin(OWNER), "the caller decides to allow the login");
    }

    @Test
    void aKnownMuteStateIsServedFromTheMirrorWhileTrusted() {
        state.onLogin(OWNER);
        backendDown = true;

        assertTrue(state.activeMute(OWNER).isEmpty(), "the empty state loaded at login is definitive");
        assertEquals(1, profileCalls);
    }

    @Test
    void anUnknownMuteStateIsFetchedOnceAndCached() {
        state.activeMute(OWNER);
        state.activeMute(OWNER);

        assertEquals(1, profileCalls, "the second message is served from the mirror");
    }

    @Test
    void anUnknownMuteStateWithTheBackendDownIsReportedUnavailable() {
        backendDown = true;

        assertThrows(PunishmentState.Unavailable.class, () -> state.activeMute(OWNER));
    }

    @Test
    void withoutTrustEveryChatCheckReadsTheBackend() {
        state.onLogin(OWNER);
        trusted = false;
        givenTheBackendHoldsAMute();

        assertTrue(state.activeMute(OWNER).isPresent(), "a mute applied while the bus was down is found");
        state.activeMute(OWNER);

        assertEquals(3, profileCalls, "login plus one read per check");
    }

    @Test
    void withoutTrustAndWithTheBackendDownTheCheckIsUnavailableNeverAllowedFromTheCache() {
        givenTheBackendHoldsAMute();
        state.onLogin(OWNER);
        trusted = false;
        backendDown = true;

        assertThrows(PunishmentState.Unavailable.class, () -> state.activeMute(OWNER),
                "a cached mute must not be trusted, and the caller applies the backend-unreachable rule");
    }

    @Test
    void anEntryOlderThanTheMaximumAgeIsReadAgain() {
        state.onLogin(OWNER);
        clock.advance(MAX_AGE.plusSeconds(1));
        givenTheBackendHoldsAMute();

        assertTrue(state.activeMute(OWNER).isPresent(), "a lost event heals once the entry expires");
        assertEquals(2, profileCalls);
    }

    @Test
    void anEntryYoungerThanTheMaximumAgeIsNotReadAgain() {
        state.onLogin(OWNER);
        clock.advance(MAX_AGE.minusSeconds(1));

        state.activeMute(OWNER);

        assertEquals(1, profileCalls);
    }

    @Test
    void aServerBanForAnUnknownStateWithTheBackendDownDoesNotLockThePlayerOut() {
        backendDown = true;

        assertTrue(state.activeServerBan(OWNER, "bedwars-1", Optional.empty()).isEmpty());
    }

    @Test
    void aServerBanDeniesOnlyTheMatchingServer() {
        profile = Optional.of(profileWith("bedwars", true, null));
        state.onLogin(OWNER);

        assertTrue(state.activeServerBan(OWNER, "bedwars-2", Optional.empty()).isPresent());
        assertTrue(state.activeServerBan(OWNER, "lobby-1", Optional.empty()).isEmpty());
    }

    @Test
    void aPunishmentAnnouncedAfterLoginIsPickedUpFromTheMirror() {
        state.onLogin(OWNER);

        mirror.apply(active("chat", EnforcementKind.MUTE, "p9", null));

        assertTrue(state.activeMute(OWNER).isPresent(), "the next chat message is denied without contacting the backend");
        assertEquals(1, profileCalls);
    }
}
