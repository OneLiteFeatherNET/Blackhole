package net.onelitefeather.blackhole.discord.sched;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@link Scheduler} over a {@link ScheduledExecutorService}. A task that throws is logged (class only) and does not
 * cancel a repeating task.
 */
public final class ExecutorScheduler implements Scheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutorScheduler.class);

    private final ScheduledExecutorService executor;

    public ExecutorScheduler(@NotNull ScheduledExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public void execute(@NotNull Runnable task) {
        this.executor.execute(guard(task));
    }

    @Override
    public void schedule(@NotNull Duration delay, @NotNull Runnable task) {
        this.executor.schedule(guard(task), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void repeat(@NotNull Duration initialDelay, @NotNull Duration period, @NotNull Runnable task) {
        this.executor.scheduleWithFixedDelay(guard(task), initialDelay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOGGER.error("A background task failed: {}", e.getClass().getName());
            }
        };
    }
}
