package net.onelitefeather.blackhole.backend.publicapi.challenge;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One test per outcome of {@code blackhole.public.challenge}; each call increments only its own tag value. */
class ChallengeMetricsTest {

    private static double count(SimpleMeterRegistry meters, String outcome) {
        return meters.counter(ChallengeMetrics.METER, "outcome", outcome).count();
    }

    @Test
    void anIssuedChallengeIsCountedAsIssued() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        new ChallengeMetrics(meters).issued();

        assertEquals(1.0, count(meters, "issued"), "an issued challenge increments outcome=issued");
    }

    @Test
    void anAcceptedSolutionIsCountedAsSolved() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        new ChallengeMetrics(meters).solved();

        assertEquals(1.0, count(meters, "solved"), "an accepted solution increments outcome=solved");
    }

    @Test
    void aRefusedSolutionIsCountedAsRejected() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        new ChallengeMetrics(meters).rejected();

        assertEquals(1.0, count(meters, "rejected"), "a refused solution increments outcome=rejected");
    }

    @Test
    void aReusedSolutionIsCountedAsReplayed() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        new ChallengeMetrics(meters).replayed();

        assertEquals(1.0, count(meters, "replayed"), "a reused solution increments outcome=replayed");
    }

    @Test
    void aReserveThatCannotAnswerIsCountedAsUnavailable() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        new ChallengeMetrics(meters).unavailable();

        assertEquals(1.0, count(meters, "unavailable"), "a reserve that cannot answer increments outcome=unavailable");
    }
}
