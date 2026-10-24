package net.onelitefeather.blackhole.backend.evasion;

import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * Fingerprint hashes are personal data and a weaker signal than the IP token, so they live shorter
 * (default 30 days against 90).
 */
@Singleton
public class ClientFingerprintRetentionSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientFingerprintRetentionSweeper.class);

    private final ClientFingerprintStore store;
    private final Clock clock;
    private final int retentionDays;

    public ClientFingerprintRetentionSweeper(
            ClientFingerprintStore store,
            Clock clock,
            @Value("${blackhole.evasion.fingerprint.retention-days:30}") int retentionDays
    ) {
        this.store = store;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${blackhole.evasion.fingerprint.retention-sweep-cron:0 50 3 * * *}")
    void sweep() {
        long cutoff = this.clock.millis() - Duration.ofDays(this.retentionDays).toMillis();
        int deleted = this.store.deleteBefore(cutoff);
        if (deleted > 0) {
            LOGGER.info("Deleted {} client fingerprint(s) past the {}-day retention window", deleted, this.retentionDays);
        }
    }
}
