package net.onelitefeather.blackhole.backend.guard;

import net.onelitefeather.blackhole.backend.intel.IntelClass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardLevelRulesTest {

    @Test
    void strictRefusesVpnAddresses() {
        assertTrue(GuardLevelRules.refusesClassification(GuardLevel.STRICT, IntelClass.VPN),
                "STRICT must refuse a VPN address");
    }

    @Test
    void strictRefusesHostingAddresses() {
        assertTrue(GuardLevelRules.refusesClassification(GuardLevel.STRICT, IntelClass.HOSTING),
                "STRICT must refuse a hosting address");
    }

    @Test
    void strictRefusesProxyAddresses() {
        assertTrue(GuardLevelRules.refusesClassification(GuardLevel.STRICT, IntelClass.PROXY),
                "STRICT must refuse a proxy address");
    }

    @Test
    void strictAllowsCleanAddresses() {
        assertFalse(GuardLevelRules.refusesClassification(GuardLevel.STRICT, null),
                "STRICT must allow an address that matched no class");
    }

    @Test
    void offRefusesNothingByClassification() {
        assertFalse(GuardLevelRules.refusesClassification(GuardLevel.OFF, IntelClass.VPN),
                "OFF leaves the classification to the VPN policy");
    }

    @Test
    void lockdownRefusesAnUnknownOwner() {
        assertTrue(GuardLevelRules.refusesUnknownOwner(GuardLevel.LOCKDOWN, false),
                "LOCKDOWN must refuse an owner that is not known to the network");
    }

    @Test
    void lockdownAllowsAKnownOwner() {
        assertFalse(GuardLevelRules.refusesUnknownOwner(GuardLevel.LOCKDOWN, true),
                "LOCKDOWN must allow an owner that is known to the network");
    }

    @Test
    void strictDoesNotRefuseUnknownOwners() {
        assertFalse(GuardLevelRules.refusesUnknownOwner(GuardLevel.STRICT, false),
                "only LOCKDOWN refuses unknown owners");
    }
}
