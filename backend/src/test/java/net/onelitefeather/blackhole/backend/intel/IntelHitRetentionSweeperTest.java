package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.support.InMemoryIntelRecordStore;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntelHitRetentionSweeperTest {

    private final TestClock clock = new TestClock();
    private final InMemoryIntelRecordStore store = new InMemoryIntelRecordStore();
    private final IntelHitRetentionSweeper sweeper = new IntelHitRetentionSweeper(this.store, this.clock, Duration.ofDays(90));

    private void hitCreatedDaysAgo(int days) {
        long created = this.clock.millis() - Duration.ofDays(days).toMillis();
        this.store.insertHit(new IntelHit(UUID.randomUUID(), "a".repeat(128), "192.0.2.10", "VPN", "list:x4bnet-vpn", null,
                null, IntelPolicy.FLAG, false, created));
    }

    @Test
    void aHitOlderThanTheRetentionIsDeleted() {
        hitCreatedDaysAgo(100);

        int deleted = this.sweeper.sweepNow();

        assertEquals(1, deleted, "one hit is past the 90-day retention");
        assertEquals(0, this.store.allHits().size(), "the old hit is gone from the store");
    }

    @Test
    void aHitInsideTheRetentionIsKept() {
        hitCreatedDaysAgo(10);

        int deleted = this.sweeper.sweepNow();

        assertEquals(0, deleted, "a 10-day-old hit is inside the window");
        assertEquals(1, this.store.allHits().size(), "the recent hit stays");
    }
}
