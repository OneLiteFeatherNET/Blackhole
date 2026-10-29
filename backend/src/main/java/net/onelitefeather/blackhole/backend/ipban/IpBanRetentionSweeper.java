package net.onelitefeather.blackhole.backend.ipban;

import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * Deletes lifted and expired IP ban rows once {@code blackhole.ipban.retention} has passed since they closed. Active
 * rows are never deleted. Mirrors {@code IpCorrelationRetentionSweeper}.
 */
@Singleton
public class IpBanRetentionSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(IpBanRetentionSweeper.class);

    private final IpBanStore store;
    private final Clock clock;
    private final Duration retention;

    public IpBanRetentionSweeper(IpBanStore store, Clock clock, @Value("${blackhole.ipban.retention:P365D}") Duration retention) {
        this.store = store;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(cron = "${blackhole.ipban.retention-sweep-cron:0 40 3 * * *}")
    void sweep() {
        int deleted = sweepAt(this.clock.millis());
        if (deleted > 0) {
            LOGGER.info("Deleted {} closed IP ban row(s) past the retention window", deleted);
        }
    }

    /** @return the number of rows deleted; the cut-off is {@code now} minus the retention */
    int sweepAt(long now) {
        return this.store.deleteClosedBefore(now - this.retention.toMillis());
    }
}
