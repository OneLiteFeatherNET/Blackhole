package net.onelitefeather.blackhole.discord.testing;

import net.onelitefeather.blackhole.discord.auth.MutableClock;
import net.onelitefeather.blackhole.discord.sched.Scheduler;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A scheduler the test advances by hand. Time is the injected clock; nothing runs until {@link #runDue()} or {@link #advance}
 * is called, so there is no sleeping and no thread. Each test builds its own.
 */
public final class ManualScheduler implements Scheduler {

    private record Task(long order, Instant due, Duration period, Runnable body) {
    }

    private final MutableClock clock;
    private final List<Task> tasks = new ArrayList<>();
    private long counter;

    public ManualScheduler(@NotNull MutableClock clock) {
        this.clock = clock;
    }

    @Override
    public void execute(@NotNull Runnable task) {
        this.tasks.add(new Task(this.counter++, this.clock.instant(), null, task));
    }

    @Override
    public void schedule(@NotNull Duration delay, @NotNull Runnable task) {
        this.tasks.add(new Task(this.counter++, this.clock.instant().plus(delay), null, task));
    }

    @Override
    public void repeat(@NotNull Duration initialDelay, @NotNull Duration period, @NotNull Runnable task) {
        this.tasks.add(new Task(this.counter++, this.clock.instant().plus(initialDelay), period, task));
    }

    /** Runs every task that is due now, including those they add, in due order. */
    public void runDue() {
        while (true) {
            Task next = this.tasks.stream().filter(t -> !t.due().isAfter(this.clock.instant()))
                    .min(Comparator.comparing(Task::due).thenComparingLong(Task::order)).orElse(null);
            if (next == null) {
                return;
            }
            this.tasks.remove(next);
            if (next.period() != null) {
                this.tasks.add(new Task(this.counter++, this.clock.instant().plus(next.period()), next.period(), next.body()));
            }
            next.body().run();
        }
    }

    /** Moves the clock forward step by step, running what falls due on the way. */
    public void advance(@NotNull Duration duration) {
        Instant end = this.clock.instant().plus(duration);
        while (true) {
            Instant nextDue = this.tasks.stream().map(Task::due).min(Comparator.naturalOrder()).orElse(null);
            if (nextDue == null || nextDue.isAfter(end)) {
                break;
            }
            if (nextDue.isAfter(this.clock.instant())) {
                this.clock.advance(Duration.between(this.clock.instant(), nextDue));
            }
            runDue();
        }
        if (end.isAfter(this.clock.instant())) {
            this.clock.advance(Duration.between(this.clock.instant(), end));
        }
    }

    /** @return how many tasks are waiting, repeating ones included */
    public int pending() {
        return this.tasks.size();
    }
}
