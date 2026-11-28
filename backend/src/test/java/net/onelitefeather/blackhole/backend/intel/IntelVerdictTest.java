package net.onelitefeather.blackhole.backend.intel;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelVerdictTest {

    @Test
    void denyWinsOverFlagWhenTwoClassesMatch() {
        Map<IntelClass, IntelPolicy> policies = Map.of(
                IntelClass.VPN, IntelPolicy.DENY,
                IntelClass.HOSTING, IntelPolicy.FLAG);

        IntelVerdict verdict = IntelVerdict.decide(policies, Set.of(IntelClass.VPN, IntelClass.HOSTING), false);

        assertEquals(IntelPolicy.DENY, verdict.policy(), "the stricter of FLAG and DENY is DENY");
    }

    @Test
    void flagWinsOverAllowWhenTwoClassesMatch() {
        Map<IntelClass, IntelPolicy> policies = Map.of(
                IntelClass.VPN, IntelPolicy.ALLOW,
                IntelClass.HOSTING, IntelPolicy.FLAG);

        IntelVerdict verdict = IntelVerdict.decide(policies, Set.of(IntelClass.VPN, IntelClass.HOSTING), false);

        assertEquals(IntelPolicy.FLAG, verdict.policy(), "the stricter of ALLOW and FLAG is FLAG");
    }

    @Test
    void aClassWithoutAPolicyRowDefaultsToFlag() {
        IntelVerdict verdict = IntelVerdict.decide(Map.of(), Set.of(IntelClass.PROXY), false);

        assertEquals(IntelPolicy.FLAG, verdict.policy(), "a class with no policy row is FLAG, the safe default");
    }

    @Test
    void anAllowListedOwnerIsAllowedForEveryClassButKeepsTheClasses() {
        Map<IntelClass, IntelPolicy> policies = Map.of(IntelClass.VPN, IntelPolicy.DENY);

        IntelVerdict verdict = IntelVerdict.decide(policies, Set.of(IntelClass.VPN), true);

        assertEquals(IntelPolicy.ALLOW, verdict.policy(), "an allow-listed owner is ALLOW whatever the policy");
        assertTrue(verdict.allowListed(), "the exemption is recorded so staff can see it applied");
        assertEquals(Set.of(IntelClass.VPN), verdict.classes(), "the matched classes are kept on the verdict");
    }

    @Test
    void aCleanAddressIsAllowedAndMatchesNoClass() {
        IntelVerdict verdict = IntelVerdict.decide(Map.of(IntelClass.VPN, IntelPolicy.DENY), Set.of(), false);

        assertEquals(IntelPolicy.ALLOW, verdict.policy(), "no matched class means ALLOW");
        assertFalse(verdict.allowListed(), "an owner that is not allow-listed is not marked so");
        assertTrue(verdict.classes().isEmpty(), "a clean address has no classes");
    }
}
