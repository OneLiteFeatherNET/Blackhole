package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A template step of kind WARN writes a warning, costs its ELO once and is the first rung of the ladder. */
class WarningTemplateStepTest {

    private static final String SECTION = "network";
    private static final String TEMPLATE = "lobby-ladder";

    private final PunishmentTestBed bed = new PunishmentTestBed();

    @BeforeEach
    void givenAWarnThenKickLadder() {
        bed.givenTemplate(TEMPLATE, SECTION, TEMPLATE, new TemplateStep(StepKind.WARN, null, 5), new TemplateStep(StepKind.KICK, null, 10));
    }

    private PunishmentDTO.ApplyResponse applied() {
        PunishmentDTO result = bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, TEMPLATE, null, null), ACTOR);
        return assertInstanceOf(PunishmentDTO.ApplyResponse.class, result, "expected the template to be applied but got " + result);
    }

    private PunishmentEntity stored(PunishmentDTO.ApplyResponse response) {
        return bed.punishments.repository().findById(response.punishment().identifier()).orElseThrow();
    }

    @Test
    void aWarnStepCreatesAWarningRow() {
        PunishmentEntity row = stored(applied());

        assertEquals(PunishmentKind.WARN, row.getKind(), "the first step is a warning");
        assertEquals(TEMPLATE, row.getEscalationGroup());
        assertEquals(0, row.getStepIndex());
        assertEquals(5, row.getEloCost());
        assertFalse(row.isPermanent(), "a warning is never permanent");
    }

    @Test
    void aWarnStepNeverTakesTheActiveSlot() {
        applied();

        assertTrue(bed.profiles.repository().findById(OWNER).map(profile -> profile.getActive().isEmpty()).orElse(true),
                "the warning must not occupy the section slot");
    }

    @Test
    void aWarnStepChangesTheScoreByItsCostOnce() {
        int base = bed.elo.score(OWNER, SECTION).orElseThrow();

        PunishmentDTO.ApplyResponse response = applied();

        assertEquals(base, response.scoreBefore());
        assertEquals(base - 5, response.scoreAfter(), "the cost of the warning step is deducted");
        assertEquals(base - 5, bed.eloBed.storedScore(OWNER, SECTION), "deducted exactly once");
    }

    @Test
    void aSecondApplicationWithinTheWindowSelectsTheKickStep() {
        applied();
        bed.eloBed.clock.advance(Duration.ofHours(1));

        PunishmentDTO.ApplyResponse second = applied();

        assertEquals(2, second.step(), "the warning counted as the first offence");
        assertEquals(PunishmentKind.KICK, stored(second).getKind(), "the second step is the kick");
    }
}
