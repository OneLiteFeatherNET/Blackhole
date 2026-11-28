package net.onelitefeather.blackhole.backend.firewall;

import net.onelitefeather.blackhole.backend.areaban.AreaBan;
import net.onelitefeather.blackhole.backend.areaban.AreaBanCache;
import net.onelitefeather.blackhole.backend.areaban.InMemoryAreaBanStore;
import net.onelitefeather.blackhole.backend.intel.AsnNetworks;
import net.onelitefeather.blackhole.backend.intel.IntelPrefix;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaBanAsnFeedSourceTest {

    private static final long NOW = 1_000_000L;
    private static final IntelPrefix AS_64500 = IntelPrefix.parseCidr("198.51.100.0/24").orElseThrow();

    /** An ASN file in which only AS 64500 has a network. */
    private static final class OneNetworkAsnFile implements AsnNetworks {

        @Override
        public long version() {
            return 1;
        }

        @Override
        public List<IntelPrefix> networksOfAsns(Set<Long> asns) {
            return asns.contains(64500L) ? List.of(AS_64500) : List.of();
        }
    }

    private static AreaBan ban(AreaBan.Kind kind, String value, Long expiresAt) {
        return new AreaBan(UUID.randomUUID(), kind, value, "test ban", AreaBan.Source.MANUAL, "admin-oid", 0L, expiresAt,
                null, null, null);
    }

    private static AreaBanCache loaded(InMemoryAreaBanStore store) {
        AreaBanCache cache = new AreaBanCache(store);
        cache.reload();
        return cache;
    }

    @Test
    void anActiveAsnBanContributesTheNetworksOfItsAsn() {
        InMemoryAreaBanStore store = new InMemoryAreaBanStore();
        store.insert(ban(AreaBan.Kind.ASN, "64500", null));
        AreaBanAsnFeedSource source = new AreaBanAsnFeedSource(loaded(store), new OneNetworkAsnFile(), new FeedTestClock(NOW));

        assertEquals(List.of(AS_64500), source.networks(), "an active ASN ban puts its AS networks into the networks feed");
    }

    @Test
    void aLiftedAsnBanContributesNothing() {
        InMemoryAreaBanStore store = new InMemoryAreaBanStore();
        AreaBan ban = ban(AreaBan.Kind.ASN, "64500", null);
        store.insert(ban);
        AreaBanCache cache = loaded(store);
        AreaBanAsnFeedSource source = new AreaBanAsnFeedSource(cache, new OneNetworkAsnFile(), new FeedTestClock(NOW));
        assertEquals(List.of(AS_64500), source.networks(), "precondition: the ban is active before it is lifted");

        store.lift(ban.lifted(NOW, "false positive", "admin-oid"));
        cache.reload();

        assertTrue(source.networks().isEmpty(), "a lifted ASN ban no longer puts its networks into the feed");
    }

    @Test
    void aCountryBanContributesNothing() {
        InMemoryAreaBanStore store = new InMemoryAreaBanStore();
        store.insert(ban(AreaBan.Kind.COUNTRY, "DE", null));
        AreaBanAsnFeedSource source = new AreaBanAsnFeedSource(loaded(store), new OneNetworkAsnFile(), new FeedTestClock(NOW));

        assertTrue(source.networks().isEmpty(), "a country ban never reaches a feed");
    }

    @Test
    void anAsnBanThatExpiresLeavesTheFeedAndChangesTheVersion() {
        InMemoryAreaBanStore store = new InMemoryAreaBanStore();
        store.insert(ban(AreaBan.Kind.ASN, "64500", NOW + 1_000));
        FeedTestClock clock = new FeedTestClock(NOW);
        AreaBanAsnFeedSource source = new AreaBanAsnFeedSource(loaded(store), new OneNetworkAsnFile(), clock);
        Object activeVersion = source.version();
        assertEquals(List.of(AS_64500), source.networks(), "precondition: the ban is active before it expires");

        clock.advanceTo(NOW + 2_000);

        assertTrue(source.networks().isEmpty(), "an expired ASN ban no longer puts its networks into the feed");
        assertNotEquals(activeVersion, source.version(), "the version changes at expiry, so the feed cache rebuilds");
    }
}
