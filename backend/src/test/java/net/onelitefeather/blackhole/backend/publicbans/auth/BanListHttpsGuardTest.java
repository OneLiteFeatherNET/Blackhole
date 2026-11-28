package net.onelitefeather.blackhole.backend.publicbans.auth;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void aHostNameInTheTrustedIngressConfigurationFailsTheStartWithoutDns() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new BanListHttpsGuard("ingress.example.com"), "a host name is refused, not resolved");

        assertTrue(failure.getMessage().contains("host names"), "the message says what is allowed");
    }

    @Test
    void aMalformedAddressInTheTrustedIngressConfigurationFailsTheStart() {
        assertThrows(IllegalStateException.class, () -> new BanListHttpsGuard("10.0.0.999"), "an octet above 255 is refused");
        assertThrows(IllegalStateException.class, () -> new BanListHttpsGuard("10.0.0.0/33"), "a prefix above 32 is refused");
    }

    @Test
    void anIpv6LiteralIsAcceptedAsTrustedIngress() throws Exception {
        assertTrue(new BanListHttpsGuard("::1").isSecure("http", "https", address("::1")), "the IPv6 loopback literal is trusted");
    }

    private static InetAddress address(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }
}
