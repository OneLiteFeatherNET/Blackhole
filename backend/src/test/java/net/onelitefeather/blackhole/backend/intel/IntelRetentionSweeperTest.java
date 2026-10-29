package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.support.InMemorySessionStore;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelRetentionSweeperTest {

    private final TestClock clock = new TestClock();
    private final InMemorySessionStore store = new InMemorySessionStore();
    private final IntelRetentionSweeper sweeper = new IntelRetentionSweeper(store, clock, 90);

    private void sessionStartedDaysAgo(int days) {
        long started = clock.millis() - Duration.ofDays(days).toMillis();
        store.insert(new SessionRecord(UUID.randomUUID(), "a".repeat(128), UUID.randomUUID(), "Steve", "192.0.2.10", null,
                null, null, null, null, null, null, null, SessionOutcome.ALLOWED, started, null));
    }

    @Test
    void aSessionStartedMoreThanTheRetentionAgoIsDeleted() {
        sessionStartedDaysAgo(100);

        int deleted = sweeper.sweepNow();

        assertEquals(1, deleted, "one session is past the 90-day window");
        assertTrue(store.all().isEmpty());
    }

    @Test
    void aSessionStartedInsideTheRetentionIsKept() {
        sessionStartedDaysAgo(10);

        int deleted = sweeper.sweepNow();

        assertEquals(0, deleted, "a 10-day-old session is inside the window");
        assertEquals(1, store.all().size());
    }
}
