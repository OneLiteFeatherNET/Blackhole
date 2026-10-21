package net.onelitefeather.blackhole.backend.cache;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheKeysTest {

    @Test
    void keysCarryTheVersionedNamespace() {
        assertEquals("blackhole:cache:v1:profile:abc", CacheKeys.profile("abc"));
        assertEquals("blackhole:cache:v1:elo:abc", CacheKeys.elo("abc"));
        assertEquals("blackhole:cache:v1:templates:all", CacheKeys.TEMPLATES_ALL);
    }

    @Test
    void templateKeyIsTheIdentifier() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertEquals("blackhole:cache:v1:template:00000000-0000-0000-0000-000000000001", CacheKeys.template(id));
    }

    @Test
    void playerKeyIsCaseInsensitive() {
        assertEquals(CacheKeys.player("Notch"), CacheKeys.player("notch"));
        assertEquals("blackhole:cache:v1:player:notch", CacheKeys.player("NOTCH"));
    }
}
