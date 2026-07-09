package net.onelitefeather.blackhole.backend.evasion;

import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * The IP correlation table is itself personal data (see {@code IpCorrelationTokenEntity}), so it
 * gets its own rolling retention window rather than being kept indefinitely - mirrors
 * {@code PunishmentExpirySweeper}/{@code EloDecaySweeper}'s pattern.
 */
@Singleton
public class IpCorrelationRetentionSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(IpCorrelationRetentionSweeper.class);

    private final IpCorrelationTokenRepository repository;
    private final int retentionDays;

    public IpCorrelationRetentionSweeper(
            IpCorrelationTokenRepository repository,
            @Value("${blackhole.evasion.retention-days:90}") int retentionDays
    ) {
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${blackhole.evasion.retention-sweep-cron:0 30 3 * * *}")
    void sweep() {
        long cutoff = System.currentTimeMillis() - Duration.ofDays(this.retentionDays).toMillis();
        List<IpCorrelationTokenEntity> expired = this.repository.findByLastSeenLessThan(cutoff);
        if (expired.isEmpty()) {
            return;
        }
        this.repository.deleteAll(expired);
        LOGGER.info("Deleted {} IP correlation token(s) past the {}-day retention window", expired.size(), this.retentionDays);
    }
}
