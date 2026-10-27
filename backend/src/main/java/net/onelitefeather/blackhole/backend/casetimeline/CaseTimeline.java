package net.onelitefeather.blackhole.backend.casetimeline;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.AppealRow;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.EvidenceRow;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.PunishmentRow;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.security.Attribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Records the lifecycle of a punishment in its case, <b>after</b> the flow's primary write. A
 * recording never fails that write: a failure is logged, counted in
 * {@value #FAILURE_METER} and left for the {@link CaseReconciler} to repair from the authoritative
 * rows. Every recording carries a deterministic dedupe key, so a retry, the reconciler or a replay
 * inserts nothing twice.
 *
 * <p>No {@code @Transactional}: the primary write and the recording are deliberately separate
 * statements (see the design of {@code add-punishment-case-timeline}, decision 4).</p>
 */
@Singleton
public class CaseTimeline {

    /** Counts recordings that failed. */
    public static final String FAILURE_METER = "blackhole.case.record.failures";

    private static final Logger LOGGER = LoggerFactory.getLogger(CaseTimeline.class);

    private final CaseEventRepository events;
    private final CaseWriter cases;
    private final Clock clock;
    private final Counter failures;

    public CaseTimeline(CaseEventRepository events, CaseWriter cases, Clock clock, MeterRegistry meters) {
        this.events = events;
        this.cases = cases;
        this.clock = clock;
        this.failures = Counter.builder(FAILURE_METER).description("Case timeline recordings that failed").register(meters);
    }

    /**
     * Appends the event. Never throws.
     *
     * @return {@code true} if a new event was stored, {@code false} if it already existed or the recording failed
     */
    public boolean record(CaseEventDraft draft) {
        try {
            return this.events.append(draft);
        } catch (RuntimeException e) {
            this.failures.increment();
            LOGGER.warn("Could not record {} for punishment {}; the reconciler will repair it", draft.type(), draft.punishmentIdentifier(), e);
            return false;
        }
    }

    /** A punishment was applied; the scores are known to the flow that deducted the cost, otherwise {@code null}. */
    public void applied(PunishmentEntity punishment, @Nullable Integer scoreBefore, @Nullable Integer scoreAfter) {
        recordSafely(() -> {
            PunishmentRow row = PunishmentRow.of(punishment);
            return CaseDrafts.applied(row, scoreBefore, scoreAfter, CaseEventOrigin.LIVE, row.createdAt());
        });
    }

    /**
     * A player was kicked. The kick is a punishment row of kind {@code KICK} and sits in its own case; the
     * event names the scope, the target and the ELO cost of its template step, if it had one.
     */
    public void kicked(PunishmentEntity kick) {
        recordSafely(() -> {
            SourceRows.KickRow row = SourceRows.KickRow.of(kick);
            return CaseDrafts.kicked(row, CaseEventOrigin.LIVE, row.createdAt());
        });
    }

    /** Staff revoked the punishment. */
    public void revoked(PunishmentEntity punishment, Attribution by) {
        recordSafely(() -> CaseDrafts.ended(PunishmentRow.of(punishment), CaseActor.of(by), false, CaseEventOrigin.LIVE, this.clock.millis()));
    }

    /** A granted full-lift appeal lifted the punishment. */
    public void lifted(PunishmentEntity punishment, UUID appealIdentifier, UUID reviewerOid) {
        recordSafely(() -> {
            PunishmentRow lifted = PunishmentRow.of(punishment).liftedBy(appealIdentifier, reviewerOid);
            return CaseDrafts.ended(lifted, lifted.endedByActor(), false, CaseEventOrigin.LIVE, this.clock.millis());
        });
    }

    /** An imported punishment was linked to a template by staff. */
    public void retemplated(PunishmentEntity punishment, @Nullable UUID fromTemplate, Attribution by) {
        recordSafely(() -> CaseDrafts.retemplated(punishment.getIdentifier(), punishment.getTemplate().getIdentifier().toString(),
                fromTemplate == null ? null : fromTemplate.toString(), CaseActor.of(by), CaseEventOrigin.LIVE, this.clock.millis()));
    }

    /** The punishment ran out; recorded once however often a sweep or a profile read notices it. */
    public void expired(PunishmentEntity punishment) {
        recordSafely(() -> {
            PunishmentRow row = PunishmentRow.of(punishment);
            Long expiresAt = row.expiresAt();
            return CaseDrafts.expired(row, expiresAt == null ? this.clock.millis() : expiresAt, CaseEventOrigin.LIVE, this.clock.millis());
        });
    }

    /** An appeal was filed; a closed case is reopened for it. */
    public void appealFiled(AppealEntity appeal) {
        recordSafely(() -> CaseDrafts.appealFiled(AppealRow.of(appeal), CaseEventOrigin.LIVE, this.clock.millis()));
        reopenIfClosed(appeal);
    }

    /** An appeal was decided. */
    public void appealDecided(AppealEntity appeal, @Nullable Long newExpirationAt) {
        recordSafely(() -> CaseDrafts.appealDecided(AppealRow.of(appeal), newExpirationAt, CaseEventOrigin.LIVE, this.clock.millis()));
    }

    /**
     * The message that comes with the decision of an appeal, written by the reviewer and visible to the
     * player. Recorded like the decision itself: after it, never failing it, once per appeal.
     */
    public void decisionMessage(AppealEntity decided, String text, CaseActor by, @Nullable String staffDisplayName) {
        recordSafely(() -> {
            String appealId = decided.getIdentifier().toString();
            return CaseDrafts.staffMessage(decided.getPunishment().getIdentifier(), CaseDrafts.decisionMessageId(appealId), appealId, text,
                    java.util.List.of(), MessageChannel.PANEL, by, CaseEventOrigin.LIVE, this.clock.millis(), staffDisplayName);
        });
    }

    /**
     * Evidence was attached to a punishment, or claimed by one. The event is dated by the item's creation:
     * evidence sent before the punishment existed shows up before the punishment was applied, as it happened.
     */
    public void evidenceAttached(EvidenceItemEntity item, CaseActor actor) {
        if (item.getPunishmentIdentifier() == null) {
            return;
        }
        recordSafely(() -> CaseDrafts.evidenceAttached(EvidenceRow.of(item), actor, CaseEventOrigin.LIVE, item.getCreatedAt()));
    }

    /** An item's status just changed to {@code LIFTED} or {@code REVOKED}. */
    public void evidenceStatusChanged(EvidenceItemEntity item, CaseActor actor) {
        if (item.getPunishmentIdentifier() == null) {
            return;
        }
        recordSafely(() -> CaseDrafts.evidenceStatusChanged(EvidenceRow.of(item), actor, false, CaseEventOrigin.LIVE, this.clock.millis()));
    }

    /** Builds and appends the event; a failure while building it is a failed recording like any other. */
    private boolean recordSafely(Supplier<CaseEventDraft> draft) {
        try {
            return record(draft.get());
        } catch (RuntimeException e) {
            this.failures.increment();
            LOGGER.warn("Could not build a case event; the reconciler will repair it", e);
            return false;
        }
    }

    private void reopenIfClosed(AppealEntity appeal) {
        try {
            this.cases.reopenIfClosed(appeal.getPunishment().getIdentifier(), this.clock.millis());
        } catch (RuntimeException e) {
            this.failures.increment();
            LOGGER.warn("Could not reopen the case of a punishment for a filed appeal", e);
        }
    }
}
