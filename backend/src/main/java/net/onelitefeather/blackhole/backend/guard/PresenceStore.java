package net.onelitefeather.blackhole.backend.guard;

import java.util.List;
import java.util.OptionalLong;

/**
 * The staff presence the proxies report: one entry per proxy, and the last time any proxy reported staff. Held outside
 * the database (Dragonfly in production), because it is worth nothing after the presence ttl.
 */
public interface PresenceStore {

    /** Replaces the entry of one proxy. */
    void report(String proxyId, int staffOnline, long reportedAt);

    /** Records that staff were seen at {@code at}. */
    void markStaffSeen(long at);

    /** Every proxy entry, fresh or stale. An unreadable store reports none, which fails toward the guard. */
    List<PresenceEntry> entries();

    /** When staff were last seen on any proxy, or empty if never or unreadable. */
    OptionalLong lastStaffSeen();

    /** One proxy's last report. */
    record PresenceEntry(String proxyId, int staffOnline, long reportedAt) {
    }
}
