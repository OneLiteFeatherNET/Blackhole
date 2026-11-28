package net.onelitefeather.blackhole.backend.firewall;

import net.onelitefeather.blackhole.backend.intel.IntelPrefix;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeedRendererTest {

    private static final long EXPIRES = 1_793_000_000_000L;

    private static IntelPrefix net(String cidr) {
        return IntelPrefix.parseCidr(cidr).orElseThrow();
    }

    private static FeedRenderer.Entry ban(String cidr) {
        return new FeedRenderer.Entry(net(cidr), "PANEL", "5f2c0000-0000-4000-8000-000000000001", EXPIRES);
    }

    @Test
    void singleAddressesAreWrittenBareWithIpv4BeforeIpv6() {
        String text = FeedRenderer.render(List.of(ban("2001:db8::7/128"), ban("203.0.113.7/32")), true, false, n -> false).text();

        assertEquals("203.0.113.7\n2001:db8::7\n", text, "bare addresses, IPv4 first");
    }

    @Test
    void networksAreWrittenOnceInSortedOrder() {
        List<FeedRenderer.Entry> entries = List.of(
                new FeedRenderer.Entry(net("203.0.113.0/24"), "VPN", null, null),
                new FeedRenderer.Entry(net("203.0.113.0/24"), "PANEL", "x", null),
                new FeedRenderer.Entry(net("198.51.100.0/24"), "LIST", null, null));

        String text = FeedRenderer.render(entries, false, false, n -> false).text();

        assertEquals("198.51.100.0/24\n203.0.113.0/24\n", text, "each network once, sorted by address");
    }

    @Test
    void commentLineCarriesIdKindAndExpiryButNoName() {
        String text = FeedRenderer.render(List.of(ban("203.0.113.7/32")), true, true, n -> false).text();

        String expected = "# id=5f2c0000-0000-4000-8000-000000000001 kind=PANEL expires=" + Instant.ofEpochMilli(EXPIRES) + "\n203.0.113.7\n";
        assertEquals(expected, text, "the comment names the ban, its kind and its expiry only");
    }

    @Test
    void commentFreeModeWritesNoHashLine() {
        String text = FeedRenderer.render(List.of(ban("203.0.113.7/32")), true, false, n -> false).text();

        assertEquals("203.0.113.7\n", text, "the comment-free mode carries only the entries");
    }

    @Test
    void protectedNetworkIsRefusedAndCounted() {
        IntelPrefix edge = net("198.51.100.0/24");
        FeedRenderer.Output output = FeedRenderer.render(
                List.of(new FeedRenderer.Entry(edge, "PANEL", "x", null), new FeedRenderer.Entry(net("203.0.113.0/24"), "PANEL", "y", null)),
                false, false, edge::equals);

        assertEquals("203.0.113.0/24\n", output.text(), "the protected line never reaches the feed");
        assertEquals(1, output.refused(), "one refusal is counted");
    }

    @Test
    void anEmptyFeedIsAnEmptyBody() {
        assertEquals("", FeedRenderer.render(List.of(), false, true, n -> false).text(), "no entries, no lines");
    }
}
