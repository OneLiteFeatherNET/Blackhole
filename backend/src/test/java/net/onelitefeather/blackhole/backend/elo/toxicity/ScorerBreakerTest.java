package net.onelitefeather.blackhole.backend.elo.toxicity;

import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScorerBreakerTest {

    private final TestClock clock = new TestClock();
    private final ScorerCircuitBreaker breaker = new ScorerCircuitBreaker(
            clock, 5, Duration.ofSeconds(10), Duration.ofSeconds(30));

    private void failTimes(int times) {
        for (int i = 0; i < times; i++) {
            breaker.recordFailure();
        }
    }

    @Test
    void fiveFailuresWithinTenSecondsOpenTheBreaker() {
        failTimes(5);

        assertEquals(ScorerCircuitBreaker.State.OPEN, breaker.state(), "five failures in ten seconds must open it");
    }

    @Test
    void fourFailuresDoNotOpenTheBreaker() {
        failTimes(4);

        assertEquals(ScorerCircuitBreaker.State.CLOSED, breaker.state(), "four failures are not enough");
    }

    @Test
    void failuresOutsideTheTenSecondWindowDoNotAccumulate() {
        failTimes(4);
        clock.advance(Duration.ofSeconds(11));
        failTimes(1);

        assertEquals(ScorerCircuitBreaker.State.CLOSED, breaker.state(), "old failures must have left the window");
    }

    @Test
    void aCallWhileOpenIsRefused() {
        failTimes(5);

        assertFalse(breaker.allowCall(), "an open breaker must refuse the call");
    }

    @Test
    void theBreakerStaysOpenBeforeThirtySeconds() {
        failTimes(5);
        clock.advance(Duration.ofSeconds(29));

        assertFalse(breaker.allowCall(), "the probe must wait the full thirty seconds");
    }

    @Test
    void aHalfOpenProbeIsAllowedAfterThirtySeconds() {
        failTimes(5);
        clock.advance(Duration.ofSeconds(30));

        assertTrue(breaker.allowCall(), "one probe is allowed after thirty seconds");
        assertEquals(ScorerCircuitBreaker.State.HALF_OPEN, breaker.state(), "the breaker is half-open while the probe runs");
    }

    @Test
    void aSuccessfulProbeClosesTheBreaker() {
        failTimes(5);
        clock.advance(Duration.ofSeconds(30));
        breaker.allowCall();

        breaker.recordSuccess();

        assertEquals(ScorerCircuitBreaker.State.CLOSED, breaker.state(), "a good probe closes the breaker");
    }

    @Test
    void aFailedProbeReopensTheBreaker() {
        failTimes(5);
        clock.advance(Duration.ofSeconds(30));
        breaker.allowCall();

        breaker.recordFailure();

        assertEquals(ScorerCircuitBreaker.State.OPEN, breaker.state(), "a failed probe reopens the breaker");
    }
}
