package net.onelitefeather.blackhole.backend.ipban;

import net.onelitefeather.blackhole.backend.intel.IntelPrefix;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpBanProtectionTest {

    private static IntelPrefix network(String text) {
        return IpBanNetwork.parse(text).orElseThrow().network();
    }

    @Test
    void loopbackIsProtected() {
        assertTrue(new IpBanProtection("").overlapping(network("127.0.0.1")).isPresent(), "loopback is never banned");
    }

    @Test
    void rfc1918AndUniqueLocalAreProtected() {
        IpBanProtection protection = new IpBanProtection("");

        assertTrue(protection.overlapping(network("192.168.1.20")).isPresent(), "RFC 1918 is protected");
        assertTrue(protection.overlapping(network("fd00::5")).isPresent(), "unique-local is protected");
    }

    @Test
    void aPublicAddressPasses() {
        assertFalse(new IpBanProtection("").overlapping(network("203.0.113.7")).isPresent(), "a public address may be banned");
    }

    @Test
    void aRangeThatContainsAProtectedNetworkIsRefused() {
        assertTrue(new IpBanProtection("").overlapping(network("10.0.0.0/8")).isPresent(),
                "a range overlapping a protected network is refused, not only one inside it");
    }

    @Test
    void anOperatorListedRangeIsProtected() {
        IpBanProtection protection = new IpBanProtection("198.51.100.0/24, 2001:db8:ffff::/48");

        assertTrue(protection.overlapping(network("198.51.100.9")).isPresent(), "an operator-listed edge range is protected");
        assertTrue(protection.overlapping(network("2001:db8:ffff:1::4")).isPresent(), "an operator-listed IPv6 range is protected");
    }

    @Test
    void aMalformedConfiguredEntryStopsTheApplication() {
        assertThrows(IllegalStateException.class, () -> new IpBanProtection("not-a-range"),
                "a bad entry must fail at start-up, not silently protect nothing");
    }
}
