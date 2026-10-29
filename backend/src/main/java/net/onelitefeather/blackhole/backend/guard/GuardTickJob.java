package net.onelitefeather.blackhole.backend.guard;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Re-evaluates the unattended level on a fixed interval, so the guard follows staff presence and the night window. */
@Singleton
public class GuardTickJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardTickJob.class);

    private final GuardTransitionService transitions;

    public GuardTickJob(GuardTransitionService transitions) {
        this.transitions = transitions;
    }

    @Scheduled(
            fixedDelay = "${blackhole.guard.tick-interval:PT30S}",
            initialDelay = "${blackhole.guard.tick-interval:PT30S}"
    )
    void tick() {
        try {
            this.transitions.reconcileAutomatic();
        } catch (RuntimeException e) {
            // A scheduled task must not throw, or the scheduler may stop running it.
            LOGGER.error("Join guard tick failed, trying again at the next interval", e);
        }
    }
}
