package net.onelitefeather.blackhole.backend.live;

import java.time.Duration;

/**
 * Runs a task after a delay. The hub's keep-alives and lifetimes go through it, so a test drives them with virtual time instead of
 * waiting. Production uses the injected scheduled executor.
 */
public interface LiveScheduler {

    /** Cancels a scheduled task that has not run yet. */
    @FunctionalInterface
    interface Handle {
        void cancel();
    }

    Handle schedule(Duration delay, Runnable task);
}
