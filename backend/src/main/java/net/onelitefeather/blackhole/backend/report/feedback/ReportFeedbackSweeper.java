package net.onelitefeather.blackhole.backend.report.feedback;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes report feedback nobody picked up before it expired, so a reporter who never logs in again
 * does not leave their hash here for good.
 */
@Singleton
public class ReportFeedbackSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportFeedbackSweeper.class);

    private final ReportFeedbackService service;

    public ReportFeedbackSweeper(ReportFeedbackService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelay = "${blackhole.report.feedback.sweep-interval:PT1H}",
            initialDelay = "${blackhole.report.feedback.sweep-interval:PT1H}"
    )
    void sweep() {
        try {
            long purged = this.service.purgeExpired();
            if (purged > 0) {
                LOGGER.info("Removed {} expired report feedback message(s)", purged);
            }
        } catch (RuntimeException e) {
            // A scheduled task must not throw, or the scheduler may stop running it.
            LOGGER.error("Report feedback sweep failed, trying again at the next interval", e);
        }
    }
}
