package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.appeal.AppealReviewResult;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.appeal.dto.AppealReviewDTO;
import net.onelitefeather.blackhole.backend.appeal.service.AppealDecisionService;
import net.onelitefeather.blackhole.backend.appeal.service.AppealEligibilityService;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The appeal flows record filing, decision and lift in the case, after their own write. */
class AppealRecordingTest {

    private static final UUID REVIEWER = UUID.nameUUIDFromBytes("reviewer".getBytes());
    private static final Actor REVIEWER_ACTOR = new Actor(REVIEWER, "Reviewer", ActorKind.HUMAN, false);

    private final PunishmentTestBed bed = new PunishmentTestBed();
    private final InMemoryRepository<AppealRepository, AppealEntity, UUID> appeals = InMemoryRepository
            .<AppealRepository, AppealEntity, UUID>of(AppealRepository.class, AppealEntity::getIdentifier)
            .assigningIds(AppealRecordingTest::withIdentifier)
            .finder("findByPunishmentIdentifierAndStatusIn", args -> java.util.List.<AppealEntity>of())
            .build();
    private final AppealEligibilityService filing = new AppealEligibilityService(
            appeals.repository(), bed.punishments.repository(), bed.elo, bed.eloBed.sections, bed.cases.timeline, bed.eloBed.clock, 14, 3, 7);
    private final AppealDecisionService deciding = new AppealDecisionService(
            appeals.repository(), bed.punishments.repository(), bed.profiles.repository(), bed.slots, bed.profileCache,
            bed.elo, bed.eloBed.sections, bed.eloBed.events, bed.evidence, bed.staffLinks.service, bed.cases.timeline, bed.eloBed.clock);

