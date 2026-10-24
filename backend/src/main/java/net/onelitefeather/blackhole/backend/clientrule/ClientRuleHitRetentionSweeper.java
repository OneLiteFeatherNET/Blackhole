package net.onelitefeather.blackhole.backend.clientrule;

import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * Hits are personal data (a hashed player and a time), so they live for a short, configurable time.
 * Each rule keeps its running total in {@code client_rule.hit_count}, which the sweep never touches.
 */
@Singleton
public class ClientRuleHitRetentionSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientRuleHitRetentionSweeper.class);

    private final ClientRuleHitStore hits;
    private final Clock clock;
    private final int retentionDays;

    public ClientRuleHitRetentionSweeper(
            ClientRuleHitStore hits,
            Clock clock,
            @Value("${blackhole.clientrules.hit-retention-days:30}") int retentionDays
    ) {
        this.hits = hits;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${blackhole.clientrules.hit-retention-sweep-cron:0 45 3 * * *}")
    void sweep() {
        long cutoff = this.clock.millis() - Duration.ofDays(this.retentionDays).toMillis();
        int deleted = this.hits.deleteBefore(cutoff);
        if (deleted > 0) {
            LOGGER.info("Deleted {} client rule hit(s) past the {}-day retention window", deleted, this.retentionDays);
        }
    }
}
