package net.onelitefeather.blackhole.backend.firewall;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.intel.AsnNetworks;
import net.onelitefeather.blackhole.backend.intel.IntelPolicy;
import net.onelitefeather.blackhole.backend.intel.IntelPrefix;
import net.onelitefeather.blackhole.backend.ipban.IpBanProtection;
import net.onelitefeather.blackhole.backend.ipban.IpBanRecord;
import net.onelitefeather.blackhole.backend.ipban.IpBanSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedCacheTest {

    private static final long NOW = 1_780_000_000_000L;

    /** An ASN file that counts its walks; its version is changed by hand. */
    static final class CountingAsnNetworks implements AsnNetworks {

        long version = 1;
        int walks;

        @Override
        public long version() {
            return this.version;
        }

        @Override
        public List<IntelPrefix> networksOfAsns(Set<Long> asns) {
            this.walks++;
            return List.of(IntelPrefix.parseCidr("198.51.100.0/24").orElseThrow());
        }
    }

    private static IpBanRecord ban(String cidr, Long expiresAt) {
        return new IpBanRecord(UUID.randomUUID(), IntelPrefix.parseCidr(cidr).orElseThrow(), "reason", null,
                IpBanSource.PANEL, null, null, null, NOW, expiresAt, null, null, null);
    }

    private static FeedCache cache(FeedTestStore store, FeedTestClock clock, List<FeedNetworkSource> sources) {
        return new FeedCache(store, new IpBanProtection(""), sources, clock, new SimpleMeterRegistry());
    }

    private static String ipsBody(FeedCache cache) {
        Optional<FeedCache.Built> built = cache.current();
        return new String(built.orElseThrow().ips().withoutComments().body(), StandardCharsets.US_ASCII);
    }

    @Test
    void twoQuietTicksWithNoChangeReadNoBanRowsAndWalkTheAsnFileOnce() {
        FeedTestStore store = new FeedTestStore();
        CountingAsnNetworks asn = new CountingAsnNetworks();
        FeedCache cache = cache(store, new FeedTestClock(NOW),
                List.of(new HostingAsnFeedSource(asn, () -> IntelPolicy.DENY, Set.of(64500L))));
        cache.current();

        cache.tick();
        cache.tick();

        assertEquals(1, store.activeReads.get(), "a quiet tick reads no ban rows: the feed is not rebuilt");
        assertEquals(1, asn.walks, "the hosting ASN file is walked once for the same file and ASN set");
    }

    @Test
    void aBanWriteRebuildsTheFeedOnTheWritePath() {
        FeedTestStore store = new FeedTestStore();
        FeedCache cache = cache(store, new FeedTestClock(NOW), List.of());
        assertFalse(ipsBody(cache).contains("203.0.113.7"), "no ban yet");

        store.rows.add(ban("203.0.113.7/32", null));
        cache.changed();

        assertTrue(ipsBody(cache).contains("203.0.113.7"), "the write marked the feed dirty and it was rebuilt at once");
        assertEquals(2, store.activeReads.get(), "one build for the first request, one for the write");
    }

    @Test
    void anExpiredBanLeavesTheFeedOnTheTickAfterItsExpiry() {
        FeedTestStore store = new FeedTestStore();
        FeedTestClock clock = new FeedTestClock(NOW);
        FeedCache cache = cache(store, clock, List.of());
        store.rows.add(ban("203.0.113.7/32", NOW + 1_000));
        assertTrue(ipsBody(cache).contains("203.0.113.7"), "the ban holds before its expiry");

        clock.advanceTo(NOW + 999);
        cache.tick();
        assertEquals(1, store.activeReads.get(), "one millisecond before the expiry nothing is rebuilt");

        clock.advanceTo(NOW + 1_000);
        cache.tick();
        assertFalse(ipsBody(cache).contains("203.0.113.7"), "the first tick after the expiry drops the entry");
    }

    @Test
    void aFailedRebuildKeepsTheLastGoodFeedAndRetriesOnTheNextTick() {
        FeedTestStore store = new FeedTestStore();
        store.rows.add(ban("203.0.113.7/32", null));
        FeedCache cache = cache(store, new FeedTestClock(NOW), List.of());
        assertTrue(ipsBody(cache).contains("203.0.113.7"), "the first build is good");

        store.failing = true;
        cache.changed();

        assertTrue(ipsBody(cache).contains("203.0.113.7"), "a failed rebuild serves the last good feed, never an empty one");

        store.failing = false;
        cache.tick();

        assertEquals(3, store.activeReads.get(), "the failed rebuild left the feed dirty, so the next tick retried it");
    }

    @Test
    void noFeedExistsBeforeTheFirstGoodBuild() {
        FeedTestStore store = new FeedTestStore();
        store.failing = true;
        FeedCache cache = cache(store, new FeedTestClock(NOW), List.of());

        assertFalse(cache.current().isPresent(), "with no good build the route answers 503");
    }

    @Test
    void concurrentFirstRequestsTriggerOneBuild() throws Exception {
        FeedTestStore store = new FeedTestStore();
        store.rows.add(ban("203.0.113.7/32", null));
        FeedCache cache = cache(store, new FeedTestClock(NOW), List.of());
        int callers = 8;
        CyclicBarrier start = new CyclicBarrier(callers);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Optional<FeedCache.Built>>> answers = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return cache.current();
                }));
            }
            for (Future<Optional<FeedCache.Built>> answer : answers) {
                assertTrue(answer.get().isPresent(), "every caller gets the built feed");
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, store.activeReads.get(), "the first build runs once, however many requests arrive at the same time");
    }
}
