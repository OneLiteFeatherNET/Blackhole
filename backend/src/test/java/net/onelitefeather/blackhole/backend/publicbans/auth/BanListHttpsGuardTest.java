package net.onelitefeather.blackhole.backend.publicbans.auth;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BanListHttpsGuardTest {

    private static final BanListHttpsGuard GUARD = new BanListHttpsGuard("10.42.0.0/16, 127.0.0.1");

    @Test
    void plainHttpWithoutAForwardedProtoIsNotSecure() throws Exception {
        assertFalse(GUARD.isSecure("http", null, address("10.42.1.7")), "a plain request is not HTTPS");
    }

    @Test
    void forwardedHttpsFromTheConfiguredIngressIsSecure() throws Exception {
        assertTrue(GUARD.isSecure("http", "https", address("10.42.1.7")), "the ingress says the client used HTTPS");
    }

    @Test
    void forwardedHttpsFromAnotherAddressIsIgnored() throws Exception {
        assertFalse(GUARD.isSecure("http", "https", address("192.168.7.9")), "only the trusted ingress may speak for the scheme");
    }

    @Test
    void forwardedHttpFromTheIngressIsNotSecure() throws Exception {
        assertFalse(GUARD.isSecure("http", "http", address("127.0.0.1")), "the ingress says the client used plain HTTP");
    }

    @Test
    void anExactAddressEntryMatchesOnlyThatAddress() throws Exception {
        assertTrue(GUARD.isSecure("http", "https", address("127.0.0.1")), "a single address is trusted");
        assertFalse(GUARD.isSecure("http", "https", address("127.0.0.2")), "its neighbour is not");
    }

    private static InetAddress address(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }
}
