package net.onelitefeather.blackhole.backend.appeal.service;

import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Attribution;
import net.onelitefeather.blackhole.backend.security.StaffLinkService;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.appeal.AppealReviewResult;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.appeal.DecisionOutcome;
import net.onelitefeather.blackhole.backend.appeal.dto.AppealReviewDTO;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.CaseTimeline;
import net.onelitefeather.blackhole.backend.casetimeline.MessageRules;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.AppealProtocolGenerator;
import net.onelitefeather.blackhole.backend.elo.EloChange;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.elo.EloScoring;
import net.onelitefeather.blackhole.backend.elo.service.EloService;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.evidence.service.PunishmentEvidenceGateway;
import net.onelitefeather.blackhole.backend.profile.ProfileCache;
import net.onelitefeather.blackhole.backend.profile.ProfileSlots;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEvents;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.section.SectionService;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import net.onelitefeather.phoca.metadata.Expirable;
import net.onelitefeather.blackhole.backend.staffprofile.StaffProfileService;
import net.onelitefeather.phoca.metadata.Metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(AppealDecisionService.class);

    private static final Set<AppealStatus> VALID_DECISIONS = Set.of(
            AppealStatus.GRANTED_FULL_LIFT, AppealStatus.GRANTED_DURATION_REDUCTION, AppealStatus.DENIED
    );

    private final AppealRepository appealRepository;
    private final PunishmentRepository punishmentRepository;
    private final PunishmentProfileRepository profileRepository;
    private final ProfileSlots slots;
    private final ProfileCache profileCache;
    private final EloScoring eloScoring;
    private final SectionService sectionService;
    private final DomainEventPublisher eventPublisher;
    private final PunishmentEvidenceGateway evidence;
    private final StaffLinkService staffLinks;
    private final CaseTimeline caseTimeline;
    private final BanIdService banIds;
    private final AppealProtocolGenerator protocols;
    private final StaffProfileService staffProfiles;
    private final Clock clock;

    public AppealDecisionService(
            AppealRepository appealRepository,
            PunishmentRepository punishmentRepository,
            PunishmentProfileRepository profileRepository,
            ProfileSlots slots,
            ProfileCache profileCache,
            EloScoring eloScoring,
            SectionService sectionService,
            DomainEventPublisher eventPublisher,
            PunishmentEvidenceGateway evidence,
            StaffLinkService staffLinks,
            CaseTimeline caseTimeline,
            BanIdService banIds,
            AppealProtocolGenerator protocols,
            StaffProfileService staffProfiles,
            Clock clock
    ) {
        this.staffProfiles = staffProfiles;
        this.appealRepository = appealRepository;
        this.punishmentRepository = punishmentRepository;
        this.profileRepository = profileRepository;
        this.slots = slots;
        this.profileCache = profileCache;
        this.eloScoring = eloScoring;
        this.sectionService = sectionService;
        this.eventPublisher = eventPublisher;
        this.evidence = evidence;
        this.staffLinks = staffLinks;
        this.caseTimeline = caseTimeline;
        this.banIds = banIds;
        this.protocols = protocols;
        this.clock = clock;
    }

    /**
     * Decides an eligible appeal end to end: looks it up, applies the status guard,
     * decision-validity check, self-review rejection, SEVERE-tier full-lift ban, and
     * duration-reduction expiry validation, then - if everything passes - applies the decision
     * and persists the decided appeal. The single entry point {@code AppealController.review}
     * calls, so the controller never branches on business rules itself.
     */
    public AppealReviewResult reviewAppeal(UUID appealIdentifier, AppealReviewDTO review, Actor reviewer) {
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

        if (review.playerMessage() != null) {
            String problem = MessageRules.check(review.playerMessage(), this.banIds);
            if (problem != null) {
                return AppealReviewResult.invalidPlayerMessage(problem);
            }
            // A message to the player is written under the reviewer's name: without one, nothing is decided.
            if (this.staffProfiles.namedDisplayName(reviewer.oid()).isEmpty()) {
                return AppealReviewResult.staffProfileRequired();
            }
        }

        PunishmentEntity punishment = appeal.getPunishment();
        // Compared by the reviewer's verified oid: the punishment's attributed source, or the principal that recorded it.
        // ... or the staff member behind an in-game ban: the stored actor, or whoever the source is linked to now.
        if (reviewer.oid().equals(punishment.getSource()) || reviewer.oid().equals(punishment.getRecordedByOid())
                || reviewer.oid().equals(punishment.getActorOid())
                || this.staffLinks.linkedOid(punishment.getSource()).filter(reviewer.oid()::equals).isPresent()) {
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
            if (review.newExpirationAt() <= this.clock.millis()) {
                return AppealReviewResult.durationReductionExpiryNotFuture();
            }
            // A punishment without an expiry is permanent: any future expiry shortens it.
            if (punishment.getMetaData().get(Expirable.META_DATA_KEY_EXPIRATION_DATE) instanceof Number current
                    && review.newExpirationAt() >= current.longValue()) {
                return AppealReviewResult.durationReductionNotShorter();
            }
        }

        DecisionOutcome outcome = applyDecision(
                punishment, appeal.getAppellantHash(), review.decision(), review.newExpirationAt(), reviewer.oid()
        );
        if (outcome == DecisionOutcome.PUNISHMENT_NOT_ACTIVE) {
            return AppealReviewResult.punishmentNotActive();
        }
        if (review.decision() == AppealStatus.GRANTED_FULL_LIFT) {
            markEvidenceLifted(punishment, appealIdentifier, reviewer.attribution());
        }

        appeal.setStatus(review.decision());
        appeal.setDecidedBy(reviewer.oid());
        appeal.setRecordedByOid(reviewer.oid());
        appeal.setDecisionNote(review.decisionNote());
        appeal.setUpdatedAt(this.clock.millis());
        AppealEntity saved = this.appealRepository.update(appeal);
        recordDecision(saved, punishment, review, reviewer);
        publishResolved(appealIdentifier, saved, punishment, review);

        return AppealReviewResult.decided(saved);
    }

    /**
     * @param punishment      the punishment being appealed
     * @param owner           the appellant's hashed owner (also the punishment's owner)
     * @param decision        {@code GRANTED_FULL_LIFT}, {@code GRANTED_DURATION_REDUCTION}, or {@code DENIED}
     * @param newExpirationAt required for {@code GRANTED_DURATION_REDUCTION}, ignored otherwise
     * @param revokedBy       the reviewer's verified {@code oid}, also its {@code recordedByOid}; stamped onto the punishment's own
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

        PunishmentEntity active = profile.getActive().get(punishment.getSectionKey());
        if (active == null || !active.getIdentifier().equals(punishment.getIdentifier())) {
            return DecisionOutcome.PUNISHMENT_NOT_ACTIVE;
        }

        long now = this.clock.millis();
        Map<String, Object> eloMetaData = Map.of(
                "punishmentIdentifier", active.getIdentifier(), "revokedBy", revokedBy.toString(), "recordedByOid", revokedBy.toString());
        if (decision == AppealStatus.GRANTED_FULL_LIFT) {
            active.getMetaData().put("revokedBy", revokedBy.toString());
            active.getMetaData().put("recordedByOid", revokedBy.toString());
            active.getMetaData().put(Metadata.META_DATA_KEY_UPDATE_DATE, now);
            active.setLifted(true);
            this.punishmentRepository.update(active);

            this.slots.clearIf(owner, punishment.getSectionKey(), active.getIdentifier());

            restoreElo(owner, active, eloMetaData);
        } else if (decision == AppealStatus.GRANTED_DURATION_REDUCTION) {
            boolean wasPermanent = active.isPermanent();
            active.getMetaData().put(Expirable.META_DATA_KEY_EXPIRATION_DATE, newExpirationAt);
            active.getMetaData().put(Metadata.META_DATA_KEY_UPDATE_DATE, now);
            active.setPermanent(false);
            this.punishmentRepository.update(active);
            if (wasPermanent) {
                this.eloScoring.resetToPardon(owner, active.getSectionKey(), eloMetaData);
            }
        }

        this.profileCache.evict(owner);
        return DecisionOutcome.APPLIED;
    }

    /** Records the decision, and the lift of a granted full-lift appeal, in the punishment's case. */
    private void recordDecision(AppealEntity decided, PunishmentEntity punishment, AppealReviewDTO review, Actor reviewer) {
        this.caseTimeline.appealDecided(decided, review.newExpirationAt());
        if (review.playerMessage() != null) {
            // The reviewer's name was checked before the decision was written; the message keeps it as a snapshot.
            String displayName = this.staffProfiles.namedDisplayName(reviewer.oid()).orElse(null);
            this.caseTimeline.decisionMessage(decided, review.playerMessage(), CaseActor.of(reviewer.attribution()), displayName);
        }
        if (review.decision() == AppealStatus.GRANTED_FULL_LIFT) {
            this.caseTimeline.lifted(punishment, decided.getIdentifier(), decided.getRecordedByOid());
        }
        storeProtocol(decided);
    }

    /**
     * Stores the protocol of the decided appeal: the conversation closes with the decision, so it is forced
     * past the settle delay. A failure is logged and never fails the decision; the reconciler stores the
     * protocol later. An appeal with a Discord ticket gets none here until the ticket's transcript is
     * verified complete.
     */
    private void storeProtocol(AppealEntity decided) {
        try {
            this.protocols.generate(decided.getIdentifier(), true);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not store the protocol of decided appeal {}; the reconciler will", decided.getIdentifier(), e);
        }
    }

    /**
     * Marks the evidence of a fully lifted punishment {@code LIFTED}. The punishment is already
     * lifted, so a failure here is logged and never fails the decision.
     */
    private void markEvidenceLifted(PunishmentEntity punishment, UUID appealIdentifier, Attribution by) {
        try {
            this.evidence.markLifted(punishment.getIdentifier(), appealIdentifier, by);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not mark the evidence of lifted punishment {}", punishment.getIdentifier(), e);
        }
    }

    /**
     * A lifted permanent punishment sets the section score to the pardon score; a lifted staff
     * temporary punishment gives back the ELO cost recorded on it. Free punishments (the ELO
     * system's own soft ones) have nothing to give back.
     */
    private void restoreElo(String owner, PunishmentEntity lifted, Map<String, Object> eloMetaData) {
        if (lifted.isPermanent()) {
            this.eloScoring.resetToPardon(owner, lifted.getSectionKey(), eloMetaData);
            return;
        }
        Integer cost = lifted.getEloCost();
        if (cost != null && cost > 0 && !EloService.SYSTEM_ELO_SOURCE.equals(lifted.getSource())) {
            Map<String, Object> metaData = new HashMap<>(eloMetaData);
            metaData.put("refundedCost", cost);
            EloChange change = this.eloScoring.applyDelta(owner, lifted.getSectionKey(), cost, EloReasonCode.APPEAL_REFUND, null, metaData);
            if (change instanceof EloChange.Rejected rejected) {
                LOGGER.warn("Could not refund {} ELO of lifted punishment {}: {}", cost, lifted.getIdentifier(), rejected.message());
            }
        }
    }

    /**
     * Announces a decided appeal. A full lift clears the proxies' mirror; a duration reduction
     * re-announces the punishment with its new expiry so the mirror's TTL follows.
     */
    private void publishResolved(UUID appealIdentifier, AppealEntity appeal, PunishmentEntity punishment, AppealReviewDTO review) {
        SectionDTO.Response section = this.sectionService.find(punishment.getSectionKey()).orElse(null);
        Map<String, Object> payload = new HashMap<>();
        payload.put("appealIdentifier", appealIdentifier.toString());
        payload.put("owner", appeal.getAppellantHash());
        payload.put("punishmentIdentifier", punishment.getIdentifier());
        payload.put("section", punishment.getSectionKey());
        payload.put("decision", review.decision().toString());
        if (section != null) {
            payload.put("enforcement", section.enforcement().name());
        }
        this.eventPublisher.publish("appeal.resolved", payload);
        if (review.decision() == AppealStatus.GRANTED_DURATION_REDUCTION) {
            Map<String, Object> updated = PunishmentEvents.created(punishment, section);
            updated.put("expiresAt", review.newExpirationAt());
            updated.put("permanent", false);
            this.eventPublisher.publish("punishment.updated", updated);
        }
    }
}
