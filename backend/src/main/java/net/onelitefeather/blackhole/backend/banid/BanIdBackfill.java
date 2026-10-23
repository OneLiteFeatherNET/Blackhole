package net.onelitefeather.blackhole.backend.banid;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issues a Ban ID to every punishment that has none: the existing ones after the upgrade, and any
 * that a failed issue missed. Runs shortly after start and then periodically, in batches. Idempotent:
 * only rows without a Ban ID are touched, and none is ever overwritten, so a second run (or a second
 * replica running at the same time) changes nothing it should not. The gauge
 * {@code blackhole.banid.missing} is the number still waiting.
 */
@Singleton
public class BanIdBackfill {

    private static final Logger LOGGER = LoggerFactory.getLogger(BanIdBackfill.class);
    static final int BATCH = 100;

    private final BanIdStore store;
    private final BanIdService service;

    public BanIdBackfill(BanIdStore store, BanIdService service, MeterRegistry meters) {
        this.store = store;
        this.service = service;
        Gauge.builder("blackhole.banid.missing", this, BanIdBackfill::missing)
                .description("Punishments that have no Ban ID yet")
                .strongReference(true)
                .register(meters);
    }

    private double missing() {
        try {
            return this.store.countWithoutBanId();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    @Scheduled(
            initialDelay = "${blackhole.banid.backfill.initial-delay:5s}",
            fixedDelay = "${blackhole.banid.backfill.interval:10m}"
    )
    void scheduled() {
        try {
            run();
        } catch (RuntimeException e) {
            LOGGER.warn("Ban ID backfill failed ({}); it will run again", e.getClass().getSimpleName());
        }
    }

    /** @return how many Ban IDs this run issued */
    public int run() {
        int issued = 0;
        while (true) {
            int issuedInBatch = 0;
            for (String identifier : this.store.withoutBanId(BATCH)) {
                if (this.service.issue(identifier)) {
                    issuedInBatch++;
                }
            }
            issued += issuedInBatch;
            // A batch that issued nothing is empty, or every row of it failed or was taken by another replica.
            if (issuedInBatch == 0) {
                break;
            }
        }
        if (issued > 0) {
            LOGGER.info("Issued {} Ban ID(s) to existing punishments", issued);
        }
        return issued;
    }
}
