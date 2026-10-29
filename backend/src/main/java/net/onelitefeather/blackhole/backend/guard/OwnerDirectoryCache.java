package net.onelitefeather.blackhole.backend.guard;

import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;

/**
 * The known and the staff owners of this replica, held in memory so that a login check reads no database. Every
 * replica reloads them on the snapshot interval and at start-up. A failed reload keeps the last good snapshot.
 */
@Singleton
public class OwnerDirectoryCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(OwnerDirectoryCache.class);

    /**
     * @param known the owners that count as known players for LOCKDOWN
     * @param staff the owners of linked staff accounts, exempt from the guard and area bans
     */
    record Snapshot(Set<String> known, Set<String> staff) {

        static final Snapshot EMPTY = new Snapshot(Set.of(), Set.of());
    }

    private final OwnerDirectoryStore store;
    private final long minPlaytimeMillis;
    private volatile Snapshot current = Snapshot.EMPTY;

    public OwnerDirectoryCache(
            OwnerDirectoryStore store,
            @Value("${blackhole.guard.lockdown.min-playtime:PT60M}") Duration minPlaytime
    ) {
        this.store = store;
        this.minPlaytimeMillis = minPlaytime.toMillis();
    }

    public boolean isKnown(String ownerHash) {
        return this.current.known().contains(ownerHash);
    }

    public boolean isStaff(String ownerHash) {
        return this.current.staff().contains(ownerHash);
    }

    /** Reads both sets again. A failure keeps the snapshot in use. */
    public synchronized void reload() {
        try {
            this.current = new Snapshot(Set.copyOf(this.store.knownOwners(this.minPlaytimeMillis)),
                    Set.copyOf(this.store.staffOwners()));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not reload the known and staff owners; keeping the last ones");
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
