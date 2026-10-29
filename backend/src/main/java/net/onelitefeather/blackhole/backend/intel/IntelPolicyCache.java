package net.onelitefeather.blackhole.backend.intel;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * The policies and the allow-list of this replica, held in memory so that a login check reads no database. Services
 * reload it after they write; every replica also reloads it on the list reload interval, so a change made on another
 * replica takes effect within that interval. A failed reload keeps the last good snapshot.
 */
@Singleton
public class IntelPolicyCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(IntelPolicyCache.class);

    /** Until this value an allow-list entry never expires. */
    static final long PERMANENT = Long.MAX_VALUE;

    /**
     * @param policies   the policy per class; a class without an entry is FLAG
     * @param allowUntil the instant until which each owner is allow-listed
     */
    record Snapshot(Map<IntelClass, IntelPolicy> policies, Map<String, Long> allowUntil) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of());
    }

    private final IntelRecordStore store;
    private volatile Snapshot current = Snapshot.EMPTY;

    public IntelPolicyCache(IntelRecordStore store) {
        this.store = store;
    }

    /** @return the policy of the class: FLAG unless an admin has stored another one */
    public IntelPolicy policyOf(IntelClass classification) {
        return this.current.policies().getOrDefault(classification, IntelPolicy.FLAG);
    }

    /** @return the policy of every class, a missing class as FLAG */
    public Map<IntelClass, IntelPolicy> policies() {
        Map<IntelClass, IntelPolicy> all = new EnumMap<>(IntelClass.class);
        for (IntelClass classification : IntelClass.values()) {
            all.put(classification, policyOf(classification));
        }
        return all;
    }

    /** @return whether the owner has an allow-list entry that has not expired at {@code now} */
    public boolean allowListed(String ownerHash, long now) {
        Long until = this.current.allowUntil().get(ownerHash);
        return until != null && now < until;
    }

    /** Reads the policies and the allow-list again. A failure keeps the snapshot in use. */
    public synchronized void reload() {
        try {
            Map<IntelClass, IntelPolicy> policies = new EnumMap<>(IntelClass.class);
            policies.putAll(this.store.loadPolicies());
            Map<String, Long> allowUntil = new HashMap<>();
            for (IntelAllowEntry entry : this.store.loadAllowList()) {
                allowUntil.put(entry.ownerHash(), entry.expiresAt() == null ? PERMANENT : entry.expiresAt());
            }
            this.current = new Snapshot(policies, allowUntil);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not reload the intel policies and allow-list; keeping the last ones");
        }
    }

    /** Periodic reload, so that a change made on another replica is seen. */
    @Scheduled(
            fixedDelay = "${blackhole.intel.lists.reload-interval:PT5M}",
            initialDelay = "${blackhole.intel.lists.reload-interval:PT5M}"
    )
    void poll() {
        reload();
    }
}