    /** The database assigns the identifier of a saved appeal; the in-memory repository needs a hand. */
    private static AppealEntity withIdentifier(AppealEntity appeal) {
        try {
            Field field = AppealEntity.class.getDeclaredField("identifier");
            field.setAccessible(true);
            field.set(appeal, UUID.randomUUID());
            return appeal;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private PunishmentEntity givenStaffMute() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 80));
        PunishmentDTO.ApplyResponse applied = assertInstanceOf(PunishmentDTO.ApplyResponse.class,
                bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR));
        return bed.punishments.all().stream().filter(p -> p.getIdentifier().equals(applied.punishment().identifier())).findFirst().orElseThrow();
    }

    private AppealEntity givenAppealAwaitingReview(PunishmentEntity punishment) {
        AppealEntity appeal = withIdentifier(new AppealEntity(punishment, OWNER, "please unmute me", AppealStatus.ELIGIBLE_PENDING_REVIEW,
                new HashMap<>(Map.of("severityTier", "STANDARD")), 0L, 0L, new HashMap<>()));
        appeals.given(appeal, appeal.getIdentifier());
        return appeal;
    }

    private AppealReviewResult review(AppealEntity appeal, AppealStatus decision, Long newExpiration) {
        return deciding.reviewAppeal(appeal.getIdentifier(), new AppealReviewDTO(decision, "a note", newExpiration), REVIEWER_ACTOR);
    }

    @Test
    void anEligibleAppealIsRecordedAsFiledAndEligible() {
        PunishmentEntity mute = givenStaffMute();
        bed.eloBed.clock.advance(Duration.ofDays(15));

        AppealEntity appeal = filing.submitAppeal(mute.getIdentifier(), OWNER, "please unmute me").orElseThrow();

        CaseEventRecord event = bed.cases.log.ofType(mute.getIdentifier(), "appeal.filed").getFirst();
        assertEquals(new CaseEventPayload.AppealFiled(appeal.getIdentifier().toString(), true), event.payload());
        assertEquals("appeal.filed:" + appeal.getIdentifier(), event.dedupeKey());
        assertEquals(bed.eloBed.clock.millis(), event.occurredAt(), "from the injected clock");
    }

    @Test
    void anAppealThatIsNotYetEligibleIsRecordedAsFiledAndNotEligible() {
        PunishmentEntity mute = givenStaffMute();

        filing.submitAppeal(mute.getIdentifier(), OWNER, "too early");

        CaseEventPayload.AppealFiled payload = assertInstanceOf(CaseEventPayload.AppealFiled.class,
                bed.cases.log.ofType(mute.getIdentifier(), "appeal.filed").getFirst().payload());
        assertFalse(payload.eligible(), "the minimum time has not elapsed");
    }

    @Test
    void theFiledEventHoldsNoStatementText() {
        PunishmentEntity mute = givenStaffMute();

        filing.submitAppeal(mute.getIdentifier(), OWNER, "my private statement");

        assertFalse(bed.cases.log.all().stream().anyMatch(event -> event.body().toString().contains("private statement")),
                "player text stays in the appeal and is not copied into the log");
    }

    @Test
    void filingAnAppealAsksForAClosedCaseToBeReopened() {
        PunishmentEntity mute = givenStaffMute();

        filing.submitAppeal(mute.getIdentifier(), OWNER, "please");

        assertEquals(java.util.List.of(mute.getIdentifier()), bed.cases.writer.reopened());
    }

    @Test
    void aFailingRecordingDoesNotFailTheFiling() {
        PunishmentEntity mute = givenStaffMute();
        bed.cases.log.failAppends(new IllegalStateException("database down"));
        bed.cases.writer.fail(new IllegalStateException("database down"));

        AppealEntity appeal = filing.submitAppeal(mute.getIdentifier(), OWNER, "please").orElseThrow();

        assertTrue(appeals.all().contains(appeal), "the appeal is stored");
        assertEquals(2.0, bed.cases.failures(), "both the event and the reopen failed and were counted");
    }

    @Test
    void aGrantedFullLiftRecordsTheDecisionAndTheLiftByTheReviewer() {
        PunishmentEntity mute = givenStaffMute();
        AppealEntity appeal = givenAppealAwaitingReview(mute);

        AppealReviewResult result = review(appeal, AppealStatus.GRANTED_FULL_LIFT, null);

        assertEquals(AppealReviewResult.Kind.DECIDED, result.kind(), result.message());
        CaseEventRecord decided = bed.cases.log.ofType(mute.getIdentifier(), "appeal.decided").getFirst();
        assertEquals(new CaseEventPayload.AppealDecided(appeal.getIdentifier().toString(), "GRANTED_FULL_LIFT", null), decided.payload());
        assertEquals(ActorTrust.VERIFIED, decided.actorTrust());
        assertEquals(REVIEWER, decided.actorOid());
        CaseEventRecord lifted = bed.cases.log.ofType(mute.getIdentifier(), "punishment.lifted").getFirst();
        assertEquals(new CaseEventPayload.Lifted(appeal.getIdentifier().toString(), false), lifted.payload());
        assertEquals(REVIEWER, lifted.actorOid(), "the reviewer lifted it");
        assertEquals("lifted:" + mute.getIdentifier(), lifted.dedupeKey());
    }

    @Test
    void aGrantedReductionRecordsTheNewExpiryAndNoLift() {
        PunishmentEntity mute = givenStaffMute();
        AppealEntity appeal = givenAppealAwaitingReview(mute);
        long newExpiration = bed.eloBed.clock.millis() + 1000;

        review(appeal, AppealStatus.GRANTED_DURATION_REDUCTION, newExpiration);

        assertEquals(new CaseEventPayload.AppealDecided(appeal.getIdentifier().toString(), "GRANTED_DURATION_REDUCTION", newExpiration),
                bed.cases.log.ofType(mute.getIdentifier(), "appeal.decided").getFirst().payload());
        assertTrue(bed.cases.log.ofType(mute.getIdentifier(), "punishment.lifted").isEmpty(), "the punishment was not lifted");
    }

    @Test
    void aDeniedAppealRecordsOnlyTheDecision() {
        PunishmentEntity mute = givenStaffMute();
        AppealEntity appeal = givenAppealAwaitingReview(mute);

        review(appeal, AppealStatus.DENIED, null);

        assertEquals(1, bed.cases.log.ofType(mute.getIdentifier(), "appeal.decided").size());
        assertTrue(bed.cases.log.ofType(mute.getIdentifier(), "punishment.lifted").isEmpty());
    }

    @Test
    void decidingTheSameAppealTwiceCannotRecordTwoDecisions() {
        PunishmentEntity mute = givenStaffMute();
        AppealEntity appeal = givenAppealAwaitingReview(mute);
        review(appeal, AppealStatus.DENIED, null);

        bed.cases.timeline.appealDecided(appeal, null);

        assertEquals(1, bed.cases.log.ofType(mute.getIdentifier(), "appeal.decided").size(), "the dedupe key holds");
    }

    @Test
    void aFailingRecordingDoesNotFailTheDecision() {
        PunishmentEntity mute = givenStaffMute();
        AppealEntity appeal = givenAppealAwaitingReview(mute);
        bed.cases.log.failAppends(new IllegalStateException("database down"));

        AppealReviewResult result = review(appeal, AppealStatus.GRANTED_FULL_LIFT, null);

        assertEquals(AppealReviewResult.Kind.DECIDED, result.kind(), "the appeal is decided regardless");
        assertTrue(mute.isLifted(), "and the punishment is lifted");
        assertEquals(2.0, bed.cases.failures(), "the decision and the lift recordings failed and were counted");
    }
}
