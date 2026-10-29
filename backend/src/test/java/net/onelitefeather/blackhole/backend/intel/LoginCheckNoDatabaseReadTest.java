package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.intel.dto.LoginCheckDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class LoginCheckNoDatabaseReadTest {

    @Test
    void aVerdictIsGivenWhileEveryStoreReadFails() {
        LoginCheckFixture fixture = new LoginCheckFixture();
        fixture.policy(IntelClass.VPN, IntelPolicy.DENY);
        fixture.store.failReads();

        LoginCheckDTO result = fixture.service.check(LoginCheckFixture.request(LoginCheckFixture.OWNER, LoginCheckFixture.VPN_IP));

        assertEquals(IntelPolicy.DENY, assertInstanceOf(LoginCheckDTO.Response.class, result, "no read was needed").verdict(),
                "the verdict comes from the in-memory classifier and policy");
    }

    @Test
    void aCleanAddressIsAllowedWhileEveryStoreReadFails() {
        LoginCheckFixture fixture = new LoginCheckFixture();
        fixture.store.failReads();

        LoginCheckDTO result = fixture.service.check(LoginCheckFixture.request(LoginCheckFixture.OWNER, LoginCheckFixture.CLEAN_IP));

        assertEquals(IntelPolicy.ALLOW, assertInstanceOf(LoginCheckDTO.Response.class, result).verdict(),
                "a clean login does not read the database");
    }
}
