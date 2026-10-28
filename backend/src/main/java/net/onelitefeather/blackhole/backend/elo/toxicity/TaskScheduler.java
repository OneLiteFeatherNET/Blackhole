package net.onelitefeather.blackhole.backend.elo.toxicity;

import java.time.Duration;

/**
 * Runs a task after a delay. Production uses a real executor; tests use a scheduler that runs tasks on demand,
 * so timeouts and the batch linger never wait for real time.
 */
public interface TaskScheduler {

    Cancellable schedule(Duration delay, Runnable task);

    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }
}
