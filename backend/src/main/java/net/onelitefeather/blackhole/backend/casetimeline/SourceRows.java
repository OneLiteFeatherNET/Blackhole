package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceStatus;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.phoca.metadata.Expirable;
import net.onelitefeather.phoca.metadata.Metadata;

import java.util.Map;
import java.util.UUID;

/**
 * The parts of the authoritative rows a case event is built from. The live flows build them from
 * the entities they just wrote, the reconciler from the rows it finds without their event, so both
 * describe a fact the same way ({@link CaseDrafts}).
 */
public final class SourceRows {

    private SourceRows() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * A punishment.
     *
     * @param endedBy         the attributed UUID that revoked or lifted it (its metadata {@code revokedBy})
     * @param endedRecordedBy the verified principal that did (its metadata {@code recordedByOid})
     * @param liftingAppeal   the granted full-lift appeal that lifted it, if one did
     * @param updatedAt       its metadata update time: when it was last changed, which for a lifted punishment is the lift
     */
    public record PunishmentRow(
            String identifier, @Nullable UUID source, @Nullable UUID recordedByOid, @Nullable UUID actorOid,
            @Nullable String sectionKey, @Nullable Integer stepIndex, @Nullable Integer stepCount, @Nullable Integer eloCost,
            boolean permanent, boolean lifted, long createdAt, @Nullable Long expiresAt, @Nullable Long updatedAt,
            @Nullable UUID templateId, @Nullable UUID endedBy, @Nullable UUID endedRecordedBy, @Nullable UUID liftingAppeal
    ) {

        public static PunishmentRow of(PunishmentEntity punishment) {
            Map<String, Object> meta = punishment.getMetaData();
            return new PunishmentRow(
                    punishment.getIdentifier(), punishment.getSource(), punishment.getRecordedByOid(), punishment.getActorOid(),
                    punishment.getSectionKey(), punishment.getStepIndex(), punishment.getStepCount(), punishment.getEloCost(),
                    punishment.isPermanent(), punishment.isLifted(), punishment.getCreatedAt() == null ? 0 : punishment.getCreatedAt(),
                    number(meta.get(Expirable.META_DATA_KEY_EXPIRATION_DATE)), number(meta.get(Metadata.META_DATA_KEY_UPDATE_DATE)),
                    punishment.getTemplate() == null ? null : punishment.getTemplate().getIdentifier(),
                    uuid(meta.get("revokedBy")), uuid(meta.get("recordedByOid")), null);
        }

        /** This punishment as lifted by a granted full-lift appeal of the reviewer. */
        public PunishmentRow liftedBy(UUID appealIdentifier, UUID reviewerOid) {
            return new PunishmentRow(this.identifier, this.source, this.recordedByOid, this.actorOid, this.sectionKey, this.stepIndex,
                    this.stepCount, this.eloCost, this.permanent, true, this.createdAt, this.expiresAt, this.updatedAt, this.templateId,
                    reviewerOid, reviewerOid, appealIdentifier);
        }

        /** Who applied it. */
        public CaseActor appliedBy() {
            return CaseActor.ofRow(this.source, this.recordedByOid, this.actorOid);
        }

        /** Who revoked or lifted it, as far as the row says. */
        public CaseActor endedByActor() {
            return CaseActor.ofRow(this.endedBy, this.endedRecordedBy, null);
        }
    }

    /**
     * A kick: a punishment row of kind {@code KICK}. It has no section step, expiry or lift, so it is not a
     * {@link PunishmentRow}: the lifecycle queries never see it.
     *
     * @param scope  {@code NETWORK}, {@code SERVER} or {@code SECTION}
     * @param target the server or section of a scoped kick
     */
    public record KickRow(
            String identifier, @Nullable UUID source, @Nullable UUID recordedByOid, @Nullable UUID actorOid,
            @Nullable String scope, @Nullable String target, @Nullable Integer eloCost, long createdAt
    ) {

        public static KickRow of(PunishmentEntity kick) {
            return new KickRow(kick.getIdentifier(), kick.getSource(), kick.getRecordedByOid(), kick.getActorOid(),
                    kick.getKickScope(), kick.getKickTarget(), kick.getEloCost(), kick.getCreatedAt() == null ? 0 : kick.getCreatedAt());
        }

        /** Who kicked. */
        public CaseActor kickedBy() {
            return CaseActor.ofRow(this.source, this.recordedByOid, this.actorOid);
        }
    }

    /**
     * A warning: a punishment row of kind {@code WARN}. Like a kick it has no section step, expiry or lift.
     *
     * @param identifier the warning's Ban ID
     */
    public record WarnRow(
            String identifier, @Nullable UUID source, @Nullable UUID recordedByOid, @Nullable UUID actorOid, long createdAt
    ) {

        public static WarnRow of(PunishmentEntity warning) {
            return new WarnRow(warning.getIdentifier(), warning.getSource(), warning.getRecordedByOid(), warning.getActorOid(),
                    warning.getCreatedAt() == null ? 0 : warning.getCreatedAt());
        }

        /** Who gave the warning. */
        public CaseActor warnedBy() {
            return CaseActor.ofRow(this.source, this.recordedByOid, this.actorOid);
        }
    }

    /** An appeal. */
    public record AppealRow(
            UUID identifier, String punishmentIdentifier, AppealStatus status, @Nullable UUID decidedBy,
            @Nullable UUID recordedByOid, long createdAt, long updatedAt
    ) {

        public static AppealRow of(AppealEntity appeal) {
            return new AppealRow(appeal.getIdentifier(), appeal.getPunishment().getIdentifier(), appeal.getStatus(),
                    appeal.getDecidedBy(), appeal.getRecordedByOid(), appeal.getCreatedAt(), appeal.getUpdatedAt());
        }

        /** Whether the appeal passed the eligibility checklist when it was filed. */
        public boolean eligible() {
            return this.status != AppealStatus.INELIGIBLE;
        }

        /** Who decided it. */
        public CaseActor decidedByActor() {
            return CaseActor.ofRow(this.decidedBy, this.recordedByOid, null);
        }
    }

    /**
     * An evidence item of a punishment.
     *
     * @param attachedAt the exact time of the {@code ATTACHED} audit line, if there is one
     */
    public record EvidenceRow(
            UUID identifier, String punishmentIdentifier, EvidenceKind kind, @Nullable String sourceSystem,
            @Nullable UUID createdBy, @Nullable UUID recordedByOid, @Nullable UUID actorOid, long createdAt,
            @Nullable Long attachedAt, EvidenceStatus status, @Nullable Long statusChangedAt, @Nullable UUID liftedByAppeal
    ) {

        public static EvidenceRow of(EvidenceItemEntity item) {
            return new EvidenceRow(item.getIdentifier(), item.getPunishmentIdentifier(), item.getKind(), item.getSourceSystem(),
                    item.getCreatedBy(), item.getRecordedByOid(), item.getActorOid(), item.getCreatedAt(), null,
                    item.getStatus(), item.getStatusChangedAt(), item.getLiftedByAppeal());
        }

        /** Who attached it. */
        public CaseActor attachedBy() {
            return CaseActor.ofRow(this.createdBy, this.recordedByOid, this.actorOid);
        }
    }

    private static @Nullable Long number(@Nullable Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static @Nullable UUID uuid(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
