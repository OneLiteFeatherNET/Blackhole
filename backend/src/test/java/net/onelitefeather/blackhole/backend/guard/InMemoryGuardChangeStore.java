package net.onelitefeather.blackhole.backend.guard;

import java.util.ArrayList;
import java.util.List;

/** A {@link GuardChangeStore} that keeps the history in a list. */
class InMemoryGuardChangeStore implements GuardChangeStore {

    private final List<GuardChange> changes = new ArrayList<>();

    @Override
    public void append(GuardChange change) {
        this.changes.add(change);
    }

    List<GuardChange> changes() {
        return this.changes;
    }
}
