package net.onelitefeather.blackhole.velocity.component;

import net.onelitefeather.blackhole.velocity.guard.GuardRefusal;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a staff member reads when the backend refuses a guard or area ban change. */
class GuardMessagesTest {

    private static String english(GuardRefusal refusal) {
        return Rendered.english(GuardMessages.refusal(refusal)).text();
    }

    @Test
    void aStaleStaffRoleAsksForASignInToTheTeamPanel() {
        assertTrue(english(GuardRefusal.STAFF_ROLE_STALE).contains("Sign in to the team panel once, then try again"),
                english(GuardRefusal.STAFF_ROLE_STALE));
    }

    @Test
    void aMemberWithoutTheRoleIsToldWhoMayDoThis() {
        assertTrue(english(GuardRefusal.STAFF_NOT_ELIGIBLE).contains("Only a moderator or an admin"), english(GuardRefusal.STAFF_NOT_ELIGIBLE));
    }

    @Test
    void aCountryRefusalNamesTheAdmin() {
        assertTrue(english(GuardRefusal.FORBIDDEN_KIND).contains("Only an admin may ban a country"), english(GuardRefusal.FORBIDDEN_KIND));
    }

    @Test
    void aDurationRefusalNamesTheSevenDayLimit() {
        assertTrue(english(GuardRefusal.FORBIDDEN_DURATION).contains("up to seven days"), english(GuardRefusal.FORBIDDEN_DURATION));
    }

    @Test
    void aConflictSaysTheStateAlreadyChanged() {
        assertTrue(english(GuardRefusal.CONFLICT).contains("conflicts with the current state"), english(GuardRefusal.CONFLICT));
    }

    @Test
    void aValidationErrorAsksForACorrection() {
        assertTrue(english(GuardRefusal.VALIDATION).contains("not valid"), english(GuardRefusal.VALIDATION));
    }

    @Test
    void anUnknownRefusalSaysTheChangeWasNotSaved() {
        assertTrue(english(GuardRefusal.OTHER).contains("could not be saved"), english(GuardRefusal.OTHER));
    }

    @Test
    void everyRefusalHasAReadableTextInEnglishAndGerman() {
        for (GuardRefusal refusal : EnumSet.allOf(GuardRefusal.class)) {
            String english = english(refusal);
            String german = Rendered.german(GuardMessages.refusal(refusal)).text();

            assertFalse(english.startsWith("guard.error"), refusal + " shows a raw key in English: " + english);
            assertFalse(german.startsWith("guard.error"), refusal + " shows a raw key in German: " + german);
        }
    }

    @Test
    void aGermanValidationErrorKeepsItsUmlauts() {
        String german = Rendered.german(GuardMessages.refusal(GuardRefusal.VALIDATION)).text();

        assertTrue(german.contains("Prüfe den Wert und die Begründung"), german);
    }
}
