package net.onelitefeather.blackhole.backend.section;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionValidatorTest {

    /** A valid BAN section: base 1000, soft 700 (P3D), permanent 300, pardon 700. */
    private static SectionSpec valid() {
        return new SectionSpec("pvp", "PvP", EnforcementKind.BAN, List.of(), 1000, 700, "P3D", 300, 700, "banned", 10);
    }

    private static List<String> errorsOf(SectionSpec spec) {
        return SectionValidator.validate(spec);
    }

    @Test
    void aWellFormedSectionIsValid() {
        assertEquals(List.of(), errorsOf(valid()), "no rule is violated");
    }

    @Test
    void aSectionWithoutASoftTierIsValid() {
        SectionSpec spec = new SectionSpec("pvp", "PvP", EnforcementKind.BAN, null, 1000, null, null, 300, 700, "banned", 0);

        assertEquals(List.of(), errorsOf(spec), "the soft tier is optional");
    }

    @Test
    void thePermanentThresholdMustBeLowerThanTheBaseScore() {
        SectionSpec spec = new SectionSpec("pvp", "PvP", EnforcementKind.BAN, null, 1000, null, null, 1000, 1500, "banned", 10);

        assertTrue(errorsOf(spec).stream().anyMatch(e -> e.contains("permanentThreshold must be lower than baseScore")),
                "permanent threshold equal to the base score is rejected");
    }

    @Test
    void thePardonScoreMustBeAboveThePermanentThreshold() {
        SectionSpec spec = new SectionSpec("pvp", "PvP", EnforcementKind.BAN, null, 1000, null, null, 300, 300, "banned", 10);

        assertTrue(errorsOf(spec).stream().anyMatch(e -> e.contains("pardonScore must be above permanentThreshold")),
                "pardon score equal to the permanent threshold is rejected");
    }

    @Test
    void theSoftThresholdMustLieBetweenThePermanentThresholdAndTheBaseScore() {
        SectionSpec below = new SectionSpec("pvp", "PvP", EnforcementKind.BAN, null, 1000, 300, "P3D", 300, 700, "banned", 10);
        SectionSpec above = new SectionSpec("pvp", "PvP", EnforcementKind.BAN, null, 1000, 1001, "P3D", 300, 700, "banned", 10);

        assertFalse(errorsOf(below).isEmpty(), "soft threshold equal to the permanent threshold");
        assertFalse(errorsOf(above).isEmpty(), "soft threshold above the base score");
    }

    @Test
    void aSoftThresholdNeedsAPositiveDuration() {
        for (String duration : new String[]{null, "", "soon", "PT0S", "-PT1H"}) {
            SectionSpec spec = new SectionSpec("pvp", "PvP", EnforcementKind.BAN, null, 1000, 700, duration, 300, 700, "banned", 10);

            assertFalse(errorsOf(spec).isEmpty(), "soft duration '" + duration + "' must be rejected");
        }
    }

    @Test
    void aSoftDurationWithoutASoftThresholdIsRejected() {
        SectionSpec spec = new SectionSpec("pvp", "PvP", EnforcementKind.BAN, null, 1000, null, "P3D", 300, 700, "banned", 10);

        assertFalse(errorsOf(spec).isEmpty(), "a duration without a threshold is a mistake");
    }

    @Test
    void theKeyMustBeKebabCase() {
        for (String key : new String[]{"Pvp", "pvp_arena", "-pvp", "pvp-", "pv p", "", "a--b"}) {
            SectionSpec spec = new SectionSpec(key, "PvP", EnforcementKind.BAN, null, 1000, 700, "P3D", 300, 700, "banned", 10);

            assertFalse(errorsOf(spec).isEmpty(), "key '" + key + "' must be rejected");
        }
    }

    @Test
    void aBlankPermanentReasonIsRejected() {
        SectionSpec spec = new SectionSpec("pvp", "PvP", EnforcementKind.BAN, null, 1000, 700, "P3D", 300, 700, " ", 10);

        assertFalse(errorsOf(spec).isEmpty(), "the permanent reason is shown to the player and must not be blank");
    }

    @Test
    void aNegativeDecayIsRejected() {
        SectionSpec spec = new SectionSpec("pvp", "PvP", EnforcementKind.BAN, null, 1000, 700, "P3D", 300, 700, "banned", -1);

        assertFalse(errorsOf(spec).isEmpty(), "decay recovers score, it cannot be negative");
    }

    @Test
    void aServerSectionWithoutPatternsIsRejected() {
        SectionSpec spec = new SectionSpec("bedwars", "Bedwars", EnforcementKind.SERVER, List.of(), 1000, 700, "P3D", 300, 700, "banned", 10);

        assertFalse(errorsOf(spec).isEmpty(), "SERVER needs patterns");
    }

    @Test
    void aServerSectionWithAPatternIsValid() {
        SectionSpec spec = new SectionSpec("bedwars", "Bedwars", EnforcementKind.SERVER, List.of("bedwars-*"), 1000, 700, "P3D", 300, 700, "banned", 10);

        assertEquals(List.of(), errorsOf(spec), "SERVER with a pattern");
    }
}
