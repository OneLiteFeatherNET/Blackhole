package net.onelitefeather.blackhole.backend.publicbans;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicBanStatusTest {

    private static final long NOW = 1_000_000_000_000L;
    private static final Duration RETENTION = Duration.ofDays(90);
    private static final long DAY = 86_400_000L;

    @Test
    void permanentEntryIsActive() {
        assertEquals(PublicBanStatus.ACTIVE, PublicBanStatus.at(null, null, NOW), "a permanent entry stays active until revoked");
    }

    @Test
    void entryPastItsExpiryIsExpiredWithoutASweep() {
        assertEquals(PublicBanStatus.EXPIRED, PublicBanStatus.at(NOW - 1, null, NOW), "the expiry instant alone makes it expired");
    }

    @Test
    void revokedWinsOverExpired() {
        assertEquals(PublicBanStatus.REVOKED, PublicBanStatus.at(NOW - 1, NOW - 2, NOW), "a revoke is the reported status");
    }

    @Test
    void endedEntryInsideRetentionIsListed() {
        assertTrue(PublicBanStatus.listed(null, NOW - DAY, NOW, RETENTION), "an entry revoked a day ago is still listed");
    }

    @Test
    void endedEntryPastRetentionIsNotListed() {
        assertFalse(PublicBanStatus.listed(null, NOW - 91 * DAY, NOW, RETENTION), "an entry ended 91 days ago leaves the list");
    }

    @Test
    void activeEntryIsAlwaysListed() {
        assertTrue(PublicBanStatus.listed(null, null, NOW + 1000 * DAY, RETENTION), "an active entry is never hidden by retention");
    }
}
