package net.onelitefeather.blackhole.backend.exemption;

import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.exemption.dto.ExemptionDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.EloTestBed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExemptionReviewDecisionTest {

    private static final String OWNER = ExemptionService.ownerOf(UUID.nameUUIDFromBytes("player".getBytes()));
    private static final Actor ADMIN = new Actor(UUID.nameUUIDFromBytes("admin".getBytes()), "Admin", ActorKind.HUMAN, false, true);

    private final EloTestBed bed = new EloTestBed();

    /** Opens one SOFT review by crossing the soft threshold as an exempt player. */
    private UUID givenOpenReview() {
        bed.staffLinks.exemptions.report(new ExemptionDTO.ReportRequest(OWNER, List.of("*")));
        bed.givenScore(OWNER, "chat", 720);
        bed.elo.applyDelta(OWNER, "chat", -40, EloReasonCode.TOXICITY_FLAG, null, Map.of());
        return bed.reviewBed.reviews.all().getFirst().getId();
    }

    @Test
    void anAdminApplyPlacesTheHeldPunishmentWithTheJustificationAndMarksTheReviewApplied() {
        UUID id = givenOpenReview();

        ExemptionDTO result = bed.reviewService.decide(id, true, "confirmed by logs", ADMIN);

        assertInstanceOf(ExemptionDTO.ReviewResponse.class, result, "the decision is answered");
        assertEquals(1, bed.punisher.calls().size(), "the punishment is placed once");
        assertEquals("APPLIED", bed.reviewBed.reviews.all().getFirst().getState());
        assertTrue(bed.punisher.calls().getFirst().metaData().containsKey(ExemptionGuard.OVERRIDE_METADATA_KEY),
                "the override is recorded with the punishment");
    }

    @Test
    void anAdminDismissalClosesTheReviewAndPlacesNothing() {
        UUID id = givenOpenReview();

        ExemptionDTO result = bed.reviewService.decide(id, false, "false positive", ADMIN);

        assertInstanceOf(ExemptionDTO.ReviewResponse.class, result);
        assertTrue(bed.punisher.calls().isEmpty(), "nothing is placed");
        assertEquals("DISMISSED", bed.reviewBed.reviews.all().getFirst().getState());
    }

    @Test
    void aSecondDecisionOfTheSameReviewIsAConflict() {
        UUID id = givenOpenReview();
        bed.reviewService.decide(id, false, "false positive", ADMIN);

        ExemptionDTO again = bed.reviewService.decide(id, true, "changed my mind", ADMIN);

        assertEquals(ErrorKind.CONFLICT, assertInstanceOf(ExemptionDTO.Error.class, again).kind(),
                "a decided review is never decided again");
        assertTrue(bed.punisher.calls().isEmpty(), "the second decision places nothing");
    }

    @Test
    void aReviewJustificationNamingAPlayerIsRefusedAndNothingIsPlaced() {
        UUID id = givenOpenReview();

        ExemptionDTO result = bed.reviewService.decide(id, true, "player 123e4567-e89b-42d3-a456-426614174000 again", ADMIN);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(ExemptionDTO.Error.class, result).kind());
        assertTrue(bed.punisher.calls().isEmpty(), "no punishment is placed");
        assertEquals("OPEN", bed.reviewBed.reviews.all().getFirst().getState(), "the review stays open");
    }

    @Test
    void anApplyWithoutJustificationIsRefusedAndChangesNothing() {
        UUID id = givenOpenReview();

        ExemptionDTO result = bed.reviewService.decide(id, true, " ", ADMIN);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(ExemptionDTO.Error.class, result).kind());
        assertEquals("OPEN", bed.reviewBed.reviews.all().getFirst().getState(), "the review stays open");
    }
}
