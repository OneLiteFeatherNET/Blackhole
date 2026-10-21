package net.onelitefeather.blackhole.backend.appeal.service;

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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppealDecisionServiceTest {

    private static final UUID REVIEWER = UUID.nameUUIDFromBytes("reviewer".getBytes());

    private final PunishmentTestBed bed = new PunishmentTestBed();
    private final InMemoryRepository<AppealRepository, AppealEntity, UUID> appeals =
            InMemoryRepository.<AppealRepository, AppealEntity, UUID>of(AppealRepository.class, AppealEntity::getIdentifier).build();
    private final AppealDecisionService service = new AppealDecisionService(
            appeals.repository(), bed.punishments.repository(), bed.profiles.repository(), bed.slots, bed.profileCache,
            bed.elo, bed.eloBed.sections, bed.eloBed.events, bed.eloBed.clock);

    /** A staff mute that cost 80 ELO; the chat score is 920 afterwards. */
    private PunishmentEntity givenStaffMute() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 80));
        PunishmentDTO.ApplyResponse applied = (PunishmentDTO.ApplyResponse) bed.punishmentService.apply(
                new PunishmentDTO.ApplyRequest(OWNER, "spam", null, STAFF));
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
        bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "warn", null, STAFF));
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
        bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "chat", STAFF));

        assertEquals(DecisionOutcome.PUNISHMENT_NOT_ACTIVE, decide(mute, AppealStatus.GRANTED_FULL_LIFT, null));
    }

    @Test
    void aGrantedFullLiftIsAnnouncedWithItsSectionAndEnforcement() {
        PunishmentEntity mute = givenStaffMute();
        UUID appealId = givenAppeal(mute);

        AppealReviewResult result = service.reviewAppeal(appealId, new AppealReviewDTO(AppealStatus.GRANTED_FULL_LIFT, "ok", REVIEWER, null));

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
                new AppealReviewDTO(AppealStatus.GRANTED_DURATION_REDUCTION, "shorter", REVIEWER, newExpiration));

        assertEquals(AppealReviewResult.Kind.DECIDED, result.kind(), result.message());
        var updated = bed.eloBed.events.eventsOfType("punishment.updated").getFirst();
        assertEquals(newExpiration, updated.payload().get("expiresAt"), "the proxy mirror's TTL follows the reduction");
        assertEquals(false, updated.payload().get("permanent"));
    }

    @Test
    void aDeniedReviewLeavesTheScoreUnchanged() {
        PunishmentEntity mute = givenStaffMute();
        UUID appealId = givenAppeal(mute);

        AppealReviewResult result = service.reviewAppeal(appealId, new AppealReviewDTO(AppealStatus.DENIED, "no", REVIEWER, null));

        assertEquals(AppealReviewResult.Kind.DECIDED, result.kind());
        assertEquals(920, bed.eloBed.storedScore(OWNER, "chat"));
    }
}
