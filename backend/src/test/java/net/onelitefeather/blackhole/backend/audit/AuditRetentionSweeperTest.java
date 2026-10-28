package net.onelitefeather.blackhole.backend.audit;

import net.onelitefeather.blackhole.backend.support.InMemoryStaffAuditStore;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditRetentionSweeperTest {

    private static final Instant NOW = Instant.parse("2031-06-01T00:00:00Z");
    private static final Duration ONE_YEAR = Duration.ofDays(365);
    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final InMemoryStaffAuditStore store = new InMemoryStaffAuditStore();
    private final TestClock clock = new TestClock(NOW);
    private final AuditRetentionSweeper sweeper = new AuditRetentionSweeper(store, clock, ONE_YEAR);

    private void recordAgedDays(long days) {
        long occurredAt = NOW.minus(Duration.ofDays(days)).toEpochMilli();
        store.insert(new StaffAuditEntry(UUID.randomUUID(), occurredAt, ACTOR, null, Surface.PANEL, "kick.create", "NONE", null, ACTOR));
    }

    @Test
    void aRowOlderThanTheRetentionIsDeleted() {
        recordAgedDays(366);

        sweeper.sweep();

        assertEquals(0, store.rows().size(), "the row past retention is gone");
    }

    @Test
    void aRowExactlyAtTheRetentionBoundaryStays() {
        recordAgedDays(365);

        sweeper.sweep();

        assertEquals(1, store.rows().size(), "a row one year old is kept");
    }

    @Test
    void aSweepDeletesEveryOldRowAcrossBatches() {
        for (int i = 0; i < 1_200; i++) {
            recordAgedDays(400);
        }
        recordAgedDays(10);

        sweeper.sweep();

        assertEquals(1, store.rows().size(), "all 1200 old rows are deleted, the young one stays");
    }
}
