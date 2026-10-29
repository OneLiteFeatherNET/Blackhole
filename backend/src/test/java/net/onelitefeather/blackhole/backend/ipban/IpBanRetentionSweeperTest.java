package net.onelitefeather.blackhole.backend.ipban;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpBanRetentionSweeperTest {

    private static final long NOW = IpBanFixture.NOW;
    private static final long YEAR = Duration.ofDays(365).toMillis();

    private static IpBanRecord row(Long expiresAt, Long liftedAt) {
        return new IpBanRecord(UUID.randomUUID(), IpBanNetwork.parse("203.0.113.7").orElseThrow().network(), "Bot wave",
                null, IpBanSource.PANEL, null, null, IpBanFixture.ADMIN_OID, NOW - 10, expiresAt, liftedAt, null, null);
    }

    @Test
    void aBanLiftedBeforeTheRetentionWindowIsDeleted() {
        IpBanFixture fixture = new IpBanFixture();
        IpBanRecord old = row(null, NOW - YEAR - 1000);
        fixture.store.rows.put(old.id(), old);
        IpBanRetentionSweeper sweeper = new IpBanRetentionSweeper(fixture.store, fixture.clock, Duration.ofDays(365));

        int deleted = sweeper.sweepAt(NOW);

        assertEquals(1, deleted, "one row is past the window");
        assertTrue(fixture.store.rows.isEmpty(), "the old lifted row is gone");
    }

    @Test
    void aBanLiftedInsideTheWindowIsKept() {
        IpBanFixture fixture = new IpBanFixture();
        IpBanRecord recent = row(null, NOW - 1000);
        fixture.store.rows.put(recent.id(), recent);
        IpBanRetentionSweeper sweeper = new IpBanRetentionSweeper(fixture.store, fixture.clock, Duration.ofDays(365));

        assertEquals(0, sweeper.sweepAt(NOW), "nothing inside the window is deleted");
    }

    @Test
    void anActiveBanIsNeverDeleted() {
        IpBanFixture fixture = new IpBanFixture();
        IpBanRecord permanent = row(null, null);
        fixture.store.rows.put(permanent.id(), permanent);
        IpBanRetentionSweeper sweeper = new IpBanRetentionSweeper(fixture.store, fixture.clock, Duration.ofDays(365));

        assertEquals(0, sweeper.sweepAt(NOW + 10 * YEAR), "an active ban stays however old it is");
    }
}
