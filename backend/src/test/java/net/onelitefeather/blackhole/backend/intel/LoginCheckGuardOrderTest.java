package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.guard.GuardLevel;
import net.onelitefeather.blackhole.backend.intel.dto.LoginCheckDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order of the login check of design.md decision 2: staff, area bans, the guard level, then the VPN policy. */
class LoginCheckGuardOrderTest {

    private static final long HOSTING_ASN = 64500L;

    private static LoginCheckDTO.Response check(LoginCheckFixture fixture, String owner, String ip) {
        return assertInstanceOf(LoginCheckDTO.Response.class, fixture.service.check(LoginCheckFixture.request(owner, ip)),
                "a well-formed login must get a response");
    }

    @Test
    void linkedStaffIsAllowedUnderLockdownFromAnAreaBannedAsn() {
        LoginCheckFixture fixture = new LoginCheckFixture();
        fixture.staff(LoginCheckFixture.OWNER);
        fixture.guardLevel(GuardLevel.LOCKDOWN);
        fixture.areaBanAsn(HOSTING_ASN);

        LoginCheckDTO.Response result = check(fixture, LoginCheckFixture.OWNER, LoginCheckFixture.HOSTING_IP);

        assertEquals(IntelPolicy.ALLOW, result.verdict(), "a linked staff owner must be allowed past the guard and area bans");
        assertNull(result.denialReason(), "an allowed staff login carries no denial reason");
    }

    @Test
    void anAreaBanDeniesAnAllowListedOwner() {
        LoginCheckFixture fixture = new LoginCheckFixture();
        fixture.allowListed(LoginCheckFixture.OWNER, null);
        fixture.areaBanAsn(HOSTING_ASN);

        LoginCheckDTO.Response result = check(fixture, LoginCheckFixture.OWNER, LoginCheckFixture.HOSTING_IP);

        assertEquals(IntelPolicy.DENY, result.verdict(), "an area ban must deny an allow-listed owner too");
        assertEquals(LoginDenialReason.AREA_BAN, result.denialReason(), "the denial must name the area ban");
    }

    @Test
    void anAllowListedOwnerIsNotRefusedByTheGuardLevel() {
        LoginCheckFixture fixture = new LoginCheckFixture();
        fixture.allowListed(LoginCheckFixture.OWNER, null);
        fixture.guardLevel(GuardLevel.STRICT);

        LoginCheckDTO.Response result = check(fixture, LoginCheckFixture.OWNER, LoginCheckFixture.HOSTING_IP);

        assertNotEquals(LoginDenialReason.GUARD_STRICT, result.denialReason(),
                "an allow-listed owner must skip the guard level and get the VPN policy result");
    }

    @Test
    void aHostingAddressUnderStrictIsDeniedByTheGuard() {
        LoginCheckFixture fixture = new LoginCheckFixture();
        fixture.guardLevel(GuardLevel.STRICT);

        LoginCheckDTO.Response result = check(fixture, LoginCheckFixture.OWNER, LoginCheckFixture.HOSTING_IP);

        assertEquals(IntelPolicy.DENY, result.verdict(), "STRICT must deny a hosting address whatever the VPN policy says");
        assertEquals(LoginDenialReason.GUARD_STRICT, result.denialReason(), "the denial must name the guard level");
    }

    @Test
    void anUnknownOwnerUnderLockdownFromACleanAddressIsDenied() {
        LoginCheckFixture fixture = new LoginCheckFixture();
        fixture.guardLevel(GuardLevel.LOCKDOWN);

        LoginCheckDTO.Response result = check(fixture, LoginCheckFixture.OWNER, LoginCheckFixture.CLEAN_IP);

        assertEquals(LoginDenialReason.GUARD_LOCKDOWN, result.denialReason(),
                "LOCKDOWN must refuse an owner that is not known, even from a clean address");
    }

    @Test
    void aKnownOwnerUnderLockdownFromACleanAddressIsAllowed() {
        LoginCheckFixture fixture = new LoginCheckFixture();
        fixture.known(LoginCheckFixture.OWNER);
        fixture.guardLevel(GuardLevel.LOCKDOWN);

        LoginCheckDTO.Response result = check(fixture, LoginCheckFixture.OWNER, LoginCheckFixture.CLEAN_IP);

        assertEquals(IntelPolicy.ALLOW, result.verdict(), "LOCKDOWN must let a known owner in from a clean address");
    }

    @Test
    void anAreaBanRefusalIsNotCountedForTheSummary() {
        LoginCheckFixture fixture = new LoginCheckFixture();
        fixture.areaBanAsn(HOSTING_ASN);

        check(fixture, LoginCheckFixture.OWNER, LoginCheckFixture.HOSTING_IP);

        assertTrue(fixture.refusalStore.drainSummary().isEmpty(),
                "an area ban refusal is a decision already taken, so it must not be counted for the summary");
    }

    @Test
    void underOffTheVpnPolicyResultIsUnchangedAndCarriesItsReason() {
        LoginCheckFixture fixture = new LoginCheckFixture();
        fixture.policy(IntelClass.VPN, IntelPolicy.DENY);

        LoginCheckDTO.Response result = check(fixture, LoginCheckFixture.OWNER, LoginCheckFixture.VPN_IP);

        assertEquals(IntelPolicy.DENY, result.verdict(), "the VPN policy must still deny under OFF");
        assertEquals(LoginDenialReason.VPN_POLICY, result.denialReason(), "a VPN policy denial must say so");
    }
}
