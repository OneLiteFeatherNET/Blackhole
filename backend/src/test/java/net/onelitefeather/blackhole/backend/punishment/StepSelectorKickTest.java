package net.onelitefeather.blackhole.backend.punishment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StepSelectorKickTest {

    private static final List<TemplateStep> KICK_THEN_BAN = List.of(
            new TemplateStep(StepKind.KICK, null, 5),
            new TemplateStep(StepKind.PUNISH, "P1D", 20));

    @Test
    void aKickInTheGroupCountsTowardsTheNextStep() {
        StepSelector.Selection selection = StepSelector.select(KICK_THEN_BAN, 1);

        assertEquals(1, selection.index(), "one earlier kick must move the offender to the second step");
        assertEquals(StepKind.PUNISH, selection.step().getKind(), "the second step is the timed punishment");
    }

    @Test
    void aFirstOffenceSelectsTheKickStep() {
        StepSelector.Selection selection = StepSelector.select(KICK_THEN_BAN, 0);

        assertEquals(0, selection.index());
        assertEquals(StepKind.KICK, selection.step().getKind(), "the first step is the kick");
    }
}
