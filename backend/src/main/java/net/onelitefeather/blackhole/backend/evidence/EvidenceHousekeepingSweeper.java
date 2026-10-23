package net.onelitefeather.blackhole.backend.evidence;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evidence.service.EvidenceService;
import net.onelitefeather.blackhole.backend.evidence.storage.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

/**
 * Periodic housekeeping for evidence: deletes pending evidence nobody claimed within
 * {@code pending-ttl}, deletes stored files that no row references any more, and - only when an
 * {@code audit-retention} is configured - purges old audit rows. It never deletes evidence that
 * belongs to a punishment: evidence lives as long as its punishment record.
 */
@Singleton
public class EvidenceHousekeepingSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceHousekeepingSweeper.class);

    /** A file without a row is only an orphan once an upload that is still saving its row cannot be behind it. */
    static final Duration ORPHAN_GRACE = Duration.ofDays(1);

    private final EvidenceService service;
    private final EvidenceItemRepository items;
    private final EvidenceAuditRepository audit;
    private final BlobStore blobs;
    private final EvidenceConfig config;
    private final Clock clock;

    public EvidenceHousekeepingSweeper(
            EvidenceService service,
            EvidenceItemRepository items,
            EvidenceAuditRepository audit,
            BlobStore blobs,
            EvidenceConfig config,
            Clock clock
    ) {
        this.service = service;
        this.items = items;
        this.audit = audit;
        this.blobs = blobs;
        this.config = config;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelay = "${blackhole.evidence.housekeeping.interval:PT1H}",
            initialDelay = "${blackhole.evidence.housekeeping.interval:PT1H}"
    )
    void sweep() {
        try {
            long now = this.clock.millis();
            int expired = this.service.deleteUnclaimedCreatedBefore(
                    now - this.config.getPendingTtl().toMillis(), now - this.config.getReportPendingTtl().toMillis());
            int orphans = deleteOrphanFiles();
            int purged = purgeAudit();
            if (expired + orphans + purged > 0) {
                LOGGER.info("Evidence housekeeping removed {} unclaimed item(s), {} orphaned file(s), {} audit row(s)", expired, orphans, purged);
            }
        } catch (RuntimeException e) {
            // A scheduled task must not throw, or the scheduler may stop running it.
            LOGGER.error("Evidence housekeeping failed, trying again at the next interval", e);
        }
    }

    /** Deletes files older than {@link #ORPHAN_GRACE} that no evidence row points at. */
    int deleteOrphanFiles() {
        int deleted = 0;
        try {
            for (BlobStore.StoredBlob blob : this.blobs.listOlderThan(this.clock.instant().minus(ORPHAN_GRACE))) {
                if (this.items.existsByFileKey(blob.key())) {
                    continue;
                }
                try {
                    if (this.blobs.delete(blob.key())) {
                        deleted++;
                    }
                } catch (IOException e) {
                    LOGGER.warn("Could not delete the orphaned evidence file {}, retrying at the next sweep", blob.key(), e);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not list the stored evidence files, retrying at the next sweep", e);
        }
        return deleted;
    }

    /** Deletes audit rows older than the configured retention; keeps them all when none is configured. */
    int purgeAudit() {
        Duration retention = this.config.getAuditRetention();
        if (retention == null) {
            return 0;
        }
        var expired = this.audit.findByOccurredAtLessThan(this.clock.millis() - retention.toMillis());
        expired.forEach(this.audit::delete);
        return expired.size();
    }
}
