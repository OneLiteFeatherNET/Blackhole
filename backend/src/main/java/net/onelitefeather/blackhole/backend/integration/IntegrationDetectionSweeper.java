package net.onelitefeather.blackhole.backend.integration;

import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * Unattached detections are personal data (an owner hash, a server, a check and its detail), so they are kept for
 * {@code blackhole.integration.detection-retention} (ISO-8601, default 180 days) counted from their occurrence time.
 * Attached detections are evidence and follow the evidence lifetime: this sweep never touches them.
 */
@Singleton
public class IntegrationDetectionSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationDetectionSweeper.class);

    private final IntegrationDetectionStore detections;
    private final Clock clock;
    private final Duration retention;

    public IntegrationDetectionSweeper(
            IntegrationDetectionStore detections,
            Clock clock,
            @Value("${blackhole.integration.detection-retention:P180D}") Duration retention
    ) {
        this.detections = detections;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(cron = "${blackhole.integration.detection-retention-sweep-cron:0 35 3 * * *}")
    void sweep() {
        long cutoff = this.clock.millis() - this.retention.toMillis();
        int deleted = this.detections.deleteUnattachedOccurredBefore(cutoff);
        if (deleted > 0) {
            LOG.info("Deleted {} unattached integration detection(s) past the retention period", deleted);
        }
    }
}
