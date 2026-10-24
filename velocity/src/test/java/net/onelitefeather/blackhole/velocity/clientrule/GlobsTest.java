package net.onelitefeather.blackhole.velocity.clientrule;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobsTest {

    private static void check(String pattern, String value, boolean expected) {
        assertEquals(expected, Globs.matches(pattern, value), "'" + pattern + "' vs '" + value + "'");
    }

    @Test
    void matchesGlobsIgnoringCase() {
        check("meteor-client:*", "meteor-client:main", true);
        check("meteor-client:*", "Meteor-Client:Main", true);
        check("meteor-client:*", "other:main", false);
        check("*client*", "lunarclient:abc", true);
        check("fabric", "fabric", true);
        check("fabric", "fabric2", false);
        check("fab?ic", "fabric", true);
        check("fab?ic", "fabic", false);
        check("*", "anything", true);
        check("a*b*c", "aXXbYYc", true);
        check("a*b*c", "aXXbYY", false);
        check("*end", "the end", true);
        check("", "", true);
        check("", "x", false);
        check("x", "", false);
        check("**", "", true);
    }

    @Test
    void aPatternWithManyStarsCannotBlowUpOnALongValue() {
        String pattern = "a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*b";
        String value = "a".repeat(2000);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertFalse(Globs.matches(pattern, value)));
    }

    @Test
    void matchesAnyNeedsOnePattern() {
        assertTrue(Globs.matchesAny(List.of("x", "y*"), "yes"));
        assertFalse(Globs.matchesAny(List.of("x", "y*"), "no"));
        assertFalse(Globs.matchesAny(List.of(), "no"));
    }
}
