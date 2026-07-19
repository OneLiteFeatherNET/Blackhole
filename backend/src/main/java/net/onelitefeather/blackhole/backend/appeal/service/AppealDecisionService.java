package net.onelitefeather.blackhole.backend.appeal.service;

import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.appeal.AppealReviewResult;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.appeal.DecisionOutcome;
import net.onelitefeather.blackhole.backend.appeal.dto.AppealReviewDTO;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.profile.CacheInvalidationPublisher;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.phoca.metadata.Expirable;
import net.onelitefeather.phoca.metadata.Metadata;

import java.util.Set;
import java.util.UUID;

/**
 * The mechanics of actually granting an appeal - lifting a punishment early or shortening it -
 * plus everything the review endpoint needs to decide whether a decision is even allowed
 * ({@link #reviewAppeal}). {@code PunishmentApplicationService} only knows how to apply new
 * punishments; reversing one that's already active is a distinct operation this service owns
 * instead.
 *
 * <p><b>Known limitation (deferred, not fixed here):</b> {@link #reviewAppeal}'s
 * read-modify-write of the punishment/profile and its subsequent {@code appealRepository.update}
 * marking the appeal decided are not atomic - {@code @Transactional} is unusable in this
 * codebase (see {@code EloService}'s class Javadoc for why). A crash between the two would leave
 * the punishment revoked/reduced but the appeal still {@code ELIGIBLE_PENDING_REVIEW}, or vice
 * versa. This is an accepted race window in the same spirit as the one already documented for
 * {@code EloService}, not newly introduced here.</p>
 */
@Singleton
public class AppealDecisionService {

    private static final Set<AppealStatus> VALID_DECISIONS = Set.of(
            AppealStatus.GRANTED_FULL_LIFT, AppealStatus.GRANTED_DURATION_REDUCTION, AppealStatus.DENIED
    );

    private final AppealRepository appealRepository;
    private final PunishmentRepository punishmentRepository;
    private final PunishmentProfileRepository profileRepository;
    private final CacheInvalidationPublisher cacheInvalidationPublisher;

    public AppealDecisionService(
            AppealRepository appealRepository,
            PunishmentRepository punishmentRepository,
            PunishmentProfileRepository profileRepository,
            CacheInvalidationPublisher cacheInvalidationPublisher
    ) {
        this.appealRepository = appealRepository;
        this.punishmentRepository = punishmentRepository;
        this.profileRepository = profileRepository;
        this.cacheInvalidationPublisher = cacheInvalidationPublisher;
    }

    /**
     * Decides an eligible appeal end to end: looks it up, applies the status guard,
     * decision-validity check, self-review rejection, SEVERE-tier full-lift ban, and
     * duration-reduction expiry validation, then - if everything passes - applies the decision
     * and persists the decided appeal. The single entry point {@code AppealController.review}
     * calls, so the controller never branches on business rules itself.
     */
    public AppealReviewResult reviewAppeal(UUID appealIdentifier, AppealReviewDTO review) {
        AppealEntity appeal = this.appealRepository.findById(appealIdentifier).orElse(null);
        if (appeal == null) {
            return AppealReviewResult.notFound();
        }
        if (appeal.getStatus() != AppealStatus.ELIGIBLE_PENDING_REVIEW && appeal.getStatus() != AppealStatus.IN_REVIEW) {
            return AppealReviewResult.notAwaitingReview();
        }
        if (!VALID_DECISIONS.contains(review.decision())) {
            return AppealReviewResult.invalidDecision("decision must be one of " + VALID_DECISIONS);
        }

        PunishmentEntity punishment = appeal.getPunishment();
        if (review.reviewerId().equals(punishment.getSource())) {
            return AppealReviewResult.selfReview();
        }

        boolean severe = "SEVERE".equals(appeal.getEligibilityCheckResult().get("severityTier"));
        if (severe && review.decision() == AppealStatus.GRANTED_FULL_LIFT) {
            return AppealReviewResult.severeFullLiftDisallowed();
        }
        if (review.decision() == AppealStatus.GRANTED_DURATION_REDUCTION) {
            if (review.newExpirationAt() == null) {
                return AppealReviewResult.durationReductionMissingExpiry();
            }
            if (review.newExpirationAt() <= System.currentTimeMillis()) {
                return AppealReviewResult.durationReductionExpiryNotFuture();
            }
        }

        DecisionOutcome outcome = applyDecision(
                punishment, appeal.getAppellantHash(), review.decision(), review.newExpirationAt(), review.reviewerId()
        );
        if (outcome == DecisionOutcome.PUNISHMENT_NOT_ACTIVE) {
            return AppealReviewResult.punishmentNotActive();
        }

        appeal.setStatus(review.decision());
        appeal.setDecidedBy(review.reviewerId());
        appeal.setDecisionNote(review.decisionNote());
        appeal.setUpdatedAt(System.currentTimeMillis());
        AppealEntity saved = this.appealRepository.update(appeal);

        return AppealReviewResult.decided(saved);
    }

