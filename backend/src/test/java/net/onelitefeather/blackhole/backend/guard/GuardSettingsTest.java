package net.onelitefeather.blackhole.backend.guard;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuardSettingsTest {

    private static GuardSettings settings(String level) {
        return GuardSettings.of(level, Duration.ofMinutes(20), "01:00-07:00", "Europe/Berlin", Duration.ofMinutes(5));
    }

    @Test
    void unattendedLevelOffIsRefusedAtConfigurationLoad() {
        assertThrows(IllegalArgumentException.class, () -> settings("OFF"),
                "an unattended level of OFF must stop the start-up, because the unattended mode would do nothing");
    }

    @Test
    void unattendedLevelStrictIsAccepted() {
        assertEquals(GuardLevel.STRICT, settings("STRICT").unattendedLevel(), "STRICT is the default unattended level");
    }

    @Test
    void unattendedLevelLockdownIsAcceptedWhenTheOperatorSetsIt() {
        assertEquals(GuardLevel.LOCKDOWN, settings("LOCKDOWN").unattendedLevel(), "the operator may choose LOCKDOWN");
    }

    @Test
    void aNightWindowWithoutADashIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> GuardSettings.of("STRICT", Duration.ofMinutes(20), "0100", "Europe/Berlin", Duration.ofMinutes(5)),
                "a night window must look like HH:mm-HH:mm");
    }

    @Test
    void aZeroPresenceTtlIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> GuardSettings.of("STRICT", Duration.ofMinutes(20), "01:00-07:00", "Europe/Berlin", Duration.ZERO),
                "a presence ttl of zero would make every proxy stale at once");
    }
}
