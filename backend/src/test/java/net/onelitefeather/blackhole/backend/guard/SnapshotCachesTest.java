package net.onelitefeather.blackhole.backend.guard;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotCachesTest {

    private static final String OWNER = "c".repeat(128);

    /** A guard store that can be made to fail, as a database outage would. */
    private static final class FlakyGuardStateStore extends InMemoryGuardStateStore {

        boolean broken;

        FlakyGuardStateStore(GuardState initial) {
            super(initial);
        }

        @Override
        public Optional<GuardState> current() {
            if (this.broken) {
                throw new IllegalStateException("database down");
            }
            return super.current();
        }
    }

    /** An owner store that can be made to fail, as a database outage would. */
    private static final class FlakyOwnerDirectoryStore extends InMemoryOwnerDirectoryStore {

        boolean broken;

        @Override
        public java.util.Set<String> staffOwners() {
            if (this.broken) {
                throw new IllegalStateException("database down");
            }
            return super.staffOwners();
        }
    }

    @Test
    void theGuardCacheLoadsTheEffectiveLevel() {
        FlakyGuardStateStore store = new FlakyGuardStateStore(GuardState.of(GuardLevel.STRICT, GuardLevel.OFF, 0L));
        GuardStateCache cache = new GuardStateCache(store);

        cache.reload();

        assertEquals(GuardLevel.STRICT, cache.effective(), "the cache must hold the effective level once loaded");
    }

    @Test
    void aFailedGuardReloadKeepsTheLevelInUse() {
        FlakyGuardStateStore store = new FlakyGuardStateStore(GuardState.of(GuardLevel.STRICT, GuardLevel.OFF, 0L));
        GuardStateCache cache = new GuardStateCache(store);
        cache.reload();

        store.broken = true;
        cache.reload();

        assertEquals(GuardLevel.STRICT, cache.effective(), "a failed reload must keep the level that was in use");
    }

    @Test
    void aKnownOwnerIsReportedKnownAfterReload() {
        InMemoryOwnerDirectoryStore store = new InMemoryOwnerDirectoryStore();
        store.addKnown(OWNER);
        OwnerDirectoryCache cache = new OwnerDirectoryCache(store, Duration.ofMinutes(60));

        cache.reload();

        assertTrue(cache.isKnown(OWNER), "an owner the store reports must be known after the reload");
        assertFalse(cache.isStaff(OWNER), "a known owner is not staff unless the store says so");
    }

    @Test
    void aFailedOwnerReloadKeepsTheLastSnapshot() {
        FlakyOwnerDirectoryStore store = new FlakyOwnerDirectoryStore();
        store.addStaff(OWNER);
        OwnerDirectoryCache cache = new OwnerDirectoryCache(store, Duration.ofMinutes(60));
        cache.reload();

        store.broken = true;
        cache.reload();

        assertTrue(cache.isStaff(OWNER), "a failed reload must keep the staff owners that were loaded");
    }
}
