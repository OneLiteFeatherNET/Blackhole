package net.onelitefeather.blackhole.backend.guard;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publishes the refusal summary on a fixed interval, so a wave of refusals is one message rather than one per login. */
@Singleton
public class GuardSummaryJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuardSummaryJob.class);

    private final GuardSummaryService summary;

    public GuardSummaryJob(GuardSummaryService summary) {
        this.summary = summary;
    }

    @Scheduled(
            fixedDelay = "${blackhole.guard.summary-interval:PT10M}",
            initialDelay = "${blackhole.guard.summary-interval:PT10M}"
    )
    void publish() {
        try {
            this.summary.publish();
        } catch (RuntimeException e) {
            // A scheduled task must not throw, or the scheduler may stop running it.
            LOGGER.error("Join guard summary failed, trying again at the next interval", e);
        }
    }
}
