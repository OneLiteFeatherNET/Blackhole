package net.onelitefeather.blackhole.backend.areaban;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaBanCacheTest {

    private static final long NOW = 1_000_000L;

    private static AreaBan ban(AreaBan.Kind kind, String value, Long expiresAt) {
        return new AreaBan(UUID.randomUUID(), kind, value, "test ban", AreaBan.Source.MANUAL, "admin-oid", 0L, expiresAt,
                null, null, null);
    }

    @Test
    void anAsnBanAppliesToALoginFromThatAsn() {
        InMemoryAreaBanStore store = new InMemoryAreaBanStore();
        store.insert(ban(AreaBan.Kind.ASN, "64500", null));
        AreaBanCache cache = new AreaBanCache(store);
        cache.reload();

        assertTrue(cache.applying(64500L, null, NOW).isPresent(), "an ASN ban must apply to a login from that ASN");
    }

    @Test
    void aCountryBanAppliesToALoginFromThatCountry() {
        InMemoryAreaBanStore store = new InMemoryAreaBanStore();
        store.insert(ban(AreaBan.Kind.COUNTRY, "DE", null));
        AreaBanCache cache = new AreaBanCache(store);
        cache.reload();

        assertTrue(cache.applying(null, "DE", NOW).isPresent(), "a country ban must apply to a login from that country");
    }

    @Test
    void anExpiredBanDoesNotApply() {
        InMemoryAreaBanStore store = new InMemoryAreaBanStore();
        store.insert(ban(AreaBan.Kind.ASN, "64500", NOW - 1));
        AreaBanCache cache = new AreaBanCache(store);
        cache.reload();

        assertFalse(cache.applying(64500L, null, NOW).isPresent(), "an expired ban must not apply to a login");
    }

    @Test
    void aFailedReloadKeepsTheLastBans() {
        boolean[] broken = {false};
        InMemoryAreaBanStore store = new InMemoryAreaBanStore() {
            @Override
            public java.util.List<AreaBan> listUnlifted() {
                if (broken[0]) {
                    throw new IllegalStateException("database down");
                }
                return super.listUnlifted();
            }
        };
        store.insert(ban(AreaBan.Kind.ASN, "64500", null));
        AreaBanCache cache = new AreaBanCache(store);
        cache.reload();

        broken[0] = true;
        cache.reload();

        assertTrue(cache.applying(64500L, null, NOW).isPresent(), "a failed reload must keep the bans that were loaded");
    }
}
