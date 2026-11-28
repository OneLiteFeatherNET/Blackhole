package net.onelitefeather.blackhole.backend.guard;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The effective guard level of this replica, held in memory so that a login check reads no database. The transition
 * service reloads it after every write, every replica reloads it on the snapshot interval, and it loads at start-up.
 * A failed reload keeps the level in use.
 */
@Singleton
public class GuardStateCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardStateCache.class);

    private final GuardStateStore store;
    private volatile GuardLevel effective = GuardLevel.OFF;

    public GuardStateCache(GuardStateStore store) {
        this.store = store;
    }

    public GuardLevel effective() {
        return this.effective;
    }

    /** Reads the guard row again. A failure, or a missing row, keeps the level in use. */
    public synchronized void reload() {
        try {
            this.store.current().ifPresent(state -> this.effective = state.effective());
        } catch (RuntimeException e) {
            LOGGER.warn("Could not reload the join guard level; keeping the level in use");
        }
    }

    @Scheduled(
            fixedDelay = "${blackhole.guard.snapshot-interval:PT2M}",
            initialDelay = "PT0S"
    )
    void poll() {
        reload();
    }
}
