package net.onelitefeather.blackhole.velocity.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GuardArgumentsTest {

    @Test
    void noArgumentAsksForTheStatus() {
        assertInstanceOf(GuardArguments.Status.class, GuardArguments.parse(""), "/guard alone shows the status");
    }

    @Test
    void strictSetsTheStrictLevel() {
        GuardArguments.Result result = GuardArguments.parse("strict");

        assertEquals(new GuardArguments.SetLevel(GuardLevel.STRICT), result, "/guard strict sets STRICT");
    }

    @Test
    void theLevelIsReadInAnyLetterCase() {
        GuardArguments.Result result = GuardArguments.parse("  LOCKDOWN ");

        assertEquals(new GuardArguments.SetLevel(GuardLevel.LOCKDOWN), result, "the level name is case-insensitive");
    }

    @Test
    void anUnknownLevelShowsTheUsageAndSetsNothing() {
        GuardArguments.Result result = GuardArguments.parse("other");

        assertInstanceOf(GuardArguments.Invalid.class, result, "an unknown level is refused before any backend call");
        assertEquals(GuardArguments.USAGE, ((GuardArguments.Invalid) result).message(), "the usage text is shown");
    }
}
