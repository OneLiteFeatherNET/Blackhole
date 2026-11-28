package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateValidatorWarnTest {

    private static TemplateSpec spec(String warnWindow, PunishTemplateDTO.Step... steps) {
        return new TemplateSpec("chat-spam", "Chat spam", "network", null, null, warnWindow, List.of(steps), null);
    }

    @Test
    void aWarnStepWithoutADurationIsValid() {
        assertTrue(TemplateValidator.validate(spec(null, new PunishTemplateDTO.Step(null, 0, StepKind.WARN))).isEmpty());
    }

    @Test
    void aWarnStepWithADurationIsRefused() {
        List<String> errors = TemplateValidator.validate(spec(null, new PunishTemplateDTO.Step("P1D", 0, StepKind.WARN)));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("step 1 is a WARN step"), errors.get(0));
    }

    @Test
    void aPunishStepWithoutADurationIsRefused() {
        assertFalse(TemplateValidator.validate(spec(null, new PunishTemplateDTO.Step(null, 0, StepKind.PUNISH))).isEmpty());
    }

    @Test
    void aWarnWindowMustBeAPositiveDuration() {
        assertTrue(TemplateValidator.validate(spec("P30D", new PunishTemplateDTO.Step(null, 0, StepKind.WARN))).isEmpty());
        assertFalse(TemplateValidator.validate(spec("P0D", new PunishTemplateDTO.Step(null, 0, StepKind.WARN))).isEmpty(),
                "a zero warn window is refused");
        assertFalse(TemplateValidator.validate(spec("thirty days", new PunishTemplateDTO.Step(null, 0, StepKind.WARN))).isEmpty(),
                "a warn window that is not ISO-8601 is refused");
    }
}
