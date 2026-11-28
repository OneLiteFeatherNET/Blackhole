package net.onelitefeather.blackhole.backend.intel.names;

import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * Deletes names not seen for {@code blackhole.intel.name-retention-days}. Its own window, longer than the session
 * retention: a name outlives the sessions it came from, so that "first seen" stays meaningful.
 */
@Singleton
public class NameHistorySweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(NameHistorySweeper.class);

    private final NameHistoryStore store;
    private final Clock clock;
    private final int retentionDays;

    public NameHistorySweeper(
            NameHistoryStore store,
            Clock clock,
            @Value("${blackhole.intel.name-retention-days:180}") int retentionDays
    ) {
        this.store = store;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${blackhole.intel.name-retention-sweep-cron:0 0 4 * * *}")
    void sweep() {
        sweepNow();
    }

    /** @return how many names were deleted */
    public int sweepNow() {
        long cutoff = this.clock.millis() - Duration.ofDays(this.retentionDays).toMillis();
        int deleted = this.store.deleteLastSeenBefore(cutoff);
        if (deleted > 0) {
            LOGGER.info("Deleted {} player name(s) past the {}-day name retention window", deleted, this.retentionDays);
        }
        return deleted;
    }
}
