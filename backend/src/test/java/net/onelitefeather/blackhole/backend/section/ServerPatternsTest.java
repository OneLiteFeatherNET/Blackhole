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

    private static final List<ServerTargetKind> CLOUDNET_KINDS =
            List.of(ServerTargetKind.CLOUDNET_TASK, ServerTargetKind.CLOUDNET_GROUP, ServerTargetKind.CLOUDNET_SERVICE);

    @Test
    void aCloudNetKindAcceptsExactNames() {
        for (ServerTargetKind kind : CLOUDNET_KINDS) {
            assertEquals(List.of(), ServerPatterns.validate(EnforcementKind.SERVER, kind, List.of("BedWars", "Bed_Wars.1-b")),
                    kind + " accepts letters, digits and _ . -");
        }
    }

    @Test
    void aCloudNetKindRejectsWildcards() {
        for (ServerTargetKind kind : CLOUDNET_KINDS) {
            assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, kind, List.of("bedwars-*")).isEmpty(),
                    kind + " must not accept '*'");
            assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, kind, List.of("bedwars-?")).isEmpty(),
                    kind + " must not accept '?'");
        }
    }

    @Test
    void aCloudNetKindRejectsBlankAndIllegalNames() {
        assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, ServerTargetKind.CLOUDNET_TASK, List.of(" ")).isEmpty(), "blank");
        assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, ServerTargetKind.CLOUDNET_TASK, List.of("bed wars")).isEmpty(), "space");
        assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, ServerTargetKind.CLOUDNET_TASK, java.util.Arrays.asList("a", null)).isEmpty(), "null entry");
    }

    @Test
    void aCloudNetKindRejectsAnEmptyList() {
        assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, ServerTargetKind.CLOUDNET_GROUP, List.of()).isEmpty(), "empty list");
        assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, ServerTargetKind.CLOUDNET_GROUP, null).isEmpty(), "null list");
    }

    @Test
    void aCloudNetNameLongerThanTheMaximumIsRejected() {
        String tooLong = "a".repeat(ServerPatterns.MAX_PATTERN_LENGTH + 1);

        assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, ServerTargetKind.CLOUDNET_SERVICE, List.of(tooLong)).isEmpty(), "65 characters");
        assertTrue(ServerPatterns.validate(EnforcementKind.SERVER, ServerTargetKind.CLOUDNET_SERVICE,
                List.of("a".repeat(ServerPatterns.MAX_PATTERN_LENGTH))).isEmpty(), "64 characters");
    }

    @Test
    void moreCloudNetNamesThanTheMaximumAreRejected() {
        List<String> names = java.util.stream.IntStream.range(0, ServerPatterns.MAX_PATTERNS + 1)
                .mapToObj(i -> "task-" + i).toList();

        assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, ServerTargetKind.CLOUDNET_TASK, names).isEmpty(), "51 names");
        assertTrue(ServerPatterns.validate(EnforcementKind.SERVER, ServerTargetKind.CLOUDNET_TASK, names.subList(0, ServerPatterns.MAX_PATTERNS)).isEmpty(), "50 names");
    }

    @Test
    void aCloudNetKindOnANonServerSectionIsRejected() {
        for (EnforcementKind enforcement : List.of(EnforcementKind.BAN, EnforcementKind.MUTE)) {
            assertFalse(ServerPatterns.validate(enforcement, ServerTargetKind.CLOUDNET_TASK, List.of()).isEmpty(),
                    enforcement + " must not carry a CloudNet target kind");
        }
    }

    @Test
    void aMissingTargetKindValidatesAsPatterns() {
        assertTrue(ServerPatterns.validate(EnforcementKind.SERVER, null, List.of("bedwars-*")).isEmpty(), "glob accepted without a kind");
        assertFalse(ServerPatterns.validate(EnforcementKind.SERVER, ServerTargetKind.PATTERN, List.of("*")).isEmpty(), "match-all still rejected");
    }
}
