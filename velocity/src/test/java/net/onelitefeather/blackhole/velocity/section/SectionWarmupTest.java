package net.onelitefeather.blackhole.velocity.section;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The first section load runs off the startup path and is retried with a growing wait until the backend answers. */
class SectionWarmupTest {

    /** Records each scheduled task with its delay; the test decides when a task runs. */
    private static final class ManualScheduler implements SectionWarmup.Scheduler {
        final List<Duration> delays = new ArrayList<>();
        final Deque<Runnable> pending = new ArrayDeque<>();

        @Override
        public void schedule(Duration delay, Runnable task) {
            this.delays.add(delay);
            this.pending.add(task);
        }

        void runNext() {
            this.pending.poll().run();
        }
    }

    private int backendCalls;
    private int failuresLeft;

    private final SectionRegistry sections = new SectionRegistry(() -> {
        this.backendCalls++;
        if (this.failuresLeft-- > 0) {
            throw new IllegalStateException("backend not up yet");
        }
        return List.of(new SectionInfo("chat", "Chat", EnforcementKind.MUTE, List.of()));
    });

    @Test
    void aFailedFirstLoadIsRetriedWithAGrowingWaitUntilItSucceeds() {
        this.failuresLeft = 2;
        ManualScheduler scheduler = new ManualScheduler();

        new SectionWarmup(this.sections, scheduler).start();
        scheduler.runNext();
        scheduler.runNext();
        scheduler.runNext();

        assertEquals(List.of(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2)), scheduler.delays,
                "each failed attempt waits twice as long as the one before");
        assertEquals(3, this.backendCalls);
        assertEquals(1, this.sections.all().size(), "the sections are loaded once the backend answers");
        assertTrue(scheduler.pending.isEmpty(), "no retry is scheduled after a successful load");
    }

    @Test
    void theWaitBetweenRetriesIsCapped() {
        this.failuresLeft = Integer.MAX_VALUE;
        ManualScheduler scheduler = new ManualScheduler();

        new SectionWarmup(this.sections, scheduler).start();
        for (int i = 0; i < 12; i++) {
            scheduler.runNext();
        }

        assertEquals(Duration.ofMinutes(1), scheduler.delays.getLast(), "a backend that stays down is retried at most once a minute");
    }
}
