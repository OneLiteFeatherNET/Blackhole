package net.onelitefeather.blackhole.backend.guard;

import java.util.Optional;

/** A {@link GuardStateStore} held in memory. Its compare-and-set has the same meaning as the conditional UPDATE. */
class InMemoryGuardStateStore implements GuardStateStore {

    private GuardState state;

    InMemoryGuardStateStore(GuardState initial) {
        this.state = initial;
    }

    @Override
    public Optional<GuardState> current() {
        return Optional.ofNullable(this.state);
    }

    @Override
    public synchronized boolean compareAndSet(GuardState expected, GuardState next) {
        if (this.state == null || !this.state.equals(expected)) {
            return false;
        }
        this.state = next;
        return true;
    }
}