    /**
     * @param punishment      the punishment being appealed
     * @param owner           the appellant's hashed owner (also the punishment's owner)
     * @param decision        {@code GRANTED_FULL_LIFT}, {@code GRANTED_DURATION_REDUCTION}, or {@code DENIED}
     * @param newExpirationAt required for {@code GRANTED_DURATION_REDUCTION}, ignored otherwise
     * @param revokedBy       the reviewer granting the decision; stamped onto the punishment's own
     *                        metadata for {@code GRANTED_FULL_LIFT}, matching
     *                        {@code PunishmentApplicationService.revoke()}'s audit-trail pattern
     * @return whether the decision was applied, or {@code PUNISHMENT_NOT_ACTIVE} if the
     * punishment already expired/was lifted naturally in the meantime - nothing left to grant
     */
    public DecisionOutcome applyDecision(
            PunishmentEntity punishment, String owner, AppealStatus decision, Long newExpirationAt, UUID revokedBy
    ) {
        if (decision == AppealStatus.DENIED) {
            return DecisionOutcome.APPLIED;
        }

        PunishmentProfileEntity profile = this.profileRepository.findById(owner).orElse(null);
        if (profile == null) {
            return DecisionOutcome.PUNISHMENT_NOT_ACTIVE;
        }

        boolean isActiveBan = profile.getActiveBan() != null && profile.getActiveBan().getIdentifier().equals(punishment.getIdentifier());
        boolean isActiveChatBan = !isActiveBan && profile.getActiveChatBan() != null
                && profile.getActiveChatBan().getIdentifier().equals(punishment.getIdentifier());
        if (!isActiveBan && !isActiveChatBan) {
            return DecisionOutcome.PUNISHMENT_NOT_ACTIVE;
        }

        if (decision == AppealStatus.GRANTED_FULL_LIFT) {
            PunishmentEntity active = isActiveBan ? profile.getActiveBan() : profile.getActiveChatBan();
            active.getMetaData().put("revokedBy", revokedBy.toString());
            active.getMetaData().put(Metadata.META_DATA_KEY_UPDATE_DATE, System.currentTimeMillis());
            this.punishmentRepository.update(active);

            profile.getHistory().add(active);
            if (isActiveBan) {
                profile.setActiveBan(null);
            } else {
                profile.setActiveChatBan(null);
            }
            this.profileRepository.update(profile);
        } else if (decision == AppealStatus.GRANTED_DURATION_REDUCTION) {
            punishment.getMetaData().put(Expirable.META_DATA_KEY_EXPIRATION_DATE, newExpirationAt);
            punishment.getMetaData().put(Metadata.META_DATA_KEY_UPDATE_DATE, System.currentTimeMillis());
            this.punishmentRepository.update(punishment);
        }

        this.cacheInvalidationPublisher.invalidate(owner);
        return DecisionOutcome.APPLIED;
    }
}
