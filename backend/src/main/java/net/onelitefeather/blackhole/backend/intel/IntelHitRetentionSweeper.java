package net.onelitefeather.blackhole.backend.intel;

import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * Deletes VPN and hosting hits older than {@code blackhole.intel.hit-retention}. A hit holds the clear address, so it
 * is kept only as long as that setting says.
 */
@Singleton
public class IntelHitRetentionSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(IntelHitRetentionSweeper.class);

    private final IntelRecordStore store;
    private final Clock clock;
    private final Duration retention;

    public IntelHitRetentionSweeper(
            IntelRecordStore store,
            Clock clock,
            @Value("${blackhole.intel.hit-retention:P90D}") Duration retention
    ) {
        this.store = store;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(cron = "${blackhole.intel.hit-retention-sweep-cron:0 50 3 * * *}")
    void sweep() {
        sweepNow();
    }

    /** @return how many hits were deleted */
    public int sweepNow() {
        long cutoff = this.clock.millis() - this.retention.toMillis();
        int deleted = this.store.deleteHitsCreatedBefore(cutoff);
        if (deleted > 0) {
            LOGGER.info("Deleted {} VPN or hosting hit(s) past the retention window", deleted);
        }
        return deleted;
    }
}
