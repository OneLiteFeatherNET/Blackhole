package net.onelitefeather.blackhole.backend.guard;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Re-evaluates the unattended level on a fixed interval, so the guard follows staff presence and the night window. */
@Singleton
public class GuardTickJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardTickJob.class);

    private final GuardTransitionService transitions;
    private final GuardStateCache cache;

    public GuardTickJob(GuardTransitionService transitions, GuardStateCache cache, MeterRegistry meters) {
        this.transitions = transitions;
        this.cache = cache;
        Gauge.builder("blackhole.guard.level", cache, value -> value.effective().ordinal())
                .description("The join guard level in force: 0 OFF, 1 STRICT, 2 LOCKDOWN")
                .register(meters);
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
        // Every replica follows the stored level within this tick, whichever replica wrote it. A failed reload keeps the level in use.
        this.cache.reload();
    }
}
