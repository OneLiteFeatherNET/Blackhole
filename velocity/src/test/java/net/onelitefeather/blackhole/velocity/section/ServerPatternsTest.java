package net.onelitefeather.blackhole.velocity.section;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPatternsTest {

    @Test
    void aTrailingStarMatchesEveryServerWithThePrefix() {
        assertTrue(ServerPatterns.matches("bedwars-*", "bedwars-3"), "bedwars-3 starts with the prefix");
        assertTrue(ServerPatterns.matches("bedwars-*", "bedwars-"), "the star may match nothing");
    }

    @Test
    void aServerWithAnotherPrefixDoesNotMatch() {
        assertFalse(ServerPatterns.matches("bedwars-*", "skywars-1"), "different game, different prefix");
    }

    @Test
    void aQuestionMarkMatchesExactlyOneCharacter() {
        assertTrue(ServerPatterns.matches("lobby-?", "lobby-1"));
        assertFalse(ServerPatterns.matches("lobby-?", "lobby-12"), "two characters are too many for a single ?");
        assertFalse(ServerPatterns.matches("lobby-?", "lobby-"), "? needs a character");
    }

    @Test
    void matchingIgnoresCase() {
        assertTrue(ServerPatterns.matches("BedWars-*", "bedwars-1"), "Velocity looks up servers case-insensitively");
    }

    @Test
    void thePatternMustMatchTheWholeName() {
        assertFalse(ServerPatterns.matches("wars", "bedwars-1"), "a literal pattern is no substring search");
        assertFalse(ServerPatterns.matches("bed", "bedwars"), "a literal pattern is no prefix search");
    }

    @Test
    void regexCharactersInAPatternAreLiteral() {
        assertTrue(ServerPatterns.matches("eu.west-1", "eu.west-1"), "dots are literal");
        assertFalse(ServerPatterns.matches("eu.west-1", "euxwest-1"), "a dot must not match any character");
    }

    @Test
    void anyOfSeveralPatternsMatches() {
        List<String> patterns = List.of("bedwars-*", "minigames");

        assertTrue(ServerPatterns.matchesAny(patterns, "minigames"));
        assertFalse(ServerPatterns.matchesAny(patterns, "lobby-1"));
    }
}
