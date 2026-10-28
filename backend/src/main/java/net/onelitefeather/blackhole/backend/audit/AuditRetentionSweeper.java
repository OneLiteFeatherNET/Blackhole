package net.onelitefeather.blackhole.backend.audit;

import io.micronaut.scheduling.annotation.Scheduled;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * Deletes audit entries older than the retention ({@code blackhole.audit.retention}, one year by default), in
 * batches, on the interval {@code blackhole.audit.sweep-interval}. Uses the injected clock.
 */
@Singleton
public class AuditRetentionSweeper {

    static final int BATCH_SIZE = 500;

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditRetentionSweeper.class);

    private final StaffAuditStore store;
    private final Clock clock;
    private final Duration retention;

    public AuditRetentionSweeper(StaffAuditStore store, Clock clock,
                                 @Value("${blackhole.audit.retention:P365D}") Duration retention) {
        this.store = store;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(
            fixedDelay = "${blackhole.audit.sweep-interval:PT1H}",
            initialDelay = "${blackhole.audit.sweep-interval:PT1H}"
    )
    public void sweep() {
        long cutoff = this.clock.millis() - this.retention.toMillis();
        int deleted;
        int total = 0;
        do {
            deleted = this.store.deleteOccurredBefore(cutoff, BATCH_SIZE);
            total += deleted;
        } while (deleted == BATCH_SIZE);
        if (total > 0) {
            LOGGER.info("Deleted {} staff audit entries older than the retention", total);
        }
    }
}
