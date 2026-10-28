package net.onelitefeather.blackhole.backend.elo.toxicity;

import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpToxicityScorerTest {

    private final ManualScheduler scheduler = new ManualScheduler();
    private final TestClock clock = new TestClock();
    private final List<List<String>> sent = new ArrayList<>();

    private HttpToxicityScorer scorer(ScorerCircuitBreaker breaker, double answer) {
        ToxicityBatchSender sender = texts -> {
            sent.add(List.copyOf(texts));
            return CompletableFuture.completedFuture(texts.stream().map(t -> answer).toList());
        };
        return new HttpToxicityScorer(sender, breaker, scheduler, Duration.ofMillis(250), 16, Duration.ofMillis(5));
    }

    @Test
    void theScoreReturnedByTheServiceIsUsed() {
        ScorerCircuitBreaker breaker = new ScorerCircuitBreaker(clock, 5, Duration.ofSeconds(10), Duration.ofSeconds(30));

        CompletableFuture<Double> result = scorer(breaker, 0.7).scoreAsync("hello");
        scheduler.runAll();

        assertEquals(0.7, result.join(), 1e-9, "the service's overall score must be returned unchanged");
    }

    @Test
    void anOpenBreakerMakesNoScorerCall() {
        ScorerCircuitBreaker breaker = new ScorerCircuitBreaker(clock, 5, Duration.ofSeconds(10), Duration.ofSeconds(30));
        for (int i = 0; i < 5; i++) {
            breaker.recordFailure();
        }

        CompletableFuture<Double> result = scorer(breaker, 0.9).scoreAsync("hello");
        scheduler.runAll();

        assertTrue(sent.isEmpty(), "while the breaker is open the service must not be called");
        assertEquals(0.0, result.join(), "an open breaker records no delta");
    }
}
