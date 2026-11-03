package net.onelitefeather.blackhole.backend.integration;

import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntegrationDetectionSweeperTest {

    private static final Duration RETENTION = Duration.ofDays(180);

    private final FakeIntegrationDetectionStore store = new FakeIntegrationDetectionStore();
    private final TestClock clock = new TestClock();
    private final IntegrationDetectionSweeper sweeper = new IntegrationDetectionSweeper(store, clock, RETENTION);

    private IntegrationDetectionStore.NewDetection detectionOccurredDaysAgo(long days, UUID detectionId) {
        long occurredAt = clock.millis() - Duration.ofDays(days).toMillis();
        return new IntegrationDetectionStore.NewDetection(1, detectionId, "DETECTION", 1, "a".repeat(128), "Lobby-2",
                occurredAt, occurredAt, null, "Flag", "{\"check\":\"KillAura\"}");
    }

    @Test
    void theSweepDeletesAnUnattachedDetectionOlderThanTheRetention() {
        UUID old = UUID.randomUUID();
        this.store.insertIfAbsent(detectionOccurredDaysAgo(181, old));

        this.sweeper.sweep();

        assertEquals(0, this.store.stored().size(), "the 181 day old detection is gone");
    }

    @Test
    void theSweepKeepsADetectionWithinTheRetention() {
        UUID recent = UUID.randomUUID();
        this.store.insertIfAbsent(detectionOccurredDaysAgo(179, recent));

        this.sweeper.sweep();

        assertEquals(1, this.store.stored().size(), "a 179 day old detection is kept");
    }

    @Test
    void theSweepKeepsAnAttachedDetectionOlderThanTheRetention() {
        UUID attached = UUID.randomUUID();
        this.store.insertIfAbsent(detectionOccurredDaysAgo(400, attached));
        this.store.markAttached(attached);

        this.sweeper.sweep();

        assertEquals(1, this.store.stored().size(), "attached evidence follows the evidence lifetime, not this sweep");
    }

    @Test
    void theRetentionCountsFromOccurrenceNotFromReceipt() {
        // Old occurrence, recent receipt: the detection is still deleted, because the retention is on occurred_at.
        long occurredAt = clock.millis() - Duration.ofDays(200).toMillis();
        this.store.insertIfAbsent(new IntegrationDetectionStore.NewDetection(1, UUID.randomUUID(), "DETECTION", 1, "a".repeat(128),
                "Lobby-2", occurredAt, clock.millis(), null, "Flag", "{}"));

        this.sweeper.sweep();

        assertEquals(0, this.store.stored().size());
    }
}
