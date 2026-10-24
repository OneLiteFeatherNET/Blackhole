package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.velocity.cloudnet.CatalogueReporter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Virtual time: tasks run when the test advances the clock, never on a real timer. */
final class ManualTimer implements CatalogueReporter.Timer {

    private record Once(Duration at, Runnable task) {
    }

    private final List<Once> once = new ArrayList<>();
    private final List<Runnable> repeating = new ArrayList<>();
    private Duration period;
    private Duration now = Duration.ZERO;

    @Override
    public void after(Duration delay, Runnable task) {
        this.once.add(new Once(this.now.plus(delay), task));
    }

    @Override
    public void every(Duration period, Runnable task) {
        this.period = period;
        this.repeating.add(task);
    }

    /** How many one-shot tasks wait. */
    int pending() {
        return this.once.size();
    }

    /** Advances one second at a time so ordering is the same as a real clock. */
    void advance(Duration by) {
        Duration end = this.now.plus(by);
        while (this.now.compareTo(end) < 0) {
            this.now = this.now.plusSeconds(1);
            runDue();
            if (this.period != null && this.now.toSeconds() % this.period.toSeconds() == 0) {
                List.copyOf(this.repeating).forEach(Runnable::run);
            }
        }
    }

    /** Runs the tasks due right now. */
    void runDue() {
        for (Once due : List.copyOf(this.once)) {
            if (due.at().compareTo(this.now) <= 0) {
                this.once.remove(due);
                due.task().run();
            }
        }
    }
}
