package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.velocity.evidence.CachedEvidenceCounts;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedEvidenceCountsTest {

    private static final String PUNISHMENT = "p-1";

    private final StubGateway backend = new StubGateway();
    private final MutableClock clock = new MutableClock();
    private final CachedEvidenceCounts counts = new CachedEvidenceCounts(backend, clock, Duration.ofSeconds(2));

    @Test
    void theCountOfAPunishmentWithEvidenceIsReturned() {
        backend.countResult = CompletableFuture.completedFuture(3L);

        assertEquals(OptionalLong.of(3), counts.of(PUNISHMENT), "the player is told how many items exist");
    }

    @Test
    void aCountOfZeroIsReturnedAsZero() {
        assertEquals(OptionalLong.of(0), counts.of(PUNISHMENT), "zero is a real answer; the screen decides to omit it");
    }

    @Test
    void aMissingPunishmentIdentifierIsNotLookedUp() {
        assertTrue(counts.of(null).isEmpty(), "without an identifier there is no count");
        assertTrue(counts.of(" ").isEmpty(), "a blank identifier has no count");
        assertTrue(backend.countedPunishments.isEmpty(), "nothing may be asked of the backend");
    }

    @Test
    void aFailedLookupYieldsNoCount() {
        backend.countResult = CompletableFuture.failedFuture(new ApiException(404, "unknown punishment"));

        assertTrue(counts.of(PUNISHMENT).isEmpty(), "a failing backend must not break the denial screen");
    }

    @Test
    void aLookupThatTimesOutYieldsNoCount() {
        backend.countResult = new CompletableFuture<>() {
            @Override
            public Long get(long timeout, TimeUnit unit) throws TimeoutException {
                throw new TimeoutException();
            }
        };

        assertTrue(counts.of(PUNISHMENT).isEmpty(), "a slow backend must not hold the denial screen back");
    }

    @Test
    void aSecondLookupWithinThirtySecondsUsesTheCache() {
        backend.countResult = CompletableFuture.completedFuture(2L);
        counts.of(PUNISHMENT);
        clock.advance(Duration.ofSeconds(29));

        assertEquals(OptionalLong.of(2), counts.of(PUNISHMENT));
        assertEquals(List.of(PUNISHMENT), backend.countedPunishments, "a retrying player must not hit the backend each time");
    }

    @Test
    void theCacheExpiresAfterThirtySeconds() {
        backend.countResult = CompletableFuture.completedFuture(2L);
        counts.of(PUNISHMENT);
        clock.advance(Duration.ofSeconds(31));
        backend.countResult = CompletableFuture.completedFuture(4L);

        assertEquals(OptionalLong.of(4), counts.of(PUNISHMENT), "evidence added later must show up after the cache expired");
    }

    @Test
    void aFailedLookupIsCachedToo() {
        backend.countResult = CompletableFuture.failedFuture(new ApiException(503, "down"));
        counts.of(PUNISHMENT);
        counts.of(PUNISHMENT);

        assertEquals(List.of(PUNISHMENT), backend.countedPunishments, "a failing backend is not asked again within the cache time");
    }

    @Test
    void onlyTheCountEndpointIsCalled() {
        counts.of(PUNISHMENT);

        assertEquals(List.of("count"), backend.calls, "no evidence content may be requested for a denial screen");
    }
}
