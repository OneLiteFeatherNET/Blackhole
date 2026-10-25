package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateValidatorKickTest {

    private static TemplateSpec specWith(PunishTemplateDTO.Step step) {
        return new TemplateSpec("chat-spam", "Spam", "chat", null, null, List.of(step), null);
    }

    @Test
    void aKickStepWithoutADurationIsValid() {
        List<String> errors = TemplateValidator.validate(specWith(new PunishTemplateDTO.Step(null, 5, StepKind.KICK)));

        assertEquals(List.of(), errors, "a kick has no duration and nothing else is wrong");
    }

    @Test
    void aKickStepWithADurationIsRefused() {
        List<String> errors = TemplateValidator.validate(specWith(new PunishTemplateDTO.Step("P1D", 5, StepKind.KICK)));

        assertEquals(1, errors.size(), "exactly the duration rule is violated: " + errors);
        assertTrue(errors.get(0).contains("KICK"), "the message names the kick rule: " + errors.get(0));
    }

    @Test
    void aPunishStepWithoutADurationIsRefused() {
        List<String> errors = TemplateValidator.validate(specWith(new PunishTemplateDTO.Step(null, 5, StepKind.PUNISH)));

        assertFalse(errors.isEmpty(), "a punishment needs a duration");
    }

    @Test
    void aStepWithoutAKindIsAPunishStep() {
        List<String> errors = TemplateValidator.validate(specWith(new PunishTemplateDTO.Step("P1D", 5)));

        assertEquals(List.of(), errors, "the two-argument step stays a valid PUNISH step");
    }
}
