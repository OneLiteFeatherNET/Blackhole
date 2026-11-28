package net.onelitefeather.blackhole.backend.firewall;

import net.onelitefeather.blackhole.backend.intel.IntelPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostingAsnFeedSourceTest {

    @Test
    void theWalkRunsAgainOnlyWhenTheAsnFileChanges() {
        FeedCacheTest.CountingAsnNetworks asn = new FeedCacheTest.CountingAsnNetworks();
        HostingAsnFeedSource source = new HostingAsnFeedSource(asn, () -> IntelPolicy.DENY, Set.of(64500L));

        source.networks();
        source.networks();
        assertEquals(1, asn.walks, "the same file and ASN set are not walked twice");

        asn.version = 2;
        source.networks();
        assertEquals(2, asn.walks, "a replaced ASN file is walked again");
    }

    @Test
    void nothingIsWalkedUnlessTheHostingPolicyIsDeny() {
        FeedCacheTest.CountingAsnNetworks asn = new FeedCacheTest.CountingAsnNetworks();
        HostingAsnFeedSource source = new HostingAsnFeedSource(asn, () -> IntelPolicy.FLAG, Set.of(64500L));

        assertTrue(source.networks().isEmpty(), "under FLAG the hosting ASNs contribute no networks");
        assertEquals(0, asn.walks, "and the ASN file is not read at all");
    }

    @Test
    void noAsnWalkWithoutConfiguredHostingAsns() {
        FeedCacheTest.CountingAsnNetworks asn = new FeedCacheTest.CountingAsnNetworks();
        HostingAsnFeedSource source = new HostingAsnFeedSource(asn, () -> IntelPolicy.DENY, Set.of());

        assertTrue(source.networks().isEmpty(), "no hosting ASNs configured, so nothing to enumerate");
        assertEquals(0, asn.walks, "and no walk");
    }
}
