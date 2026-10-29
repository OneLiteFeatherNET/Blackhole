package net.onelitefeather.blackhole.backend.intel;

import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * Deletes sessions older than {@code blackhole.intel.retention-days}. Its own window: it does not share the evasion
 * sweeper's retention, because the two data sets have different reasons to be kept.
 */
@Singleton
public class IntelRetentionSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(IntelRetentionSweeper.class);

    private final SessionStore store;
    private final Clock clock;
    private final int retentionDays;

    public IntelRetentionSweeper(
            SessionStore store,
            Clock clock,
            @Value("${blackhole.intel.retention-days:90}") int retentionDays
    ) {
        this.store = store;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${blackhole.intel.retention-sweep-cron:0 45 3 * * *}")
    void sweep() {
        sweepNow();
    }

    /** @return how many sessions were deleted */
    public int sweepNow() {
        long cutoff = this.clock.millis() - Duration.ofDays(this.retentionDays).toMillis();
        int deleted = this.store.deleteStartedBefore(cutoff);
        if (deleted > 0) {
            LOGGER.info("Deleted {} player session(s) past the {}-day retention window", deleted, this.retentionDays);
        }
        return deleted;
    }
}
