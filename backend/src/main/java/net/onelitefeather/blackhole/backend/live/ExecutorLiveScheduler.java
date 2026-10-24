package net.onelitefeather.blackhole.backend.live;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The production scheduler: one daemon thread for the keep-alives and lifetimes of the live streams. A task only writes a frame or
 * ends a stream, so one thread is enough; a task that fails is logged and never stops the thread.
 */
@Singleton
public class ExecutorLiveScheduler implements LiveScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutorLiveScheduler.class);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "live-streams");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public Handle schedule(Duration delay, Runnable task) {
        ScheduledFuture<?> future = this.executor.schedule(() -> {
            try {
                task.run();
            } catch (RuntimeException failed) {
                LOGGER.warn("A live stream task failed", failed);
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @PreDestroy
    void close() {
        this.executor.shutdownNow();
    }
}
