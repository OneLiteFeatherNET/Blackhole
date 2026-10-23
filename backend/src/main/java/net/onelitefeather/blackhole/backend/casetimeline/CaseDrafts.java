package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.AppealRow;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.EvidenceRow;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.PunishmentRow;

/**
 * Builds the case events of the lifecycle facts, with their deterministic dedupe keys. Used by the
 * live flows and by the reconciler alike, so a repaired event looks like the one that was lost.
 */
public final class CaseDrafts {

    private CaseDrafts() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** {@code punishment.applied}; the scores are only known to the flow that deducted the cost. */
    public static CaseEventDraft applied(PunishmentRow row, @Nullable Integer scoreBefore, @Nullable Integer scoreAfter,
                                         CaseEventOrigin origin, long occurredAt) {
        CaseActor actor = row.appliedBy();
        CaseEventPayload.Applied payload = new CaseEventPayload.Applied(row.sectionKey(), row.stepIndex(), row.stepCount(), row.eloCost(),
                scoreBefore, scoreAfter, row.permanent(), actor.trust() == ActorTrust.SYSTEM,
                row.templateId() == null ? null : row.templateId().toString(), row.expiresAt());
        return new CaseEventDraft(row.identifier(), payload, occurredAt, origin, actor, null, "applied:" + row.identifier());
    }

    /** {@code punishment.revoked}, or {@code punishment.lifted} when a granted full-lift appeal ended it. */
    public static CaseEventDraft ended(PunishmentRow row, CaseActor actor, boolean approximate, CaseEventOrigin origin, long occurredAt) {
        if (row.liftingAppeal() != null) {
            return new CaseEventDraft(row.identifier(), new CaseEventPayload.Lifted(row.liftingAppeal().toString(), approximate),
                    occurredAt, origin, actor, row.liftingAppeal().toString(), "lifted:" + row.identifier());
        }
        return new CaseEventDraft(row.identifier(), new CaseEventPayload.Revoked(approximate), occurredAt, origin, actor, null,
                "revoked:" + row.identifier());
    }

    /** {@code punishment.expired}, always by the system. */
    public static CaseEventDraft expired(PunishmentRow row, long expiredAt, CaseEventOrigin origin, long occurredAt) {
        return new CaseEventDraft(row.identifier(), new CaseEventPayload.Expired(expiredAt), occurredAt, origin,
                CaseActor.system(), null, "expired:" + row.identifier());
    }

    /** {@code appeal.filed}; filing records no actor. */
    public static CaseEventDraft appealFiled(AppealRow row, CaseEventOrigin origin, long occurredAt) {
        return new CaseEventDraft(row.punishmentIdentifier(), new CaseEventPayload.AppealFiled(row.identifier().toString(), row.eligible()),
                occurredAt, origin, CaseActor.unattributed(), row.identifier().toString(), "appeal.filed:" + row.identifier());
    }

    /** {@code appeal.decided}; the decision note and statement stay in the appeal. */
    public static CaseEventDraft appealDecided(AppealRow row, @Nullable Long newExpirationAt, CaseEventOrigin origin, long occurredAt) {
        CaseEventPayload.AppealDecided payload = new CaseEventPayload.AppealDecided(row.identifier().toString(), row.status().name(), newExpirationAt);
        return new CaseEventDraft(row.punishmentIdentifier(), payload, occurredAt, origin, row.decidedByActor(),
                row.identifier().toString(), "appeal.decided:" + row.identifier());
    }

    /** {@code evidence.attached}: ids and enums only, no content and no file name. */
    public static CaseEventDraft evidenceAttached(EvidenceRow row, CaseActor actor, CaseEventOrigin origin, long occurredAt) {
        return new CaseEventDraft(row.punishmentIdentifier(),
                new CaseEventPayload.EvidenceAttached(row.identifier().toString(), row.kind().name(), row.sourceSystem()),
                occurredAt, origin, actor, row.identifier().toString(), "evidence.attached:" + row.identifier());
    }

    /** {@code evidence.lifted} or {@code evidence.revoked}, whichever the item's status says. */
    public static CaseEventDraft evidenceStatusChanged(EvidenceRow row, CaseActor actor, boolean approximate, CaseEventOrigin origin, long occurredAt) {
        String id = row.identifier().toString();
        return switch (row.status()) {
            case LIFTED -> new CaseEventDraft(row.punishmentIdentifier(),
                    new CaseEventPayload.EvidenceLifted(id, row.liftedByAppeal() == null ? null : row.liftedByAppeal().toString(), approximate),
                    occurredAt, origin, actor, id, "evidence.lifted:" + id);
            case REVOKED -> new CaseEventDraft(row.punishmentIdentifier(), new CaseEventPayload.EvidenceRevoked(id, approximate),
                    occurredAt, origin, actor, id, "evidence.revoked:" + id);
            case VALID -> throw new IllegalArgumentException("a valid evidence item has no status change");
        };
    }
}
