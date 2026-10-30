package net.onelitefeather.blackhole.backend.guard;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnattendedEvaluatorTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    /** 12:00 in Berlin on a day outside the night window. */
    private static final long NOON = Instant.parse("2026-10-29T11:00:00Z").toEpochMilli();

    private static final UnattendedEvaluator EVALUATOR = new UnattendedEvaluator();

    private static UnattendedSettings settings(GuardLevel level, Duration absenceThreshold, boolean nightWindowEnabled) {
        return new UnattendedSettings(level, absenceThreshold, nightWindowEnabled, NightWindow.parse("01:00-07:00"), BERLIN, 0L, null);
    }

    private static UnattendedSettings twentyMinutes(GuardLevel level) {
        return settings(level, Duration.ofMinutes(20), true);
    }

    private static long at(String utc) {
        return Instant.parse(utc).toEpochMilli();
    }

    @Test
    void staffOnlineMeansOffWhateverTheClock() {
        assertEquals(GuardLevel.OFF, EVALUATOR.automaticLevel(twentyMinutes(GuardLevel.STRICT), true, OptionalLong.of(NOON),
                        at("2026-10-29T00:30:00Z")),
                "staff on the proxy must clear the unattended level at once, even inside the night window");
    }

    @Test
    void staffAbsentForNineteenMinutesIsStillOff() {
        long lastSeen = NOON - Duration.ofMinutes(19).toMillis();

        assertEquals(GuardLevel.OFF, EVALUATOR.automaticLevel(twentyMinutes(GuardLevel.STRICT), false, OptionalLong.of(lastSeen), NOON),
                "nineteen minutes of absence is below the twenty minute threshold");
    }

    @Test
    void staffAbsentForTwentyMinutesIsStrict() {
        long lastSeen = NOON - Duration.ofMinutes(20).toMillis();

        assertEquals(GuardLevel.STRICT, EVALUATOR.automaticLevel(twentyMinutes(GuardLevel.STRICT), false, OptionalLong.of(lastSeen), NOON),
                "twenty minutes of absence reaches the threshold and applies the unattended level");
    }

    @Test
    void aShorterStoredThresholdAppliesSooner() {
        long lastSeen = NOON - Duration.ofMinutes(6).toMillis();

        assertEquals(GuardLevel.STRICT,
                EVALUATOR.automaticLevel(settings(GuardLevel.STRICT, Duration.ofMinutes(5), true), false, OptionalLong.of(lastSeen), NOON),
                "a stored threshold of five minutes must apply after six minutes of absence, where twenty would still give OFF");
    }

    @Test
    void aNeverSeenStaffMemberCountsAsLongAbsent() {
        assertEquals(GuardLevel.STRICT, EVALUATOR.automaticLevel(twentyMinutes(GuardLevel.STRICT), false, OptionalLong.empty(), NOON),
                "a missing last-seen value must count as long absent, so the guard fails toward protection");
    }

    @Test
    void nightWindowAppliesEvenWhenStaffWereSeenTwoMinutesAgo() {
        long lastSeen = at("2026-10-28T23:58:00Z");

        assertEquals(GuardLevel.STRICT,
                EVALUATOR.automaticLevel(twentyMinutes(GuardLevel.STRICT), false, OptionalLong.of(lastSeen), at("2026-10-29T00:00:00Z")),
                "00:00 UTC is 01:00 Berlin, inside the night window, so the unattended level applies");
    }

    @Test
    void aDisabledNightWindowDoesNotApplyAtNight() {
        // 02:00 Berlin, staff absent for one minute
        long lastSeen = at("2026-10-29T00:59:00Z");

        assertEquals(GuardLevel.OFF,
                EVALUATOR.automaticLevel(settings(GuardLevel.STRICT, Duration.ofMinutes(20), false), false, OptionalLong.of(lastSeen),
                        at("2026-10-29T01:00:00Z")),
                "with the night window switched off, only the absence threshold may raise the unattended level");
    }

    @Test
    void aConfiguredLockdownIsUsedWhenTheOperatorSetsIt() {
        assertEquals(GuardLevel.LOCKDOWN, EVALUATOR.automaticLevel(twentyMinutes(GuardLevel.LOCKDOWN), false, OptionalLong.empty(), NOON),
                "the unattended level must be the stored one, including LOCKDOWN");
    }
}
