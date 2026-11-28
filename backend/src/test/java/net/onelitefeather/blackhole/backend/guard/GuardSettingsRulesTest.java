package net.onelitefeather.blackhole.backend.guard;

import net.onelitefeather.blackhole.backend.guard.dto.GuardSettingsDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bounds of the editable unattended settings, checked without a database. */
class GuardSettingsRulesTest {

    private static GuardSettingsDTO.UpdateRequest request(String level, Integer minutes, String start, String end, String zone) {
        return new GuardSettingsDTO.UpdateRequest(level, minutes, true, start, end, zone);
    }

    private static GuardSettingsDTO.UpdateRequest valid() {
        return request("STRICT", 20, "01:00", "07:00", "Europe/Berlin");
    }

    private static String refusalOf(GuardSettingsDTO.UpdateRequest request) {
        GuardSettingsRules.Verdict verdict = GuardSettingsRules.check(request);
        return assertInstanceOf(GuardSettingsRules.Verdict.Refused.class, verdict,
                "the request must be refused: " + request).message();
    }

    @Test
    void theOffLevelIsRefusedAsTheUnattendedLevel() {
        String message = refusalOf(request("OFF", 20, "01:00", "07:00", "Europe/Berlin"));

        assertTrue(message.contains("OFF"), "the refusal must name OFF so the admin knows why: " + message);
    }

    @Test
    void strictAndLockdownAreAccepted() {
        assertInstanceOf(GuardSettingsRules.Verdict.Valid.class, GuardSettingsRules.check(valid()),
                "STRICT must be accepted as the unattended level");
        assertInstanceOf(GuardSettingsRules.Verdict.Valid.class,
                GuardSettingsRules.check(request("LOCKDOWN", 20, "01:00", "07:00", "Europe/Berlin")),
                "LOCKDOWN must be accepted as the unattended level");
    }

    @Test
    void anAbsenceThresholdOfFourMinutesIsRefused() {
        String message = refusalOf(request("STRICT", 4, "01:00", "07:00", "Europe/Berlin"));

        assertTrue(message.contains("5") && message.contains("240"), "the refusal must name the range 5 to 240: " + message);
    }

    @Test
    void anAbsenceThresholdOfTwoHundredFortyOneMinutesIsRefused() {
        assertInstanceOf(GuardSettingsRules.Verdict.Refused.class,
                GuardSettingsRules.check(request("STRICT", 241, "01:00", "07:00", "Europe/Berlin")),
                "241 minutes is above the upper bound");
    }

    @Test
    void theAbsenceThresholdBoundsOfFiveAndTwoHundredFortyAreAccepted() {
        assertInstanceOf(GuardSettingsRules.Verdict.Valid.class,
                GuardSettingsRules.check(request("STRICT", 5, "01:00", "07:00", "Europe/Berlin")),
                "5 minutes is the lower bound and must be accepted");
        assertInstanceOf(GuardSettingsRules.Verdict.Valid.class,
                GuardSettingsRules.check(request("STRICT", 240, "01:00", "07:00", "Europe/Berlin")),
                "240 minutes is the upper bound and must be accepted");
    }

    @Test
    void aNightWindowWhoseStartEqualsItsEndIsRefused() {
        assertInstanceOf(GuardSettingsRules.Verdict.Refused.class,
                GuardSettingsRules.check(request("STRICT", 20, "03:00", "03:00", "Europe/Berlin")),
                "start and end both 03:00 is an ambiguous window and must be refused");
    }

    @Test
    void aNightWindowTimeThatIsNotHhMmIsRefused() {
        assertInstanceOf(GuardSettingsRules.Verdict.Refused.class,
                GuardSettingsRules.check(request("STRICT", 20, "7:00", "07:00", "Europe/Berlin")),
                "7:00 is not HH:mm, so the stored text would not be canonical");
    }

    @Test
    void aNightWindowCrossingMidnightIsAccepted() {
        assertInstanceOf(GuardSettingsRules.Verdict.Valid.class,
                GuardSettingsRules.check(request("STRICT", 20, "23:00", "02:00", "Europe/Berlin")),
                "a window from 23:00 to 02:00 crosses midnight and is allowed");
    }

    @Test
    void aFixedOffsetIsRefusedAsTheTimeZone() {
        String message = refusalOf(request("STRICT", 20, "01:00", "07:00", "+01:00"));

        assertTrue(message.contains("Europe/Berlin"), "the refusal must ask for an IANA region id: " + message);
    }

    @Test
    void utcAloneIsRefusedAsTheTimeZone() {
        assertInstanceOf(GuardSettingsRules.Verdict.Refused.class,
                GuardSettingsRules.check(request("STRICT", 20, "01:00", "07:00", "UTC")),
                "UTC alone is not a region that follows daylight saving time and must be refused");
    }

    @Test
    void anUnknownZoneIsRefusedAsTheTimeZone() {
        assertInstanceOf(GuardSettingsRules.Verdict.Refused.class,
                GuardSettingsRules.check(request("STRICT", 20, "01:00", "07:00", "Mars/Olympus")),
                "a zone the runtime does not know must be refused");
    }

    @Test
    void europeBerlinIsAcceptedAsTheTimeZone() {
        GuardSettingsRules.Verdict verdict = GuardSettingsRules.check(valid());

        assertEquals("Europe/Berlin", assertInstanceOf(GuardSettingsRules.Verdict.Valid.class, verdict).settings().zone().getId(),
                "a known IANA region id must be accepted and kept");
    }

    @Test
    void aMissingFieldIsRefusedAsAValidationProblem() {
        assertInstanceOf(GuardSettingsRules.Verdict.Refused.class,
                GuardSettingsRules.check(new GuardSettingsDTO.UpdateRequest(null, 20, true, "01:00", "07:00", "Europe/Berlin")),
                "a missing level must be refused, not stored as null");
    }
}
