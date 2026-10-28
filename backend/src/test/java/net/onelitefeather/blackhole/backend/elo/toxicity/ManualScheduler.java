package net.onelitefeather.blackhole.backend.elo.toxicity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A {@link TaskScheduler} that runs tasks only when a test asks, in order of their delay. No thread and no
 * real time is involved.
 */
final class ManualScheduler implements TaskScheduler {

    private final List<Entry> entries = new ArrayList<>();
    private final List<Duration> delays = new ArrayList<>();

    @Override
    public Cancellable schedule(Duration delay, Runnable task) {
        Entry entry = new Entry(delay, task);
        entries.add(entry);
        delays.add(delay);
        return () -> entry.cancelled = true;
    }

    /** Every delay requested so far, in request order. */
    List<Duration> scheduledDelays() {
        return List.copyOf(delays);
    }

    /** Runs the pending task with the shortest delay (ties by request order). Returns false when none is left. */
    boolean runNext() {
        Entry next = entries.stream()
                .filter(e -> !e.cancelled && !e.done)
                .min(Comparator.comparing(e -> e.delay))
                .orElse(null);
        if (next == null) {
            return false;
        }
        next.done = true;
        next.task.run();
        return true;
    }

    /** Runs pending tasks until none is left, including tasks that the ran tasks schedule. */
    void runAll() {
        while (runNext()) {
            // keep running
        }
    }

    private static final class Entry {
        private final Duration delay;
        private final Runnable task;
        private boolean cancelled;
        private boolean done;

        private Entry(Duration delay, Runnable task) {
            this.delay = delay;
            this.task = task;
        }
    }
}
