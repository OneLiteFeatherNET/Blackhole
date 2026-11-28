package net.onelitefeather.blackhole.backend.intel.names;

import net.onelitefeather.blackhole.backend.support.InMemoryNameHistoryStore;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameHistorySweeperTest {

    private final TestClock clock = new TestClock();
    private final InMemoryNameHistoryStore store = new InMemoryNameHistoryStore();
    private final NameHistorySweeper sweeper = new NameHistorySweeper(store, clock, 180);

    private void nameLastSeenDaysAgo(String name, int days) {
        store.upsert("a".repeat(128), UUID.randomUUID(), name, clock.millis() - Duration.ofDays(days).toMillis());
    }

    @Test
    void aNameLastSeenMoreThanTheRetentionAgoIsDeleted() {
        nameLastSeenDaysAgo("Steve", 200);

        int deleted = sweeper.sweepNow();

        assertEquals(1, deleted, "one name is past the 180-day window");
        assertTrue(store.all().isEmpty(), "the entry is gone");
    }

    @Test
    void aNameLastSeenInsideTheRetentionIsKept() {
        nameLastSeenDaysAgo("Steve", 10);

        int deleted = sweeper.sweepNow();

        assertEquals(0, deleted, "a 10-day-old name is inside the window");
        assertEquals(1, store.all().size(), "the entry stays");
    }

    @Test
    void theNameRetentionIsLongerThanTheSessionRetentionByDefault() {
        // A name last seen 100 days ago outlives the 90-day session window: the sweep keeps it.
        nameLastSeenDaysAgo("Steve", 100);

        assertEquals(0, sweeper.sweepNow(), "100 days is inside the 180-day name window");
    }
}
