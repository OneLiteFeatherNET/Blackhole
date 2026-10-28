package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A WARN step without an ELO cost is a record only: no score changes and no ELO event is written. */
class WarningWithoutEloCostTest {

    private static final String SECTION = "network";

    private final PunishmentTestBed bed = new PunishmentTestBed();

    @Test
    void aFreeWarnStepChangesNoScoreAndRecordsNoEloEvent() {
        bed.givenTemplate("free-warn", SECTION, "free-warn", new TemplateStep(StepKind.WARN, null, 0));
        int base = bed.elo.score(OWNER, SECTION).orElseThrow();

        PunishmentDTO result = bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "free-warn", null, null), ACTOR);

        PunishmentDTO.ApplyResponse response = assertInstanceOf(PunishmentDTO.ApplyResponse.class, result, "the warning is applied: " + result);
        assertEquals(base, response.scoreAfter(), "a free warning leaves the score as it was");
        assertEquals(-1, bed.eloBed.storedScore(OWNER, SECTION), "no score row is written for a free warning");
        assertTrue(bed.eloBed.eventRepository.all().isEmpty(), "no ELO event is recorded for a free warning");
    }
}
