package net.onelitefeather.blackhole.velocity.enforcement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnforcementHealthTest {

    @Test
    void aNewProxyIsNotEnforcingUntilItsInitialisationCompletes() {
        EnforcementHealth health = new EnforcementHealth();

        assertFalse(health.isEnforcing());
        assertEquals(EnforcementHealth.State.STARTING, health.state());
    }

    @Test
    void aCompletedInitialisationEnforces() {
        EnforcementHealth health = new EnforcementHealth();

        health.enforcing();

        assertTrue(health.isEnforcing());
        assertTrue(health.failure().isEmpty());
    }

    @Test
    void aFailedInitialisationRemembersWhyAndDoesNotEnforce() {
        EnforcementHealth health = new EnforcementHealth();

        health.failed(new IllegalStateException("config.json is broken"));

        assertFalse(health.isEnforcing());
        assertEquals(EnforcementHealth.State.FAILED, health.state());
        assertTrue(health.failure().orElseThrow().contains("config.json is broken"));
    }
}
