package net.onelitefeather.blackhole.backend.banid;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdCollisionException;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdReissueReason;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * The recovery after {@code BLACKHOLE_BANID_KEY} was replaced: gives every punishment whose Ban ID the
 * current key can no longer read a new one. Started explicitly by {@link BanIdReissueAllRunner}, never
 * implicitly.
 *
 * <p>Idempotent and resumable without any extra state: a Ban ID that decrypts under the current key
 * is already current and is left alone, so a repeat or a run that was interrupted only touches what
 * is left. Each reissue is one statement ({@link CaseChanges#reissueBanIdAtGeneration}) that raises
 * the generation, deletes the punishment's player sessions and appends {@code ban_id.reissued} with
 * the reason {@code KEY_ROTATION}; it is guarded by the generation read, so two replicas running
 * at once reissue a punishment once. The plain Ban ID stays inside {@link BanIdService#draw}; this
 * class logs and counts numbers only.
 */
@Singleton
public class BanIdReissueAll {

    private static final Logger LOGGER = LoggerFactory.getLogger(BanIdReissueAll.class);
    static final int BATCH = 100;
    private static final int COLLISION_ATTEMPTS = 5;

    /**
     * @param scanned  punishments with a Ban ID that were looked at
     * @param reissued punishments that got a new Ban ID in this run
     * @param skipped  punishments already current, or reissued by someone else meanwhile
     * @param failed   punishments whose reissue failed; run again to finish them
     */
    public record Result(int scanned, int reissued, int skipped, int failed) {
    }

    private final BanIdStore store;
    private final BanIdCrypto crypto;
    private final BanIdService service;
    private final CaseChanges changes;
    private final Clock clock;
    private final Counter reissuedCounter;
    private final Counter failedCounter;

    public BanIdReissueAll(BanIdStore store, BanIdCrypto crypto, BanIdService service, CaseChanges changes, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.crypto = crypto;
        this.service = service;
        this.changes = changes;
        this.clock = clock;
        this.reissuedCounter = Counter.builder("blackhole.banid.reissue.reissued")
                .description("Ban IDs reissued after a key rotation").register(meters);
        this.failedCounter = Counter.builder("blackhole.banid.reissue.failed")
                .description("Ban IDs a key rotation reissue could not replace").register(meters);
    }

    /** Walks all punishments in identifier order, in batches, and reissues the stale ones. */
    public Result run() {
        int scanned = 0;
        int reissued = 0;
        int skipped = 0;
        int failed = 0;
        String after = null;
        while (true) {
            List<BanIdStore.Held> batch = this.store.heldAfter(after, BATCH);
            if (batch.isEmpty()) {
                break;
            }
            for (BanIdStore.Held held : batch) {
                scanned++;
                after = held.identifier();
                if (this.crypto.decrypt(held.encrypted(), held.identifier()) != null) {
                    skipped++;
                    continue;
                }
                try {
                    if (reissue(held)) {
                        reissued++;
                        this.reissuedCounter.increment();
                    } else {
                        skipped++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    this.failedCounter.increment();
                    LOGGER.warn("Could not reissue the Ban ID of punishment {} ({})", held.identifier(), e.getClass().getSimpleName());
                }
            }
            LOGGER.info("Ban ID key rotation progress: {} looked at, {} reissued", scanned, reissued);
        }
        LOGGER.info("Ban ID key rotation finished: {} looked at, {} reissued, {} already current, {} failed", scanned, reissued, skipped, failed);
        return new Result(scanned, reissued, skipped, failed);
    }

    /** @return whether this call reissued it; {@code false} when it had already moved on */
    private boolean reissue(BanIdStore.Held held) {
        String identifier = held.identifier();
        long now = this.clock.millis();
        this.changes.ensureCase(identifier, now);
        for (int attempt = 0; attempt < COLLISION_ATTEMPTS; attempt++) {
            Optional<BanIdService.Material> material = this.service.draw(identifier);
            if (material.isEmpty()) {
                break;
            }
            try {
                return this.changes.reissueBanIdAtGeneration(identifier, held.generation(), material.get().hash(), material.get().encrypted(),
                        BanIdReissueReason.KEY_ROTATION, now, CaseActor.system()) != null;
            } catch (BanIdCollisionException e) {
                LOGGER.info("A new Ban ID candidate for punishment {} was taken; drawing another", identifier);
            }
        }
        throw new IllegalStateException("no free Ban ID could be drawn");
    }
}
