package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.PunishmentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Repairs lost recordings. A live recording runs after the primary write and may fail; this sweep
 * finds the punishments, appeals and evidence items of the look-back window that have no lifecycle
 * event and appends it with origin {@link CaseEventOrigin#RECONSTRUCTED}. The time of the event is
 * the time of the source row, never the time of the run, and the actor is the source row's actor.
 * The dedupe keys make a run that races a live recording, or a second run, add nothing twice.
 *
 * <p>Notes and case-own changes never need repair: they are one statement each. Switched off by
 * {@code blackhole.case.reconcile.interval: 0}.</p>
 */
@Singleton
@Requires(property = "blackhole.case.reconcile.interval", notEquals = "0")
public class CaseReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaseReconciler.class);

    /** Rows read per query; a source with more gaps is drained batch by batch up to {@link #MAX_BATCHES}. */
    static final int BATCH_SIZE = 500;
    static final int MAX_BATCHES = 20;

    private final CaseSourceReader source;
    private final CaseTimeline timeline;
    private final Clock clock;
    private final Duration lookBack;

    public CaseReconciler(
            CaseSourceReader source,
            CaseTimeline timeline,
            Clock clock,
            @Value("${blackhole.case.reconcile.look-back:P2D}") Duration lookBack
    ) {
        this.source = source;
        this.timeline = timeline;
        this.clock = clock;
        this.lookBack = lookBack;
    }

    @Scheduled(
            fixedDelay = "${blackhole.case.reconcile.interval:10m}",
            initialDelay = "${blackhole.case.reconcile.interval:10m}"
    )
    void sweep() {
        try {
            int repaired = reconcile();
            if (repaired > 0) {
                LOGGER.info("Reconciled {} lost case event(s)", repaired);
            }
        } catch (RuntimeException e) {
            // A scheduled task must not throw, or the scheduler may stop running it.
            LOGGER.error("Case reconciliation failed, trying again at the next interval", e);
        }
    }

    /**
     * One reconciliation pass over the look-back window.
     *
     * @return how many events were added
     */
    public int reconcile() {
        long now = this.clock.millis();
        long since = now - this.lookBack.toMillis();
        int repaired = 0;
        repaired += drain(limit -> this.source.appliedMissing(since, limit),
                row -> CaseDrafts.applied(row, null, null, CaseEventOrigin.RECONSTRUCTED, row.createdAt()));
        repaired += drain(limit -> this.source.endedMissing(since, limit), CaseReconciler::ended);
        repaired += drain(limit -> this.source.expiredMissing(since, now, limit),
                row -> CaseDrafts.expired(row, row.expiresAt(), CaseEventOrigin.RECONSTRUCTED, row.expiresAt()));
        repaired += drain(limit -> this.source.filedMissing(since, limit),
                row -> CaseDrafts.appealFiled(row, CaseEventOrigin.RECONSTRUCTED, row.createdAt()));
        repaired += drain(limit -> this.source.decidedMissing(since, limit),
                row -> CaseDrafts.appealDecided(row, null, CaseEventOrigin.RECONSTRUCTED, row.updatedAt()));
        repaired += drain(limit -> this.source.attachedMissing(since, limit),
                row -> CaseDrafts.evidenceAttached(row, row.attachedBy(), CaseEventOrigin.RECONSTRUCTED,
                        row.attachedAt() != null ? row.attachedAt() : row.createdAt()));
        repaired += drain(limit -> this.source.statusChangeMissing(since, limit),
                row -> CaseDrafts.evidenceStatusChanged(row, CaseActor.unattributed(), row.statusChangedAt() == null,
                        CaseEventOrigin.RECONSTRUCTED, row.statusChangedAt() != null ? row.statusChangedAt() : row.createdAt()));
        return repaired;
    }

    /** The end of a punishment, at its metadata update time; without one the creation time stands in and the event is approximate. */
    private static CaseEventDraft ended(PunishmentRow row) {
        Long exact = row.updatedAt();
        return CaseDrafts.ended(row, row.endedByActor(), exact == null, CaseEventOrigin.RECONSTRUCTED, exact != null ? exact : row.createdAt());
    }

    /** Appends the events of each batch until a batch is not full, adds nothing, or the batch limit is reached. */
    private <T> int drain(IntFunction<List<T>> batchOf, Function<T, CaseEventDraft> draft) {
        int added = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<T> rows = batchOf.apply(BATCH_SIZE);
            int addedInBatch = 0;
            for (T row : rows) {
                if (this.timeline.record(draft.apply(row))) {
                    addedInBatch++;
                }
            }
            added += addedInBatch;
            if (rows.size() < BATCH_SIZE || addedInBatch == 0) {
                break;
            }
        }
        return added;
    }
}
