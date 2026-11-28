package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@code /warn} asks for a warning only: when the ladder has climbed past its WARN step, the apply is refused with 422
 * WARN_STEP_REQUIRED and nothing is written. Without the flag the ladder applies whatever step it reached.
 */
class WarningOnlyApplyTest {

    private static final String TEMPLATE = "lobby-ladder";

    private final PunishmentTestBed bed = new PunishmentTestBed();

    @BeforeEach
    void givenAWarnThenKickLadder() {
        bed.givenTemplate(TEMPLATE, "network", TEMPLATE, new TemplateStep(StepKind.WARN, null, 0), new TemplateStep(StepKind.KICK, null, 0));
    }

    private PunishmentDTO apply(Boolean warningOnly) {
        return bed.punishmentService.apply(
                new PunishmentDTO.ApplyRequest(OWNER, TEMPLATE, null, null, null, null, null, warningOnly), ACTOR);
    }

    @Test
    void aWarningOnlyApplyOnAWarnStepWritesTheWarning() {
        PunishmentDTO result = apply(true);

        PunishmentDTO.ApplyResponse response = assertInstanceOf(PunishmentDTO.ApplyResponse.class, result, "a WARN step is a warning: " + result);
        assertEquals(PunishmentKind.WARN, bed.punishments.repository().findById(response.punishment().identifier()).orElseThrow().getKind());
    }

    @Test
    void aWarningOnlyApplyPastTheWarnStepIsRefusedAndWritesNothing() {
        apply(true);
        bed.eloBed.clock.advance(Duration.ofHours(1));
        int rowsBefore = bed.punishments.all().size();

        PunishmentDTO result = apply(true);

        PunishmentDTO.Error error = assertInstanceOf(PunishmentDTO.Error.class, result, "a KICK step is no warning");
        assertEquals(ErrorKind.WARN_STEP_REQUIRED, error.kind(), error.errorMessage());
        assertEquals(422, error.kind().status().getCode(), "the refusal answers 422");
        assertEquals(rowsBefore, bed.punishments.all().size(), "nothing is written for a refused warning");
    }

    @Test
    void anApplyWithoutTheFlagStillClimbsTheLadder() {
        apply(true);
        bed.eloBed.clock.advance(Duration.ofHours(1));

        PunishmentDTO result = apply(null);

        PunishmentDTO.ApplyResponse response = assertInstanceOf(PunishmentDTO.ApplyResponse.class, result, "the ladder applies: " + result);
        assertEquals(2, response.step(), "the second step is the kick");
    }
}
