package net.onelitefeather.blackhole.backend.cache;

import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SharedCacheTest {

    /**
     * A connection whose every use fails, as Lettuce does when the server is unreachable. The
     * cache must treat that as a miss on read and as a swallowed error on write and evict.
     */
    @SuppressWarnings("unchecked")
    private static StatefulRedisConnection<String, String> unreachableConnection() {
        return (StatefulRedisConnection<String, String>) Proxy.newProxyInstance(
                SharedCacheTest.class.getClassLoader(),
                new Class<?>[]{StatefulRedisConnection.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("connection refused");
                }
        );
    }

    private static JsonMapper stubJsonMapper() {
        return (JsonMapper) Proxy.newProxyInstance(
                SharedCacheTest.class.getClassLoader(),
                new Class<?>[]{JsonMapper.class},
                (proxy, method, args) -> "{}"
        );
    }

    private static SharedCache cacheOnDownServer() {
        return new SharedCache(unreachableConnection(), stubJsonMapper(), Duration.ofMillis(200));
    }

    @Test
    void readIsAMissWhenTheCacheIsDown() {
        Optional<String> value = cacheOnDownServer().get("blackhole:cache:v1:profile:x", Argument.of(String.class));

        assertEquals(Optional.empty(), value);
    }

    @Test
    void writeDoesNotFailTheCallerWhenTheCacheIsDown() {
        SharedCache cache = cacheOnDownServer();

        assertDoesNotThrow(() -> cache.put("blackhole:cache:v1:profile:x", Argument.of(String.class), "value", Duration.ofSeconds(30)));
    }

    @Test
    void evictDoesNotFailTheCallerWhenTheCacheIsDown() {
        SharedCache cache = cacheOnDownServer();

        assertDoesNotThrow(() -> cache.evict("blackhole:cache:v1:profile:x", "blackhole:cache:v1:elo:x"));
    }

    @Test
    void tryPutReportsFailureWhenTheCacheIsDown() {
        boolean stored = cacheOnDownServer().tryPut("blackhole:cache:v1:profile:x", Argument.of(String.class), "value", Duration.ofSeconds(30));

        assertEquals(false, stored);
    }

    @Test
    void keysIsEmptyWhenTheCacheIsDown() {
        assertEquals(Optional.empty(), cacheOnDownServer().keys("blackhole:cache:v1:*"));
    }
}
