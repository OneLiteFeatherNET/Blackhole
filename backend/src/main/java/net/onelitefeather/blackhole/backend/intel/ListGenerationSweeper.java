package net.onelitefeather.blackhole.backend.intel;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * Removes generations a crashed refresh left behind. A generation younger than an hour may still be in progress, so
 * it is kept; the active generation of every source is never removed.
 */
@Singleton
public class ListGenerationSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListGenerationSweeper.class);
    static final Duration ORPHAN_AGE = Duration.ofHours(1);

    private final ListGenerationStore store;
    private final Clock clock;

    public ListGenerationSweeper(ListGenerationStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Scheduled(cron = "${blackhole.intel.lists.sweep-cron:0 20 * * * *}")
    void sweep() {
        sweepNow();
    }

    /** @return how many orphaned generations were removed */
    public int sweepNow() {
        int removed = this.store.deleteOrphanGenerationsCreatedBefore(this.clock.millis() - ORPHAN_AGE.toMillis());
        if (removed > 0) {
            LOGGER.info("Removed {} orphaned list generation(s)", removed);
        }
        return removed;
    }
}
