package net.onelitefeather.blackhole.backend.elo.toxicity;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.elo.ToxicityScorer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * The {@link ToxicityScorer} backed by the inference service. It is fail-open: a timeout, an error, a bad
 * response or an open breaker scores zero, which means no flag and no delta. A scoring call never blocks
 * longer than the configured timeout. Message text is never logged.
 */
@Singleton
public class HttpToxicityScorer implements ToxicityScorer {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpToxicityScorer.class);

    private final ToxicityBatchSender sender;
    private final ScorerCircuitBreaker breaker;
    private final TaskScheduler scheduler;
    private final Duration timeout;
    private final MicroBatcher batcher;

    @Inject
    public HttpToxicityScorer(
            @Value("${blackhole.elo.chat.scorer.url:http://localhost:8090}") String url,
            @Value("${blackhole.elo.chat.scorer.timeout-ms:250}") long timeoutMillis,
            @Value("${blackhole.elo.chat.scorer.batch.max-size:16}") int maxBatchSize,
            @Value("${blackhole.elo.chat.scorer.batch.linger-ms:5}") long lingerMillis,
            @Value("${blackhole.elo.chat.scorer.breaker.failures:5}") int breakerFailures,
            @Value("${blackhole.elo.chat.scorer.breaker.window-ms:10000}") long breakerWindowMillis,
            @Value("${blackhole.elo.chat.scorer.breaker.open-ms:30000}") long breakerOpenMillis,
            Clock clock,
            TaskScheduler scheduler
    ) {
        this(
                new HttpToxicityBatchSender(URI.create(url), HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(timeoutMillis))
                        .build()),
                new ScorerCircuitBreaker(clock, breakerFailures,
                        Duration.ofMillis(breakerWindowMillis), Duration.ofMillis(breakerOpenMillis)),
                scheduler,
                Duration.ofMillis(timeoutMillis),
                maxBatchSize,
                Duration.ofMillis(lingerMillis));
    }

    HttpToxicityScorer(ToxicityBatchSender sender, ScorerCircuitBreaker breaker, TaskScheduler scheduler,
                       Duration timeout, int maxBatchSize, Duration linger) {
        this.sender = sender;
        this.breaker = breaker;
        this.scheduler = scheduler;
        this.timeout = timeout;
        this.batcher = new MicroBatcher(maxBatchSize, linger, scheduler, this::sendGuarded);
    }

    @Override
    public double score(String message) {
        return scoreAsync(message).join();
    }

    /** Completes with the overall score, or with zero when the call failed or was refused. Never completes exceptionally. */
    public CompletableFuture<Double> scoreAsync(String message) {
        if (message == null || message.isBlank()) {
            return CompletableFuture.completedFuture(0.0);
        }
        return batcher.submit(message);
    }

    private CompletableFuture<List<Double>> sendGuarded(List<String> texts) {
        int count = texts.size();
        if (!breaker.allowCall()) {
            return CompletableFuture.completedFuture(zeros(count));
        }
        CompletableFuture<List<Double>> call;
        try {
            call = sender.send(texts);
        } catch (RuntimeException e) {
            breaker.recordFailure();
            return CompletableFuture.completedFuture(zeros(count));
        }
        TaskScheduler.Cancellable timer = scheduler.schedule(timeout,
                () -> call.completeExceptionally(new TimeoutException("scorer timeout")));
        CompletableFuture<List<Double>> guarded = new CompletableFuture<>();
        call.whenComplete((scores, error) -> {
            timer.cancel();
            if (error == null && scores != null && scores.size() == count) {
                breaker.recordSuccess();
                guarded.complete(scores);
            } else {
                breaker.recordFailure();
                LOGGER.warn("Toxicity scorer call failed for {} messages: {}", count,
                        error == null ? "unusable response" : error.getClass().getSimpleName());
                guarded.complete(zeros(count));
            }
        });
        return guarded;
    }

    private static List<Double> zeros(int count) {
        List<Double> zeros = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            zeros.add(0.0);
        }
        return zeros;
    }
}
