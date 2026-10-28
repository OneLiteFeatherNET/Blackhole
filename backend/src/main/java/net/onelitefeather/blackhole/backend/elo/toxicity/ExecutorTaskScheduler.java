package net.onelitefeather.blackhole.backend.elo.toxicity;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** The production {@link TaskScheduler}: one daemon thread that only schedules the timers. */
@Singleton
public class ExecutorTaskScheduler implements TaskScheduler {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "toxicity-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public Cancellable schedule(Duration delay, Runnable task) {
        ScheduledFuture<?> future = executor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
