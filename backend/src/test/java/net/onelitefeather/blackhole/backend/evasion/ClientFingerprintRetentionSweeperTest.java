package net.onelitefeather.blackhole.backend.evasion;

import net.onelitefeather.blackhole.backend.support.FakeClientFingerprintStore;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientFingerprintRetentionSweeperTest {

    private final FakeClientFingerprintStore store = new FakeClientFingerprintStore();
    private final TestClock clock = new TestClock();

    @Test
    void sightingsNotSeenWithinTheRetentionAreDeleted() {
        store.record("f".repeat(64), "v1", "a".repeat(128), clock.millis());
        clock.advance(Duration.ofDays(20));
        store.record("e".repeat(64), "v1", "a".repeat(128), clock.millis());
        clock.advance(Duration.ofDays(11));

        new ClientFingerprintRetentionSweeper(store, clock, 30).sweep();

        assertEquals(1, store.all().size());
        assertEquals("e".repeat(64), store.all().getFirst().fingerprint());
    }

    @Test
    void aSightingExactlyAtTheEdgeIsKept() {
        store.record("f".repeat(64), "v1", "a".repeat(128), clock.millis());
        clock.advance(Duration.ofDays(30));

        new ClientFingerprintRetentionSweeper(store, clock, 30).sweep();

        assertEquals(1, store.all().size());
    }

    @Test
    void aRepeatedSightingKeepsTheRowAliveBecauseItRefreshesLastSeen() {
        store.record("f".repeat(64), "v1", "a".repeat(128), clock.millis());
        clock.advance(Duration.ofDays(25));
        store.record("f".repeat(64), "v1", "a".repeat(128), clock.millis());
        clock.advance(Duration.ofDays(25));

        new ClientFingerprintRetentionSweeper(store, clock, 30).sweep();

        assertEquals(1, store.all().size());
    }
}
