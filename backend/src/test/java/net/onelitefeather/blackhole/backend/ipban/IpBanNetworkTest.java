package net.onelitefeather.blackhole.backend.ipban;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpBanNetworkTest {

    @Test
    void aSingleIpv4AddressBecomesA32() {
        IpBanNetwork network = IpBanNetwork.parse("203.0.113.7").orElseThrow();

        assertEquals("203.0.113.7/32", network.range(), "a single address takes the full prefix");
        assertFalse(network.hostBitsCleared(), "a single address has no host bits to clear");
    }

    @Test
    void aSingleIpv6AddressBecomesA128() {
        IpBanNetwork network = IpBanNetwork.parse("2001:db8::1").orElseThrow();

        assertTrue(network.range().endsWith("/128"), "an IPv6 address takes /128, was " + network.range());
    }

    @Test
    void aCidrWithHostBitsIsNormalisedAndFlagged() {
        IpBanNetwork network = IpBanNetwork.parse("203.0.113.7/24").orElseThrow();

        assertEquals("203.0.113.0/24", network.range(), "the host bits are cleared");
        assertTrue(network.hostBitsCleared(), "the caller is told so it can warn");
    }

    @Test
    void anIpv6RangeKeepsItsPrefix() {
        assertEquals("2001:0db8:0000:0000:0000:0000:0000:0000/48",
                IpBanNetwork.parse("2001:db8::/48").orElseThrow().range(), "the prefix is kept");
    }

    @Test
    void aHostNameIsNeverAccepted() {
        assertEquals(Optional.empty(), IpBanNetwork.parse("example.org"), "a name is not an address");
    }

    @Test
    void aPrefixLongerThanTheFamilyIsRefused() {
        assertEquals(Optional.empty(), IpBanNetwork.parse("203.0.113.0/33"), "IPv4 stops at /32");
    }

    @Test
    void anIpv4MappedIpv6LiteralIsRefused() {
        assertEquals(Optional.empty(), IpBanNetwork.parse("::ffff:203.0.113.7"), "mapped literals are not stored");
    }

    @Test
    void theMinimumWidthIsSixteenForIpv4AndThirtyTwoForIpv6() {
        assertEquals(16, IpBanNetwork.minimumLength(IpBanNetwork.parse("203.0.113.0/24").orElseThrow().network()));
        assertEquals(32, IpBanNetwork.minimumLength(IpBanNetwork.parse("2001:db8::/48").orElseThrow().network()));
    }
}
