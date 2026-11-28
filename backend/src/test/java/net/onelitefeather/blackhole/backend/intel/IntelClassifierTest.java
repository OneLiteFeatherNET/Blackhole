package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.intel.IntelClassifier.ListedPrefix;
import net.onelitefeather.blackhole.backend.intel.IntelClassifier.Match;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelClassifierTest {

    private static InetAddress address(String ip) {
        return IpLiteral.parse(ip).orElseThrow();
    }

    private static IntelPrefix cidr(String text) {
        return IntelPrefix.parseCidr(text).orElseThrow();
    }

    private static IntelClassifier classifier(Set<Long> hostingAsns, ListedPrefix... prefixes) {
        return IntelClassifier.of(hostingAsns, List.of(prefixes));
    }

    @Test
    void aHostingAsnClassifiesAsHosting() {
        IntelClassifier classifier = classifier(Set.of(16509L));

        List<Match> matches = classifier.classify(address("203.0.113.7"), 16509L);

        assertEquals(List.of(new Match(IntelClass.HOSTING, IntelClassifier.HOSTING_ASN_KEY)), matches,
                "an address of a configured hosting ASN is HOSTING, keyed by the ASN");
    }

    @Test
    void aPrefixInAVpnListClassifiesAsVpnWithThatSource() {
        IntelClassifier classifier = classifier(Set.of(),
                new ListedPrefix("x4bnet-vpn", IntelClass.VPN, cidr("198.51.100.0/24")));

        List<Match> matches = classifier.classify(address("198.51.100.42"), null);

        assertEquals(List.of(new Match(IntelClass.VPN, "x4bnet-vpn")), matches,
                "an address inside a listed range is VPN with the key of its source");
    }

    @Test
    void anAddressInNoListClassifiesAsCleanWithNoMatch() {
        IntelClassifier classifier = classifier(Set.of(16509L),
                new ListedPrefix("x4bnet-vpn", IntelClass.VPN, cidr("198.51.100.0/24")));

        List<Match> matches = classifier.classify(address("192.0.2.1"), 64500L);

        assertTrue(matches.isEmpty(), "an address in no list and of no hosting ASN matches nothing");
    }

    @Test
    void anAddressInAVpnListAndAHostingAsnRecordsBothClasses() {
        IntelClassifier classifier = classifier(Set.of(16509L),
                new ListedPrefix("x4bnet-datacenter", IntelClass.HOSTING, cidr("203.0.113.0/24")),
                new ListedPrefix("x4bnet-vpn", IntelClass.VPN, cidr("203.0.113.0/25")));

        List<Match> matches = classifier.classify(address("203.0.113.7"), 16509L);

        assertTrue(matches.contains(new Match(IntelClass.VPN, "x4bnet-vpn")), "the VPN list match is recorded: " + matches);
        assertTrue(matches.contains(new Match(IntelClass.HOSTING, "x4bnet-datacenter")), "the datacenter match is recorded: " + matches);
        assertTrue(matches.contains(new Match(IntelClass.HOSTING, IntelClassifier.HOSTING_ASN_KEY)), "the ASN match is recorded: " + matches);
    }

    @Test
    void anIpv6AddressIsClassifiedByAsnWhenNoIpv6ListIsEnabled() {
        IntelClassifier classifier = classifier(Set.of(16509L),
                new ListedPrefix("x4bnet-vpn", IntelClass.VPN, cidr("198.51.100.0/24")));

        assertEquals(List.of(new Match(IntelClass.HOSTING, IntelClassifier.HOSTING_ASN_KEY)),
                classifier.classify(address("2001:db8::1"), 16509L), "the ASN path still classifies an IPv6 address");
        assertTrue(classifier.classify(address("2001:db8::1"), 64500L).isEmpty(),
                "without an IPv6 list and a hosting ASN an IPv6 address is clean");
    }

    @Test
    void anIpv4MappedIpv6AddressMatchesTheIpv4List() {
        IntelClassifier classifier = classifier(Set.of(),
                new ListedPrefix("x4bnet-vpn", IntelClass.VPN, cidr("198.51.100.0/24")));

        List<Match> matches = classifier.classify(address("::ffff:198.51.100.42"), null);

        assertEquals(List.of(new Match(IntelClass.VPN, "x4bnet-vpn")), matches,
                "an IPv4-mapped IPv6 address is the IPv4 address it maps to");
    }

    @Test
    void theEmptyClassifierMatchesNothing() {
        assertTrue(IntelClassifier.empty().classify(address("198.51.100.42"), 16509L).isEmpty(),
                "a classifier with no data classifies every address as clean");
    }
}
