package net.onelitefeather.blackhole.backend.punishment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A warning counts as an earlier step of its group, so one warning moves the ladder to the next rung. */
class StepSelectorWarningTest {

    @Test
    void oneEarlierWarningSelectsTheKickStepAfterIt() {
        List<TemplateStep> steps = List.of(new TemplateStep(StepKind.WARN, null, 0), new TemplateStep(StepKind.KICK, null, 10));

        StepSelector.Selection selection = StepSelector.select(steps, 1);

        assertEquals(1, selection.index(), "one counted warning selects the second step");
        assertEquals(StepKind.KICK, selection.step().getKind());
    }
}
