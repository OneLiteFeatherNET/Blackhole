package net.onelitefeather.blackhole.backend.appeal.service;

import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.appeal.AppealReviewResult;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.appeal.dto.AppealReviewDTO;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.StepKind;
import net.onelitefeather.blackhole.backend.punishment.TemplateStep;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import net.onelitefeather.blackhole.backend.support.RecordingProtocols;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A warning has no slot and no duration: a full lift refunds its cost once, a duration reduction is refused. */
class WarningAppealDecisionTest {

    private static final UUID REVIEWER = UUID.nameUUIDFromBytes("reviewer".getBytes());
    private static final Actor REVIEWER_ACTOR = new Actor(REVIEWER, "Reviewer", ActorKind.HUMAN, false);
    private static final String SECTION = "chat";
    private static final int COST = 5;

    private final PunishmentTestBed bed = new PunishmentTestBed();
    private final InMemoryRepository<AppealRepository, AppealEntity, UUID> appeals =
            InMemoryRepository.<AppealRepository, AppealEntity, UUID>of(AppealRepository.class, AppealEntity::getIdentifier).build();
    private final AppealDecisionService service = new AppealDecisionService(
            appeals.repository(), bed.punishments.repository(), bed.profiles.repository(), bed.slots, bed.profileCache,
            bed.elo, bed.eloBed.sections, bed.eloBed.events, bed.evidence, bed.staffLinks.service, bed.cases.timeline,
            bed.banIds.service, new RecordingProtocols(), bed.staffLinks.profileService, bed.eloBed.clock, bed.audit);

    /** A warning step that costs {@link #COST} ELO; the chat score is 995 afterwards. */
    private PunishmentEntity givenWarning() {
        bed.givenTemplate("warn-chat", SECTION, null, new TemplateStep(StepKind.WARN, null, COST),
                new TemplateStep(StepKind.KICK, null, 10));
        PunishmentDTO.ApplyResponse applied = (PunishmentDTO.ApplyResponse) bed.punishmentService.apply(
                new PunishmentDTO.ApplyRequest(OWNER, "warn-chat", null, null), ACTOR);
        PunishmentEntity warning = bed.punishments.all().stream()
                .filter(p -> p.getIdentifier().equals(applied.punishment().identifier())).findFirst().orElseThrow();
        assertEquals(1000 - COST, bed.eloBed.storedScore(OWNER, SECTION), "precondition: the warning cost 5");
        return warning;
    }

    /** The decision does not check the waiting time, so the clock stays put: time would also let ELO decay move the score. */
    private UUID givenAppeal(PunishmentEntity warning) {
        AppealEntity appeal = new AppealEntity(warning, OWNER, "I did not do it", AppealStatus.ELIGIBLE_PENDING_REVIEW,
                new HashMap<>(Map.of("severityTier", "STANDARD")), 0L, 0L, new HashMap<>());
        UUID id = UUID.randomUUID();
        appeals.given(appeal, id);
        return id;
    }

    private AppealReviewResult review(UUID appealId, AppealStatus decision, Long newExpiration) {
        return service.reviewAppeal(appealId, AppealReviewDTO.withoutMessage(decision, "decided", newExpiration), REVIEWER_ACTOR);
    }

    @Test
    void aFullLiftLiftsTheWarningWithoutTouchingASlot() {
        PunishmentEntity warning = givenWarning();
        UUID appealId = givenAppeal(warning);

        AppealReviewResult result = review(appealId, AppealStatus.GRANTED_FULL_LIFT, null);

        assertEquals(AppealReviewResult.Kind.DECIDED, result.kind(), "a warning full lift is not a conflict: " + result.message());
        assertTrue(warning.isLifted(), "the warning is lifted, so it no longer counts for escalation");
        assertTrue(bed.profiles.repository().findById(OWNER).map(profile -> profile.getActive().isEmpty()).orElse(true),
                "a warning never had a slot and none is changed");
    }

    @Test
    void aFullLiftRefundsTheWarningCostExactlyOnce() {
        PunishmentEntity warning = givenWarning();
        UUID appealId = givenAppeal(warning);

        review(appealId, AppealStatus.GRANTED_FULL_LIFT, null);

        assertEquals(1000, bed.eloBed.storedScore(OWNER, SECTION), "the recorded cost of 5 is added back");
        long refunds = bed.eloBed.eventRepository.all().stream()
                .filter(e -> e.getReasonCode() == EloReasonCode.APPEAL_REFUND && e.getDelta() == COST).count();
        assertEquals(1, refunds, "the refund is recorded once as APPEAL_REFUND");
    }

    @Test
    void aLiftedWarningNoLongerCountsTowardsEscalation() {
        PunishmentEntity warning = givenWarning();
        review(givenAppeal(warning), AppealStatus.GRANTED_FULL_LIFT, null);

        PunishmentDTO result = bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "warn-chat", null, null), ACTOR);

        assertEquals(1, ((PunishmentDTO.ApplyResponse) result).step(),
                "the lifted warning is not an earlier offence, so the first step applies again");
    }

    @Test
    void aDurationReductionOfAWarningIsRefused() {
        PunishmentEntity warning = givenWarning();
        UUID appealId = givenAppeal(warning);
        long newExpiration = bed.eloBed.clock.millis() + Duration.ofDays(1).toMillis();

        AppealReviewResult result = review(appealId, AppealStatus.GRANTED_DURATION_REDUCTION, newExpiration);

        assertEquals(AppealReviewResult.Kind.INVALID_DECISION, result.kind(), "a warning has no duration to reduce");
        assertFalse(warning.isLifted(), "a refused reduction changes nothing");
    }

    @Test
    void aDeniedWarningAppealChangesNeitherTheWarningNorTheScore() {
        PunishmentEntity warning = givenWarning();

        AppealReviewResult result = review(givenAppeal(warning), AppealStatus.DENIED, null);

        assertEquals(AppealReviewResult.Kind.DECIDED, result.kind(), result.message());
        assertFalse(warning.isLifted(), "a denial keeps the warning");
        assertEquals(1000 - COST, bed.eloBed.storedScore(OWNER, SECTION), "a denial refunds nothing");
    }
}
