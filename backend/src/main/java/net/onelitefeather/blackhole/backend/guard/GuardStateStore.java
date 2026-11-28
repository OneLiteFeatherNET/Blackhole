package net.onelitefeather.blackhole.backend.guard;

import java.util.Optional;

/** The single guard row. Writes are conditional, so concurrent replicas cannot both apply one transition. */
public interface GuardStateStore {

    Optional<GuardState> current();

    /**
     * Replaces {@code expected} with {@code next} only while the row still holds {@code expected}.
     *
     * @return true when this call made the change; false when another replica changed the row first
     */
    boolean compareAndSet(GuardState expected, GuardState next);
}
