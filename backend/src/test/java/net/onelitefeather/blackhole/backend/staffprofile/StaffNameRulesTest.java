package net.onelitefeather.blackhole.backend.staffprofile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Pure rules of a staff display name: no clock, no network, no shared state. */
class StaffNameRulesTest {

    @Test
    void aTwoCharacterNameIsAccepted() {
        assertInstanceOf(StaffNameRules.Accepted.class, StaffNameRules.check("Mo"));
    }

    @Test
    void a32CharacterNameIsAccepted() {
        assertInstanceOf(StaffNameRules.Accepted.class, StaffNameRules.check("x".repeat(32)));
    }

    @Test
    void a1CharacterNameIsRejectedAsLength() {
        assertEquals(StaffNameRules.Problem.LENGTH, rejection("M"));
    }

    @Test
    void a33CharacterNameIsRejectedAsLength() {
        assertEquals(StaffNameRules.Problem.LENGTH, rejection("x".repeat(33)));
    }

    @Test
    void theNameIsTrimmedBeforeTheLengthCheck() {
        StaffNameRules.Accepted accepted = assertInstanceOf(StaffNameRules.Accepted.class, StaffNameRules.check("  Mia  "));
        assertEquals("Mia", accepted.displayName());
    }

    @Test
    void letterDigitSpaceHyphenUnderscoreFullStopAndApostropheAreAllowed() {
        assertInstanceOf(StaffNameRules.Accepted.class, StaffNameRules.check("Mia K.-O'Neil_2"));
    }

    @Test
    void accentedLettersAreAllowed() {
        assertInstanceOf(StaffNameRules.Accepted.class, StaffNameRules.check("Zoë Müller"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mia@Home", "Mia#1", "Mia:K", "<b>Mia</b>", "*Mia*", "`Mia`", "Mia|K"})
    void markupCharactersAreRejected(String name) {
        assertEquals(StaffNameRules.Problem.CHARACTERS, rejection(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.org", "www.mia.example", "mia.example.com", "example.net"})
    void aUrlLookalikeIsRejected(String name) {
        assertEquals(StaffNameRules.Problem.LOOKALIKE, rejection(name));
    }

    @Test
    void anIpAddressLookalikeIsRejected() {
        assertEquals(StaffNameRules.Problem.LOOKALIKE, rejection("10.0.0.1"));
    }

    @Test
    void aUuidLookalikeIsRejected() {
        assertEquals(StaffNameRules.Problem.LOOKALIKE, rejection("123e4567-e89b-12d3-a456-426614174000"));
    }

    @Test
    void aDashlessUuidLookalikeIsRejected() {
        assertEquals(StaffNameRules.Problem.LOOKALIKE, rejection("123e4567e89b12d3a456426614174000"));
    }

    @Test
    void aBanIdLookalikeIsRejected() {
        assertEquals(StaffNameRules.Problem.LOOKALIKE, rejection("K7P2QW9X"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"staff", "Team", "MODERATION", "admin", "System", "bot", "Support", "Discord",
            "Verwaltung", "Moderationsteam", "moderator", "Moderation team"})
    void everyReservedWordIsRejectedInAnyCase(String name) {
        assertEquals(StaffNameRules.Problem.RESERVED, rejection(name));
    }

    @Test
    void aReservedWordInsideANameIsRejected() {
        assertEquals(StaffNameRules.Problem.RESERVED, rejection("Mia Admin"));
    }

    @Test
    void aPseudonymThatIsNotAReservedWordIsAccepted() {
        assertInstanceOf(StaffNameRules.Accepted.class, StaffNameRules.check("Nightwatch"));
    }

    @Test
    void fullwidthLettersAreNormalisedBeforeTheRules() {
        StaffNameRules.Accepted accepted = assertInstanceOf(StaffNameRules.Accepted.class, StaffNameRules.check("Ｍｉａ"));
        assertEquals("Mia", accepted.displayName());
    }

    @Test
    void theFoldedFormIgnoresCaseAndNormalisation() {
        assertEquals(StaffNameRules.fold("Mia K."), StaffNameRules.fold("MIA K."));
        assertEquals(StaffNameRules.fold("Mia K."), StaffNameRules.fold("ＭIA K."));
    }

    @Test
    void theFoldedFormDiffersForDifferentNames() {
        assertFalse(StaffNameRules.fold("Mia K.").equals(StaffNameRules.fold("Mia L.")));
    }

    private static StaffNameRules.Problem rejection(String name) {
        return assertInstanceOf(StaffNameRules.Rejected.class, StaffNameRules.check(name)).problem();
    }
}
