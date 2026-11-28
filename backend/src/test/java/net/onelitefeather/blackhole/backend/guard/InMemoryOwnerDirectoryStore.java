package net.onelitefeather.blackhole.backend.guard;

import java.util.HashSet;
import java.util.Set;

/** An {@link OwnerDirectoryStore} held in memory: the test adds the owners it needs. */
public class InMemoryOwnerDirectoryStore implements OwnerDirectoryStore {

    private final Set<String> known = new HashSet<>();
    private final Set<String> staff = new HashSet<>();

    public void addKnown(String owner) {
        this.known.add(owner);
    }

    public void addStaff(String owner) {
        this.staff.add(owner);
    }

    @Override
    public Set<String> knownOwners(long minPlaytimeMillis) {
        return Set.copyOf(this.known);
    }

    @Override
    public Set<String> staffOwners() {
        return Set.copyOf(this.staff);
    }
}
