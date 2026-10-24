package net.onelitefeather.blackhole.backend.appeal.service;

import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.appeal.AppealReviewResult;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.appeal.DecisionOutcome;
import net.onelitefeather.blackhole.backend.appeal.dto.AppealReviewDTO;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import net.onelitefeather.blackhole.backend.support.RecordingEvidenceGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppealDecisionServiceTest {

    private static final UUID REVIEWER = UUID.nameUUIDFromBytes("reviewer".getBytes());
    private static final Actor REVIEWER_ACTOR = new Actor(REVIEWER, "Reviewer", ActorKind.HUMAN, false);

    private final PunishmentTestBed bed = new PunishmentTestBed();
    private final InMemoryRepository<AppealRepository, AppealEntity, UUID> appeals =
            InMemoryRepository.<AppealRepository, AppealEntity, UUID>of(AppealRepository.class, AppealEntity::getIdentifier).build();
    private final AppealDecisionService service = new AppealDecisionService(
            appeals.repository(), bed.punishments.repository(), bed.profiles.repository(), bed.slots, bed.profileCache,
            bed.elo, bed.eloBed.sections, bed.eloBed.events, bed.evidence, bed.staffLinks.service, bed.cases.timeline, bed.banIds.service, bed.eloBed.clock);

    /** A staff mute that cost 80 ELO; the chat score is 920 afterwards. */
    private PunishmentEntity givenStaffMute() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 80));
        PunishmentDTO.ApplyResponse applied = (PunishmentDTO.ApplyResponse) bed.punishmentService.apply(
                new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR);
        return bed.punishments.all().stream().filter(p -> p.getIdentifier().equals(applied.punishment().identifier())).findFirst().orElseThrow();
    }

    /** A permanent network ban from escalation; the network score is 200 afterwards. */
    private PunishmentEntity givenPermanentBan() {
        bed.elo.applyDelta(OWNER, "network", -800, EloReasonCode.MANUAL_ADJUSTMENT, null, Map.of());
        return bed.profiles.repository().findById(OWNER).orElseThrow().getActive().get("network");
    }

    /** Stores an appeal awaiting review and returns its identifier. */
    private UUID givenAppeal(PunishmentEntity punishment) {
        AppealEntity appeal = new AppealEntity(punishment, OWNER, "please", AppealStatus.ELIGIBLE_PENDING_REVIEW,
                new HashMap<>(Map.of("severityTier", "STANDARD")), 0L, 0L, new HashMap<>());
        UUID id = UUID.randomUUID();
        appeals.given(appeal, id);
        return id;
    }

    private DecisionOutcome decide(PunishmentEntity punishment, AppealStatus decision, Long newExpiration) {
        return service.applyDecision(punishment, OWNER, decision, newExpiration, REVIEWER);
    }

    @Test
    void liftingATemporaryStaffPunishmentGivesBackItsEloCost() {
        PunishmentEntity mute = givenStaffMute();
        assertEquals(920, bed.eloBed.storedScore(OWNER, "chat"), "precondition: the mute cost 80");

        DecisionOutcome outcome = decide(mute, AppealStatus.GRANTED_FULL_LIFT, null);

        assertEquals(DecisionOutcome.APPLIED, outcome);
        assertEquals(1000, bed.eloBed.storedScore(OWNER, "chat"), "the recorded cost of 80 is added back");
        assertTrue(bed.eloBed.eventRepository.all().stream().anyMatch(e -> e.getReasonCode() == EloReasonCode.APPEAL_REFUND && e.getDelta() == 80),
                "the refund is an APPEAL_REFUND event");
    }

    @Test
    void liftingATemporaryPunishmentClearsTheSlotMarksItLiftedAndRecordsTheReviewer() {
        PunishmentEntity mute = givenStaffMute();

        decide(mute, AppealStatus.GRANTED_FULL_LIFT, null);

        var profile = bed.profiles.repository().findById(OWNER).orElseThrow();
        assertFalse(profile.getActive().containsKey("chat"), "slot cleared");
        assertTrue(profile.getHistory().contains(mute), "moved to history");
        assertTrue(mute.isLifted(), "lifted punishments no longer count for escalation");
        assertEquals(REVIEWER.toString(), mute.getMetaData().get("revokedBy"));
    }

    @Test
    void liftingAFreePunishmentRefundsNothing() {
        bed.givenTemplate("warn", "chat", null, step("PT1H", 0));
        bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "warn", null, null), ACTOR);
        PunishmentEntity warn = bed.punishments.all().getFirst();

        decide(warn, AppealStatus.GRANTED_FULL_LIFT, null);

        assertTrue(bed.eloBed.eventRepository.all().isEmpty(), "a cost of 0 has nothing to give back");
    }

    @Test
    void liftingAPermanentPunishmentSetsTheScoreToThePardonScore() {
        PunishmentEntity ban = givenPermanentBan();

        decide(ban, AppealStatus.GRANTED_FULL_LIFT, null);

        assertEquals(700, bed.eloBed.storedScore(OWNER, "network"), "pardon score of the network section");
        assertTrue(bed.eloBed.eventRepository.all().stream().anyMatch(e -> e.getReasonCode() == EloReasonCode.PARDON_RESET),
                "the reset is an audited PARDON_RESET");
    }

    @Test
    void reducingAPermanentPunishmentMakesItTemporaryAndSetsTheScoreToThePardonScore() {
        PunishmentEntity ban = givenPermanentBan();
        long newExpiration = bed.eloBed.clock.millis() + Duration.ofDays(7).toMillis();

        DecisionOutcome outcome = decide(ban, AppealStatus.GRANTED_DURATION_REDUCTION, newExpiration);

        assertEquals(DecisionOutcome.APPLIED, outcome);
        assertEquals(700, bed.eloBed.storedScore(OWNER, "network"));
        assertFalse(ban.isPermanent(), "a permanent punishment with an expiry is temporary");
        assertEquals(newExpiration, ban.getMetaData().get("expiration_date"));
        assertTrue(bed.profiles.repository().findById(OWNER).orElseThrow().getActive().containsKey("network"), "still active until it expires");
    }

    @Test
    void reducingATemporaryPunishmentLeavesTheScoreAlone() {
        PunishmentEntity mute = givenStaffMute();

        decide(mute, AppealStatus.GRANTED_DURATION_REDUCTION, bed.eloBed.clock.millis() + 1000);

        assertEquals(920, bed.eloBed.storedScore(OWNER, "chat"), "only permanent punishments pardon");
    }

    @Test
    void aDeniedAppealChangesNeitherTheScoreNorThePunishment() {
        PunishmentEntity mute = givenStaffMute();

        DecisionOutcome outcome = decide(mute, AppealStatus.DENIED, null);

        assertEquals(DecisionOutcome.APPLIED, outcome);
        assertEquals(920, bed.eloBed.storedScore(OWNER, "chat"));
        assertTrue(bed.profiles.repository().findById(OWNER).orElseThrow().getActive().containsKey("chat"), "the mute stays");
        assertFalse(mute.isLifted());
    }

    @Test
    void aPunishmentThatIsNoLongerActiveCannotBeGranted() {
        PunishmentEntity mute = givenStaffMute();
        bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "chat", null), ACTOR);

        assertEquals(DecisionOutcome.PUNISHMENT_NOT_ACTIVE, decide(mute, AppealStatus.GRANTED_FULL_LIFT, null));
    }

    @Test
    void aGrantedFullLiftIsAnnouncedWithItsSectionAndEnforcement() {
        PunishmentEntity mute = givenStaffMute();
        UUID appealId = givenAppeal(mute);

        AppealReviewResult result = service.reviewAppeal(appealId, AppealReviewDTO.withoutMessage(AppealStatus.GRANTED_FULL_LIFT, "ok", null), REVIEWER_ACTOR);

        assertEquals(AppealReviewResult.Kind.DECIDED, result.kind(), result.message());
        var event = bed.eloBed.events.eventsOfType("appeal.resolved").getFirst();
        assertEquals("chat", event.payload().get("section"));
        assertEquals("MUTE", event.payload().get("enforcement"));
        assertEquals("GRANTED_FULL_LIFT", event.payload().get("decision"));
    }

    @Test
    void aGrantedReductionReannouncesThePunishmentWithItsNewExpiry() {
        PunishmentEntity ban = givenPermanentBan();
        UUID appealId = givenAppeal(ban);
        long newExpiration = bed.eloBed.clock.millis() + Duration.ofDays(7).toMillis();

        AppealReviewResult result = service.reviewAppeal(appealId,
                AppealReviewDTO.withoutMessage(AppealStatus.GRANTED_DURATION_REDUCTION, "shorter", newExpiration), REVIEWER_ACTOR);

        assertEquals(AppealReviewResult.Kind.DECIDED, result.kind(), result.message());
        var updated = bed.eloBed.events.eventsOfType("punishment.updated").getFirst();
        assertEquals(newExpiration, updated.payload().get("expiresAt"), "the proxy mirror's TTL follows the reduction");
        assertEquals(false, updated.payload().get("permanent"));
    }

    @Test
    void aDeniedReviewLeavesTheScoreUnchanged() {
        PunishmentEntity mute = givenStaffMute();
        UUID appealId = givenAppeal(mute);

        AppealReviewResult result = service.reviewAppeal(appealId, AppealReviewDTO.withoutMessage(AppealStatus.DENIED, "no", null), REVIEWER_ACTOR);

        assertEquals(AppealReviewResult.Kind.DECIDED, result.kind());
        assertEquals(920, bed.eloBed.storedScore(OWNER, "chat"));
    }

    @Test
    void aReductionToALaterExpiryThanTheCurrentOneIsRejected() {
        PunishmentEntity mute = givenStaffMute();
        UUID appealId = givenAppeal(mute);
        long current = ((Number) mute.getMetaData().get("expiration_date")).longValue();

        AppealReviewResult result = service.reviewAppeal(appealId,
                AppealReviewDTO.withoutMessage(AppealStatus.GRANTED_DURATION_REDUCTION, "longer?", current + 1000), REVIEWER_ACTOR);

        assertEquals(AppealReviewResult.Kind.DURATION_REDUCTION_NOT_SHORTER, result.kind(), "extending is not a reduction");
        assertEquals(current, ((Number) mute.getMetaData().get("expiration_date")).longValue(), "the expiry is unchanged");
        assertEquals(AppealStatus.ELIGIBLE_PENDING_REVIEW, appeals.repository().findById(appealId).orElseThrow().getStatus());
    }

    @Test
    void aReductionToTheSameExpiryIsRejected() {
        PunishmentEntity mute = givenStaffMute();
        UUID appealId = givenAppeal(mute);
        long current = ((Number) mute.getMetaData().get("expiration_date")).longValue();

        AppealReviewResult result = service.reviewAppeal(appealId,
                AppealReviewDTO.withoutMessage(AppealStatus.GRANTED_DURATION_REDUCTION, "same", current), REVIEWER_ACTOR);

        assertEquals(AppealReviewResult.Kind.DURATION_REDUCTION_NOT_SHORTER, result.kind(), "equal is not strictly before");
    }

    @Test
    void aReductionToAnEarlierFutureExpiryIsAccepted() {
        PunishmentEntity mute = givenStaffMute();
        UUID appealId = givenAppeal(mute);
        long earlier = bed.eloBed.clock.millis() + 1000;

        AppealReviewResult result = service.reviewAppeal(appealId,
                AppealReviewDTO.withoutMessage(AppealStatus.GRANTED_DURATION_REDUCTION, "shorter", earlier), REVIEWER_ACTOR);

        assertEquals(AppealReviewResult.Kind.DECIDED, result.kind(), result.message());
        assertEquals(earlier, ((Number) mute.getMetaData().get("expiration_date")).longValue());
    }

    @Test
    void aGrantedFullLiftMarksTheEvidenceLiftedByTheAppeal() {
        PunishmentEntity mute = givenStaffMute();
        UUID appealId = givenAppeal(mute);

        service.reviewAppeal(appealId, AppealReviewDTO.withoutMessage(AppealStatus.GRANTED_FULL_LIFT, "ok", null), REVIEWER_ACTOR);

        assertEquals(java.util.List.of(new RecordingEvidenceGateway.Lift(mute.getIdentifier(), appealId)), bed.evidence.lifted());
    }

    @Test
    void aDeniedAppealLeavesTheEvidenceAlone() {
        PunishmentEntity mute = givenStaffMute();

        service.reviewAppeal(givenAppeal(mute), AppealReviewDTO.withoutMessage(AppealStatus.DENIED, "no", null), REVIEWER_ACTOR);

        assertTrue(bed.evidence.lifted().isEmpty(), "a denial lifts nothing");
        assertTrue(bed.evidence.revoked().isEmpty(), "a denial revokes nothing");
    }

    @Test
    void aDurationReductionLeavesTheEvidenceAlone() {
        PunishmentEntity mute = givenStaffMute();

        service.reviewAppeal(givenAppeal(mute),
                AppealReviewDTO.withoutMessage(AppealStatus.GRANTED_DURATION_REDUCTION, "shorter", bed.eloBed.clock.millis() + 1000), REVIEWER_ACTOR);

        assertTrue(bed.evidence.lifted().isEmpty(), "the punishment still stands, so its evidence stays valid");
    }

    @Test
    void aFailingEvidenceMarkDoesNotFailTheDecision() {
        PunishmentEntity mute = givenStaffMute();
        bed.evidence.failClaims(new IllegalStateException("database down"));

        AppealReviewResult result = service.reviewAppeal(givenAppeal(mute),
                AppealReviewDTO.withoutMessage(AppealStatus.GRANTED_FULL_LIFT, "ok", null), REVIEWER_ACTOR);

        assertEquals(AppealReviewResult.Kind.DECIDED, result.kind(), "the appeal is decided regardless");
    }

    private AppealReviewDTO fullLift() {
        return AppealReviewDTO.withoutMessage(AppealStatus.GRANTED_FULL_LIFT, "ok", null);
    }

    @Test
    void theReviewerWhoseOidIsThePunishmentsSourceCannotReviewItAndTheAppealStaysUndecided() {
        PunishmentEntity mute = givenStaffMute();
        UUID appealId = givenAppeal(mute);

        AppealReviewResult result = service.reviewAppeal(appealId, fullLift(), ACTOR);

        assertEquals(AppealReviewResult.Kind.SELF_REVIEW, result.kind(), "the issuer reviews their own punishment");
        assertEquals(AppealStatus.ELIGIBLE_PENDING_REVIEW, appeals.repository().findById(appealId).orElseThrow().getStatus(), "undecided");
    }

    @Test
    void theReviewerWhoseOidRecordedThePunishmentCannotReviewItEvenWhenTheSourceIsAnInGameUuid() {
        PunishmentEntity ban = givenStaffMute();
        ban.setRecordedByOid(REVIEWER);

        AppealReviewResult result = service.reviewAppeal(givenAppeal(ban), fullLift(), REVIEWER_ACTOR);

        assertEquals(AppealReviewResult.Kind.SELF_REVIEW, result.kind(), "recorded_by_oid counts as well as the source");
    }

    @Test
    void aDifferentModeratorDecidesAndTheDecisionRecordsTheirOid() {
        PunishmentEntity mute = givenStaffMute();
        UUID appealId = givenAppeal(mute);

        AppealReviewResult result = service.reviewAppeal(appealId, fullLift(), REVIEWER_ACTOR);

        assertEquals(AppealReviewResult.Kind.DECIDED, result.kind(), result.message());
        AppealEntity decided = appeals.repository().findById(appealId).orElseThrow();
        assertEquals(REVIEWER, decided.getDecidedBy(), "decidedBy is the reviewer's oid");
        assertEquals(REVIEWER, decided.getRecordedByOid(), "recorded by the reviewer's oid");
        assertTrue(decided.toDTO().verified(), "a decision made with a token is verified");
    }

    @Test
    void aPunishmentFromBeforeSsoCanBeReviewedByAnyoneButItsIssuer() {
        PunishmentEntity legacy = givenStaffMute();
        legacy.setRecordedByOid(null);

        AppealReviewResult result = service.reviewAppeal(givenAppeal(legacy), fullLift(), REVIEWER_ACTOR);

        assertEquals(AppealReviewResult.Kind.DECIDED, result.kind(), "no recorded_by_oid, the source differs: allowed");
    }

    @Test
    void anUndecidedAppealIsReportedAsUnverified() {
        UUID appealId = givenAppeal(givenStaffMute());

        assertFalse(appeals.repository().findById(appealId).orElseThrow().toDTO().verified());
    }

    private static final UUID INGAME = UUID.nameUUIDFromBytes("ingame-staff".getBytes());
    private static final Actor PROXY_ACTOR = new Actor(UUID.nameUUIDFromBytes("proxy".getBytes()), null, ActorKind.SERVICE, true);

    /** A mute the proxy applied on behalf of {@link #INGAME}; linked to {@link #REVIEWER} when {@code linkedBefore}. */
    private PunishmentEntity givenInGameMute(boolean linkedBefore) {
        if (linkedBefore) {
            bed.staffLinks.givenLink(REVIEWER, INGAME);
        }
        bed.givenTemplate("spam", "chat", null, step("PT1H", 80));
        PunishmentDTO.ApplyResponse applied = (PunishmentDTO.ApplyResponse) bed.punishmentService.apply(
                new PunishmentDTO.ApplyRequest(OWNER, "spam", null, INGAME), PROXY_ACTOR);
        return bed.punishments.all().stream().filter(p -> p.getIdentifier().equals(applied.punishment().identifier())).findFirst().orElseThrow();
    }

    @Test
    void aStaffMemberCannotDecideTheAppealOfTheirOwnLinkedInGameBan() {
        PunishmentEntity mute = givenInGameMute(true);
        UUID appealId = givenAppeal(mute);

        AppealReviewResult result = service.reviewAppeal(appealId, AppealReviewDTO.withoutMessage(AppealStatus.DENIED, "no", null), REVIEWER_ACTOR);

        assertEquals(AppealReviewResult.Kind.SELF_REVIEW, result.kind(), "the stored actor_oid is the reviewer");
    }

    @Test
    void aStaffMemberWhoLinkedAfterBanningInGameStillCannotDecideThatAppeal() {
        PunishmentEntity mute = givenInGameMute(false);
        assertEquals(null, mute.getActorOid(), "precondition: no link existed when the ban was written");
        bed.staffLinks.givenLink(REVIEWER, INGAME);
        UUID appealId = givenAppeal(mute);

        AppealReviewResult result = service.reviewAppeal(appealId, AppealReviewDTO.withoutMessage(AppealStatus.DENIED, "no", null), REVIEWER_ACTOR);

        assertEquals(AppealReviewResult.Kind.SELF_REVIEW, result.kind(), "the source is currently linked to the reviewer");
    }

    @Test
    void anotherModeratorCanDecideTheAppealOfAnInGameBan() {
        PunishmentEntity mute = givenInGameMute(true);
        UUID appealId = givenAppeal(mute);
        Actor other = new Actor(UUID.nameUUIDFromBytes("other".getBytes()), "Other", ActorKind.HUMAN, false);

        AppealReviewResult result = service.reviewAppeal(appealId, AppealReviewDTO.withoutMessage(AppealStatus.DENIED, "no", null), other);

        assertEquals(AppealReviewResult.Kind.DECIDED, result.kind(), result.message());
        assertEquals(other.oid(), result.appeal().getDecidedBy(), "the decision is recorded for that moderator");
    }
}
