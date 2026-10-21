package net.onelitefeather.blackhole.backend.section;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPatternsTest {

    @Test
    void aServerSectionWithAGlobIsValid() {
        assertTrue(ServerPatterns.validate(EnforcementKind.SERVER, List.of("bedwars-*")).isEmpty(),
                "a SERVER section with one proper pattern must be accepted");
    }

    @Test
    void aServerSectionWithoutPatternsIsRejected() {
        assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, List.of()).isEmpty(), "empty pattern list");
        assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, null).isEmpty(), "null pattern list");
    }

    @Test
    void patternsOnANonServerSectionAreRejected() {
        assertFalse(ServerPatterns.validate(EnforcementKind.BAN, List.of("lobby")).isEmpty(), "BAN must not list servers");
        assertFalse(ServerPatterns.validate(EnforcementKind.MUTE, List.of("lobby")).isEmpty(), "MUTE must not list servers");
    }

    @Test
    void nonServerSectionsWithoutPatternsAreValid() {
        assertTrue(ServerPatterns.validate(EnforcementKind.BAN, List.of()).isEmpty(), "BAN without servers");
        assertTrue(ServerPatterns.validate(EnforcementKind.MUTE, null).isEmpty(), "MUTE without servers");
    }

    @Test
    void aMatchAllPatternIsRejected() {
        for (String pattern : List.of("*", "**", "?", "?*", "*?*")) {
            assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, List.of(pattern)).isEmpty(),
                    "'" + pattern + "' matches every server and must be rejected");
        }
    }

    @Test
    void aBlankPatternIsRejected() {
        assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, List.of(" ")).isEmpty(), "blank pattern");
    }

    @Test
    void aPatternWithIllegalCharactersIsRejected() {
        assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, List.of("bed wars")).isEmpty(), "space");
        assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, List.of("a[b]")).isEmpty(), "regex characters");
    }

    @Test
    void oneBadPatternAmongGoodOnesIsReportedOnce() {
        List<String> errors = ServerPatterns.validate(EnforcementKind.SERVER, List.of("bedwars-*", "*"));

        assertEquals(1, errors.size(), "only the match-all pattern is reported: " + errors);
    }

    @Test
    void tooManyPatternsAreRejected() {
        List<String> patterns = java.util.stream.IntStream.range(0, ServerPatterns.MAX_PATTERNS + 1)
                .mapToObj(i -> "server-" + i).toList();

        assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, patterns).isEmpty(), "more than the maximum");
    }

    @Test
    void aGlobMatchesTheWholeServerNameIgnoringCase() {
        assertTrue(ServerPatterns.matches("bedwars-*", "BedWars-12"), "prefix glob, case-insensitive");
        assertTrue(ServerPatterns.matches("sky?", "sky1"), "single character wildcard");
        assertFalse(ServerPatterns.matches("bedwars-*", "lobby-bedwars-1"), "must match from the start");
        assertFalse(ServerPatterns.matches("sky?", "sky12"), "? is exactly one character");
        assertFalse(ServerPatterns.matches("lobby", "lobby-2"), "no implicit wildcard");
    }

    @Test
    void regexMetaCharactersInThePatternAreLiteral() {
        assertTrue(ServerPatterns.matches("a.b", "a.b"), "dot matches itself");
        assertFalse(ServerPatterns.matches("a.b", "axb"), "dot is not a regex wildcard");
    }
}
