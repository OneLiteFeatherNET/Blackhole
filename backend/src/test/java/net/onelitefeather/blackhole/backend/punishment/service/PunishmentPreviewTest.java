package net.onelitefeather.blackhole.backend.punishment.service;

import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentPreviewTest {

    private final PunishmentTestBed bed = new PunishmentTestBed();

    private PunishmentDTO.ApplyRequest request(String templateKey) {
        return new PunishmentDTO.ApplyRequest(OWNER, templateKey, null, STAFF);
    }

    private PunishmentDTO.PreviewResponse preview(String templateKey) {
        PunishmentDTO result = bed.punishmentService.preview(request(templateKey));
        return assertInstanceOf(PunishmentDTO.PreviewResponse.class, result, "expected a preview but got " + result);
    }

    private void givenHackingLadder() {
        bed.givenTemplate("hacking", "network", null, step("P1D", 100), step("P7D", 150), step("P30D", 250));
    }

    @Test
    void aFirstOffencePreviewsTheFirstStepAndItsCost() {
        givenHackingLadder();

        PunishmentDTO.PreviewResponse preview = preview("hacking");

        assertEquals("network", preview.section());
        assertEquals(EnforcementKind.BAN, preview.enforcement());
        assertEquals(1, preview.step(), "step 1 of 3");
        assertEquals(3, preview.stepCount());
        assertEquals("P1D", preview.duration());
        assertEquals(100, preview.eloCost());
        assertEquals(1000, preview.scoreBefore(), "a player without a score stands at the base score");
        assertEquals(900, preview.scoreAfter());
        assertEquals(300, preview.permanentThreshold());
        assertFalse(preview.escalatesToPermanent(), "900 is above the permanent threshold");
    }

    @Test
    void thePreviewFollowsTheOffencesAlreadyCounted() {
        givenHackingLadder();
        bed.punishmentService.apply(request("hacking"));

        PunishmentDTO.PreviewResponse preview = preview("hacking");

        assertEquals(2, preview.step(), "the earlier offence moves the preview to step 2");
        assertEquals("P7D", preview.duration());
        assertEquals(900, preview.scoreBefore(), "the score after the first cost");
        assertEquals(750, preview.scoreAfter());
    }

    @Test
    void thePreviewWarnsWhenTheCostCrossesThePermanentThreshold() {
        givenHackingLadder();
        bed.eloBed.givenScore(OWNER, "network", 500);

        PunishmentDTO.PreviewResponse preview = preview("hacking");

        assertEquals(400, preview.scoreAfter());
        assertFalse(preview.escalatesToPermanent(), "400 is still above 300");

        bed.eloBed.givenScore(OWNER, "network", 350);
        PunishmentDTO.PreviewResponse crossing = preview("hacking");

        assertEquals(250, crossing.scoreAfter());
        assertTrue(crossing.escalatesToPermanent(), "250 is below the permanent threshold of 300");
    }

    @Test
    void aFreeTemplateNeverEscalates() {
        bed.givenTemplate("warn", "network", null, step("PT1H", 0));
        bed.eloBed.givenScore(OWNER, "network", 100);

        PunishmentDTO.PreviewResponse preview = preview("warn");

        assertEquals(100, preview.scoreAfter(), "no cost, no change");
        assertFalse(preview.escalatesToPermanent(), "a player already below the threshold is not escalated by a free punishment");
    }

    @Test
    void previewingChangesNothing() {
        givenHackingLadder();

        preview("hacking");
        preview("hacking");

        assertTrue(bed.punishments.all().isEmpty(), "no punishment was stored");
        assertEquals(-1, bed.eloBed.storedScore(OWNER, "network"), "no score row was written");
        assertEquals(1, ((PunishmentDTO.ApplyResponse) bed.punishmentService.apply(request("hacking"))).step(),
                "the previews did not count as offences");
    }

    @Test
    void previewingAnUnknownTemplateIsNotFound() {
        PunishmentDTO result = bed.punishmentService.preview(request("missing"));

        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(PunishmentDTO.Error.class, result).kind());
    }

    @Test
    void previewingForAPermanentlyPunishedPlayerIsAConflict() {
        givenHackingLadder();
        bed.eloBed.givenScore(OWNER, "network", 400);
        bed.elo.applyDelta(OWNER, "network", -200, net.onelitefeather.blackhole.backend.elo.EloReasonCode.MANUAL_ADJUSTMENT, null, java.util.Map.of());

        PunishmentDTO result = bed.punishmentService.preview(request("hacking"));

        assertEquals(ErrorKind.CONFLICT, assertInstanceOf(PunishmentDTO.Error.class, result, "permanent ban in force").kind());
    }

    @Test
    void anInvalidOwnerIsAValidationError() {
        PunishmentDTO result = bed.punishmentService.preview(new PunishmentDTO.ApplyRequest("nope", "hacking", null, STAFF));

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(PunishmentDTO.Error.class, result).kind());
    }

    @Test
    void theEscalationWindowOfTheTemplateDecidesWhichOffencesCount() {
        givenHackingLadder();
        bed.punishmentService.apply(request("hacking"));
        bed.eloBed.clock.advance(Duration.ofDays(366));

        assertEquals(1, preview("hacking").step(), "the earlier offence is outside the default window");
    }
}
