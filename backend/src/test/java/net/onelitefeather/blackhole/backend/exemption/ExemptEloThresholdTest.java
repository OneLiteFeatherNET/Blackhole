package net.onelitefeather.blackhole.backend.exemption;

import net.onelitefeather.blackhole.backend.elo.EloChange;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.exemption.dto.ExemptionDTO;
import net.onelitefeather.blackhole.backend.support.EloTestBed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A threshold crossing against an exempt player keeps the score change and opens a review instead of punishing. */
class ExemptEloThresholdTest {

    private static final String OWNER = ExemptionService.ownerOf(java.util.UUID.nameUUIDFromBytes("player".getBytes()));

    private final EloTestBed bed = new EloTestBed();

    private void givenPermissionExempt() {
        bed.staffLinks.exemptions.report(new ExemptionDTO.ReportRequest(OWNER, List.of("*")));
    }

    @Test
    void aSoftCrossingOfAnExemptPlayerAppliesNoPunishmentAndOpensOneReview() {
        givenPermissionExempt();
        bed.givenScore(OWNER, "chat", 720);

        EloChange result = bed.elo.applyDelta(OWNER, "chat", -40, EloReasonCode.TOXICITY_FLAG, null, Map.of());

        assertEquals(680, assertInstanceOf(EloChange.Applied.class, result).scoreAfter(), "the score change is kept");
        assertTrue(bed.punisher.calls().isEmpty(), "no automatic punishment is applied");
        var reviews = bed.reviewBed.reviews.all();
        assertEquals(1, reviews.size(), "one review item");
        assertEquals("OPEN", reviews.getFirst().getState());
        assertEquals("SOFT", reviews.getFirst().getTier());
    }

    @Test
    void aPermanentCrossingOfAnExemptPlayerAppliesNoBanAndOpensOneReview() {
        givenPermissionExempt();
        bed.givenScore(OWNER, "network", 380);

        bed.elo.applyDelta(OWNER, "network", -150, EloReasonCode.TOXICITY_FLAG, null, Map.of());

        assertTrue(bed.punisher.calls().isEmpty(), "no permanent punishment is applied");
        assertEquals("PERMANENT", bed.reviewBed.reviews.all().getFirst().getTier());
    }
}
