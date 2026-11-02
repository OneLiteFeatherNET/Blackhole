package net.onelitefeather.blackhole.backend.publicapi.challenge;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The gate with a fake verifier, a fake replay store, a fixed clock and a simple meter registry. No network, no time of day. */
class ChallengeGateTest {

    private static final Instant NOW = TestClock.START;
    private static final ChallengeVerifier.Accepted ACCEPTED = new ChallengeVerifier.Accepted("nonce-1", NOW.plusSeconds(300));
    private static final String KEY = "fakes-do-not-check-the-key";

    /** A verifier that accepts one purpose only; it counts its calls so a test can see whether it ran. */
    private static final class FakeVerifier implements ChallengeVerifier {
        private final Optional<Accepted> answer;
        private final String purpose;
        int calls;

        FakeVerifier(Optional<Accepted> answer, String purpose) {
            this.answer = answer;
            this.purpose = purpose;
        }

        @Override
        public Optional<Accepted> verify(String payload, String expectedPurpose) {
            this.calls++;
            return expectedPurpose.equals(this.purpose) ? this.answer : Optional.empty();
        }
    }

    /** A store that reserves each nonce once, unless a fixed answer is set. */
    private static final class FakeReplayStore implements ChallengeReplayStore {
        final Set<String> reserved = new HashSet<>();
        Reservation forced;
        Duration lastRemaining;
        int calls;

        @Override
        public Reservation reserve(String nonce, Duration remaining) {
            this.calls++;
            this.lastRemaining = remaining;
            if (this.forced != null) {
                return this.forced;
            }
            return this.reserved.add(nonce) ? Reservation.RESERVED : Reservation.REPLAYED;
        }
    }

    /** One gate with its fakes; each test builds its own. */
    private static final class Fixture {
        final FakeVerifier verifier;
        final FakeReplayStore store = new FakeReplayStore();
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final ChallengeGate gate;

        Fixture(boolean enabled) {
            this(enabled, new FakeVerifier(Optional.of(ACCEPTED), "sign-in"));
        }

        Fixture(boolean enabled, FakeVerifier verifier) {
            this.verifier = verifier;
            PublicChallengeProperties properties = new PublicChallengeProperties(enabled, KEY, 10, "00", Duration.ofMinutes(5), Duration.ofMillis(500));
            this.gate = new ChallengeGate(properties, verifier, this.store, new TestClock(NOW), new ChallengeMetrics(this.meters));
        }
    }

    private static double count(SimpleMeterRegistry meters, String outcome) {
        return meters.counter(ChallengeMetrics.METER, "outcome", outcome).count();
    }

    @Test
    void aValidSolutionPassesTheFirstTime() {
        Fixture f = new Fixture(true);

        assertEquals(ChallengeGate.Verdict.PASSED, f.gate.check("payload", "sign-in"), "the first use of a valid solution passes");
    }

    @Test
    void aSolutionIsRefusedTheSecondTime() {
        Fixture f = new Fixture(true);
        f.gate.check("payload", "sign-in");

        assertEquals(ChallengeGate.Verdict.REJECTED, f.gate.check("payload", "sign-in"), "the same solution is refused again");
    }

    @Test
    void aReplayIsCountedAsReplayed() {
        Fixture f = new Fixture(true);
        f.gate.check("payload", "sign-in");
        f.gate.check("payload", "sign-in");

        assertEquals(1.0, count(f.meters, "replayed"), "the replay is counted once");
    }

    @Test
    void aSolutionForAnotherPurposeIsRefused() {
        Fixture f = new Fixture(true);

        assertEquals(ChallengeGate.Verdict.REJECTED, f.gate.check("payload", "appeal"), "a sign-in solution is not an appeal solution");
    }

    @Test
    void aReserveThatCannotAnswerGivesUnavailable() {
        Fixture f = new Fixture(true);
        f.store.forced = ChallengeReplayStore.Reservation.UNAVAILABLE;

        assertEquals(ChallengeGate.Verdict.UNAVAILABLE, f.gate.check("payload", "sign-in"), "a store that does not answer fails closed");
    }

    @Test
    void anUnavailableReserveIsCountedAsUnavailable() {
        Fixture f = new Fixture(true);
        f.store.forced = ChallengeReplayStore.Reservation.UNAVAILABLE;
        f.gate.check("payload", "sign-in");

        assertEquals(1.0, count(f.meters, "unavailable"), "the outage is counted");
    }

    @Test
    void aDisabledCheckIsSkipped() {
        Fixture f = new Fixture(false);

        assertEquals(ChallengeGate.Verdict.PASSED, f.gate.check(null, "sign-in"), "with the switch off every request passes");
        assertEquals(0, f.verifier.calls, "the verifier is not run when the check is off");
    }

    @Test
    void aMissingChallengeIsRejectedWithoutVerifying() {
        Fixture f = new Fixture(true);

        assertEquals(ChallengeGate.Verdict.REJECTED, f.gate.check(null, "sign-in"), "no altcha field is refused");
        assertEquals(0, f.verifier.calls, "nothing is verified when nothing was sent");
    }

    @Test
    void aBlankChallengeIsRejectedWithoutVerifying() {
        Fixture f = new Fixture(true);

        assertEquals(ChallengeGate.Verdict.REJECTED, f.gate.check("   ", "sign-in"), "a blank altcha field is refused");
        assertEquals(0, f.verifier.calls, "nothing is verified when the field is blank");
    }

    @Test
    void anOversizedChallengeIsRejectedWithoutVerifying() {
        Fixture f = new Fixture(true);

        assertEquals(ChallengeGate.Verdict.REJECTED, f.gate.check("x".repeat(8193), "sign-in"), "a payload over 8 KiB is refused");
        assertEquals(0, f.verifier.calls, "an oversized payload is never parsed");
    }

    @Test
    void aBadlySignedOrMalformedChallengeIsRejected() {
        Fixture f = new Fixture(true, new FakeVerifier(Optional.empty(), "sign-in"));

        assertEquals(ChallengeGate.Verdict.REJECTED, f.gate.check("forged", "sign-in"), "a payload the verifier refuses is rejected");
    }

    @Test
    void aBadlySignedChallengeNeverReachesTheReplayStore() {
        Fixture f = new Fixture(true, new FakeVerifier(Optional.empty(), "sign-in"));
        f.gate.check("forged", "sign-in");

        assertEquals(0, f.store.calls, "garbage never writes a key to Dragonfly");
    }

    @Test
    void aRejectedChallengeIsCountedAsRejected() {
        Fixture f = new Fixture(true);
        f.gate.check("payload", "appeal");

        assertEquals(1.0, count(f.meters, "rejected"), "one refused solution is counted once");
    }

    @Test
    void aSolutionIsReservedForItsRemainingLifetime() {
        Fixture f = new Fixture(true);
        f.gate.check("payload", "sign-in");

        assertEquals(Duration.ofSeconds(300), f.store.lastRemaining, "the reserve gets the time the challenge has left; the store adds the margin");
    }
}
