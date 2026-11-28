package net.onelitefeather.blackhole.velocity.guard;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The guard level this proxy last heard from the backend, kept in memory. It starts at {@link GuardLevel#OFF}
 * and is shared by the heartbeat, which writes it, and the login decision, which reads it when the backend
 * does not answer a login.
 */
@Singleton
public final class GuardLevelCache {

    private final AtomicReference<GuardLevel> level = new AtomicReference<>(GuardLevel.OFF);

    @Inject
    public GuardLevelCache() {
    }

    /**
     * @return the level in force as far as this proxy knows
     */
    public @NotNull GuardLevel current() {
        return this.level.get();
    }

    /**
     * @param next the level the backend reported
     */
    public void update(@NotNull GuardLevel next) {
        this.level.set(next);
    }
}
