package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.intel.dto.LoginCheckDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginCheckTest {

    @Test
    void aDeniedClassReturnsDeny() {
        LoginCheckFixture fixture = new LoginCheckFixture();
        fixture.policy(IntelClass.VPN, IntelPolicy.DENY);

        LoginCheckDTO result = fixture.service.check(LoginCheckFixture.request(LoginCheckFixture.OWNER, LoginCheckFixture.VPN_IP));

        LoginCheckDTO.Response response = assertInstanceOf(LoginCheckDTO.Response.class, result, "the login was checked");
        assertEquals(IntelPolicy.DENY, response.verdict(), "a VPN address under a DENY policy is refused");
        assertEquals(IntelClass.VPN, response.classification(), "the class that decided is named");
        assertEquals("list:x4bnet-vpn", response.sourceKey(), "the matching source is named");
    }

    @Test
    void aFlaggedClassReturnsFlagAndRecordsOneHit() {
        LoginCheckFixture fixture = new LoginCheckFixture();

        LoginCheckDTO result = fixture.service.check(LoginCheckFixture.request(LoginCheckFixture.OWNER, LoginCheckFixture.HOSTING_IP));

        assertEquals(IntelPolicy.FLAG, assertInstanceOf(LoginCheckDTO.Response.class, result).verdict(),
                "a hosting address under the default FLAG policy is flagged");
        assertEquals(1, fixture.store.allHits().size(), "the flag is recorded as one hit");
        assertEquals(IntelPolicy.FLAG, fixture.store.allHits().getFirst().verdict(), "the hit holds the FLAG verdict");
    }

    @Test
    void anAllowListedOwnerGetsAllowAndAHitMarkedAllowListed() {
        LoginCheckFixture fixture = new LoginCheckFixture();
        fixture.policy(IntelClass.VPN, IntelPolicy.DENY);
        fixture.allowListed(LoginCheckFixture.OWNER, null);

        LoginCheckDTO result = fixture.service.check(LoginCheckFixture.request(LoginCheckFixture.OWNER, LoginCheckFixture.VPN_IP));

        assertEquals(IntelPolicy.ALLOW, assertInstanceOf(LoginCheckDTO.Response.class, result).verdict(),
                "an allow-listed owner is ALLOW for every class");
        IntelHit hit = fixture.store.allHits().getFirst();
        assertTrue(hit.allowListed(), "the hit records that the exemption applied");
    }

    @Test
    void anExpiredAllowListEntryDoesNotExempt() {
        LoginCheckFixture fixture = new LoginCheckFixture();
        fixture.policy(IntelClass.VPN, IntelPolicy.DENY);
        fixture.allowListed(LoginCheckFixture.OWNER, fixture.clock.millis() + Duration.ofDays(1).toMillis());
        fixture.clock.advance(Duration.ofDays(2));

        LoginCheckDTO result = fixture.service.check(LoginCheckFixture.request(LoginCheckFixture.OWNER, LoginCheckFixture.VPN_IP));

        assertEquals(IntelPolicy.DENY, assertInstanceOf(LoginCheckDTO.Response.class, result).verdict(),
                "an exemption that has run out no longer lets the owner through");
    }

    @Test
    void aCleanAddressReturnsAllowAndRecordsNothing() {
        LoginCheckFixture fixture = new LoginCheckFixture();

        LoginCheckDTO result = fixture.service.check(LoginCheckFixture.request(LoginCheckFixture.OWNER, LoginCheckFixture.CLEAN_IP));

        LoginCheckDTO.Response response = assertInstanceOf(LoginCheckDTO.Response.class, result);
        assertEquals(IntelPolicy.ALLOW, response.verdict(), "an address in no list is allowed");
        assertEquals(0, fixture.store.allHits().size(), "a clean login records no hit");
    }

    @Test
    void aMalformedOwnerHashIsRefused() {
        LoginCheckFixture fixture = new LoginCheckFixture();

        LoginCheckDTO result = fixture.service.check(LoginCheckFixture.request("not-a-hash", LoginCheckFixture.VPN_IP));

        LoginCheckDTO.Error error = assertInstanceOf(LoginCheckDTO.Error.class, result, "the owner is not a SHA-512 hash");
        assertEquals(ErrorKind.VALIDATION, error.kind(), "a malformed owner is a validation error");
    }
}
