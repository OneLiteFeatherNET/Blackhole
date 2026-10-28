package net.onelitefeather.blackhole.backend.elo.toxicity;

import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScorerTimeoutTest {

    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<CompletableFuture<List<Double>>> unanswered = new ArrayList<>();

    private HttpToxicityScorer scorer() {
        ToxicityBatchSender neverAnswers = texts -> {
            CompletableFuture<List<Double>> pending = new CompletableFuture<>();
            unanswered.add(pending);
            return pending;
        };
        return new HttpToxicityScorer(neverAnswers, new ScorerCircuitBreaker(
                new TestClock(), 5, Duration.ofSeconds(10), Duration.ofSeconds(30)),
                scheduler, Duration.ofMillis(250), 16, Duration.ofMillis(5));
    }

    @Test
    void theScorerCallIsGivenTheConfiguredTimeout() {
        scorer().scoreAsync("hello");
        scheduler.runNext(); // the linger

        assertTrue(scheduler.scheduledDelays().contains(Duration.ofMillis(250)),
                "the call must be bounded by the configured 250 ms timeout");
    }

    @Test
    void aTimedOutCallRecordsNoDelta() {
        CompletableFuture<Double> result = scorer().scoreAsync("hello");

        scheduler.runAll();

        assertEquals(0.0, result.join(), "a timed-out call must score zero, so no delta and no flag");
    }
}
