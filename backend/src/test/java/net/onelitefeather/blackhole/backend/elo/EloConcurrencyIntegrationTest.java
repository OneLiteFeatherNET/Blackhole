package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.context.ApplicationContext;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.elo.service.EloService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Concurrent score writes against the real database: no delta may be lost and the first write of a
 * never-scored owner may not fail on the primary key.
 */
@Tag("integration")
class EloConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final int WRITERS = 8;

    private ApplicationContext context;
    private EloService elo;
    private EloScoreRepository scores;

    @BeforeEach
    void start() {
        this.context = ApplicationContext.run(contextProperties());
        this.elo = this.context.getBean(EloService.class);
        this.scores = this.context.getBean(EloScoreRepository.class);
    }

    @AfterEach
    void stop() {
        this.context.close();
    }

    @Test
    void concurrentDeltasOnANeverScoredOwnerAllLand() throws Exception {
        String owner = "c1".repeat(64);

        List<EloChange> changes = runConcurrently(owner, -10);

        assertEquals(1000 - WRITERS * 10, this.elo.score(owner, "chat").orElseThrow(), "every delta must be stored");
        Set<Integer> resultingScores = new HashSet<>();
        for (EloChange change : changes) {
            resultingScores.add(((EloChange.Applied) change).scoreAfter());
        }
        assertEquals(WRITERS, resultingScores.size(), "each caller must see its own distinct stored result");
    }

    @Test
    void concurrentDeltasOnAnExistingScoreAllLand() throws Exception {
        String owner = "c2".repeat(64);
        this.elo.applyDelta(owner, "chat", 0, EloReasonCode.MANUAL_ADJUSTMENT, null, null);

        runConcurrently(owner, 5);

        assertEquals(1000 + WRITERS * 5, this.elo.score(owner, "chat").orElseThrow(), "no increment may be lost");
        assertEquals(1, this.scores.findByIdOwner(owner).size(), "still exactly one score row");
    }

    private List<EloChange> runConcurrently(String owner, int delta) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        CountDownLatch ready = new CountDownLatch(WRITERS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<EloChange>> futures = new ArrayList<>();
            for (int i = 0; i < WRITERS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return this.elo.applyDelta(owner, "chat", delta, EloReasonCode.MANUAL_ADJUSTMENT, null, null);
                }));
            }
            ready.await();
            go.countDown();
            List<EloChange> changes = new ArrayList<>();
            for (Future<EloChange> future : futures) {
                changes.add(future.get());
            }
            return changes;
        } finally {
            pool.shutdownNow();
        }
    }
}
