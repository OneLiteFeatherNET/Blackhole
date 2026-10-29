package net.onelitefeather.blackhole.backend.guard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** A {@link PresenceStore} held in memory, for tests that must not reach Dragonfly. */
class InMemoryPresenceStore implements PresenceStore {

    private final Map<String, PresenceEntry> entries = new HashMap<>();
    private OptionalLong lastSeen = OptionalLong.empty();

    @Override
    public void report(String proxyId, int staffOnline, long reportedAt) {
        this.entries.put(proxyId, new PresenceEntry(proxyId, staffOnline, reportedAt));
    }

    @Override
    public void markStaffSeen(long at) {
        this.lastSeen = OptionalLong.of(at);
    }

    @Override
    public List<PresenceEntry> entries() {
        return new ArrayList<>(this.entries.values());
    }

    @Override
    public OptionalLong lastStaffSeen() {
        return this.lastSeen;
    }
}
