package net.onelitefeather.blackhole.discord.sched;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * Where the bot's background work runs. The production implementation is one thread, so the sync, lifecycle and
 * leak code never races with itself; the tests inject a scheduler they advance by hand, so nothing waits on a clock.
 */
public interface Scheduler {

    /** Runs the task as soon as the thread is free. */
    void execute(@NotNull Runnable task);

    /** Runs the task once after the delay. */
    void schedule(@NotNull Duration delay, @NotNull Runnable task);

    /** Runs the task after {@code initialDelay} and then again {@code period} after each run finished. */
    void repeat(@NotNull Duration initialDelay, @NotNull Duration period, @NotNull Runnable task);
}
