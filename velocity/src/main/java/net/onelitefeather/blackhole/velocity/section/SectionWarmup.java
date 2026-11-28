package net.onelitefeather.blackhole.velocity.section;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Loads the sections off the startup path: the first attempt runs as a scheduled task, and a failed attempt is
 * retried with a wait that doubles from one second up to one minute. Until the first successful load the proxy
 * enforces with the section kinds the events carry; the periodic refresh of {@link SectionRegistry} takes over after.
 */
public final class SectionWarmup {

    private static final Logger LOGGER = LoggerFactory.getLogger(SectionWarmup.class);
    static final Duration FIRST_RETRY = Duration.ofSeconds(1);
    static final Duration MAX_RETRY = Duration.ofMinutes(1);

    /** Runs a task after a delay on the proxy's scheduler. */
    @FunctionalInterface
    public interface Scheduler {
        void schedule(Duration delay, Runnable task);
    }

    private final SectionRegistry sections;
    private final Scheduler scheduler;

    public SectionWarmup(SectionRegistry sections, Scheduler scheduler) {
        this.sections = sections;
        this.scheduler = scheduler;
    }

    /** Schedules the first load at once; the startup does not wait for it. */
    public void start() {
        this.scheduler.schedule(Duration.ZERO, () -> attempt(FIRST_RETRY));
    }

    /**
     * @param wait the wait before the next attempt if this one fails; doubled, up to {@link #MAX_RETRY}, after each failure
     */
    private void attempt(Duration wait) {
        if (this.sections.reload()) {
            LOGGER.info("Loaded {} punishment section(s)", this.sections.all().size());
            return;
        }
        LOGGER.warn("Punishment sections not loaded yet, retrying in {} seconds", wait.toSeconds());
        Duration next = wait.multipliedBy(2).compareTo(MAX_RETRY) > 0 ? MAX_RETRY : wait.multipliedBy(2);
        this.scheduler.schedule(wait, () -> attempt(next));
    }
}
