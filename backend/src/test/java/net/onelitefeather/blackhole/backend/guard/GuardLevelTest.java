package net.onelitefeather.blackhole.backend.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuardLevelTest {

    @Test
    void effectiveLevelIsLockdownWhenManualIsLockdownAndAutomaticIsOff() {
        GuardState state = GuardState.of(GuardLevel.LOCKDOWN, GuardLevel.OFF, 1L);

        assertEquals(GuardLevel.LOCKDOWN, state.effective(), "a manual LOCKDOWN must hold even with no automatic level");
    }

    @Test
    void effectiveLevelIsStrictWhenManualIsOffAndAutomaticIsStrict() {
        GuardState state = GuardState.of(GuardLevel.OFF, GuardLevel.STRICT, 1L);

        assertEquals(GuardLevel.STRICT, state.effective(), "the unattended STRICT must apply when the manual level is OFF");
    }

    @Test
    void stricterOfReturnsTheHigherLevelWhicheverSideItIsOn() {
        assertEquals(GuardLevel.LOCKDOWN, GuardLevel.STRICT.stricterOf(GuardLevel.LOCKDOWN), "LOCKDOWN is the stricter");
        assertEquals(GuardLevel.LOCKDOWN, GuardLevel.LOCKDOWN.stricterOf(GuardLevel.OFF), "LOCKDOWN is the stricter");
    }
}
