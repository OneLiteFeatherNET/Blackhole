package net.onelitefeather.blackhole.backend.cache;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.core.type.Argument;
import io.micronaut.serde.ObjectMapper;
import net.onelitefeather.blackhole.backend.playerresolver.ResolvedPlayer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs {@link SharedCache} against a real Dragonfly server (Testcontainers). Needs Docker, so it is
 * tagged {@code integration} and excluded by {@code -PexcludeTags=integration}.
 *
 * <p>The container is started once for the class (read-only infrastructure); every test works on its
 * own random keys and opens its own connection, so tests do not depend on each other.</p>
 */
@Tag("integration")
class SharedCacheIntegrationTest {

    private static final GenericContainer<?> SERVER =
            new GenericContainer<>("docker.dragonflydb.io/dragonflydb/dragonfly")
                    .withExposedPorts(6379)
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withUlimits(
                            new com.github.dockerjava.api.model.Ulimit[]{
                                    new com.github.dockerjava.api.model.Ulimit("memlock", -1L, -1L)}));

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;

    @BeforeAll
    static void startServer() {
        SERVER.start();
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop();
    }

    @BeforeEach
    void connect() {
        client = RedisClient.create("redis://" + SERVER.getHost() + ":" + SERVER.getMappedPort(6379));
        connection = client.connect();
    }

    @AfterEach
    void disconnect() {
        connection.close();
        client.shutdown();
    }

    private SharedCache cache() {
        return new SharedCache(connection, ObjectMapper.getDefault(), Duration.ofSeconds(2));
    }

    private static String uniqueKey(String name) {
        return "blackhole:cache:v1:test:" + name + ":" + UUID.randomUUID();
    }

    @Test
    void aPutIsReadBackAsTheSameValue() {
        String key = CacheKeys.player("RoundTrip-" + UUID.randomUUID());
        ResolvedPlayer resolved = new ResolvedPlayer(UUID.randomUUID(), "RoundTrip", "otis");
        SharedCache cache = cache();

        cache.put(key, Argument.of(ResolvedPlayer.class), resolved, Duration.ofMinutes(1));

        assertEquals(Optional.of(resolved), cache.get(key, Argument.of(ResolvedPlayer.class)),
                "a stored value must deserialize to an equal value");
    }

    @Test
    void aPutCarriesTheRequestedTtlSoTheServerWillExpireIt() {
        String key = uniqueKey("ttl");
        Duration ttl = Duration.ofMinutes(5);

        cache().put(key, Argument.of(String.class), "short-lived", ttl);

        long remainingMillis = connection.sync().pttl(key);
        assertTrue(remainingMillis > 0 && remainingMillis <= ttl.toMillis(),
                "key must carry a positive TTL not above the requested one but was " + remainingMillis);
    }

    @Test
    void evictRemovesTheEntry() {
        String key = uniqueKey("evict");
        SharedCache cache = cache();
        cache.put(key, Argument.of(String.class), "to-be-evicted", Duration.ofMinutes(1));

        cache.evict(key);

        assertFalse(cache.get(key, Argument.of(String.class)).isPresent(), "an evicted key must read as a miss");
    }

    @Test
    void aMissingKeyReadsAsEmpty() {
        assertTrue(cache().get(uniqueKey("missing"), Argument.of(String.class)).isEmpty(),
                "a key never written must read as a miss");
    }
}
