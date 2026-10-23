package net.onelitefeather.blackhole.backend.punishment;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class PunishmentMirrorIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "f0".repeat(64);

    private record Fixture(ApplicationContext context, StatefulRedisConnection<String, String> connection, PunishmentRedisWriter writer,
                           TestClock clock, BlockingQueue<String> messages, StatefulRedisPubSubConnection<String, String> subscriber,
                           RedisClient client) implements AutoCloseable {

        @Override
        public void close() {
            this.subscriber.close();
            this.client.shutdown();
            this.context.close();
        }
    }

    private Fixture fixture(String section) {
        ApplicationContext context = ApplicationContext.run(contextProperties());
        StatefulRedisConnection<String, String> connection = context.getBean(StatefulRedisConnection.class);
        TestClock clock = new TestClock();
        PunishmentRedisWriter writer = new PunishmentRedisWriter(connection, context.getBean(JsonMapper.class), clock);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        RedisClient client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        StatefulRedisPubSubConnection<String, String> subscriber = client.connectPubSub();
        subscriber.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                messages.add(message);
            }
        });
        subscriber.sync().subscribe(RedisTopology.PUNISHMENT_SYNC_CHANNEL);
        connection.sync().del(RedisTopology.punishKey(section, OWNER));
        return new Fixture(context, connection, writer, clock, messages, subscriber, client);
    }

    @Test
    void aTemporaryBanIsStoredUnderItsSectionKeyWithATtlEqualToTheRemainingDuration() throws InterruptedException {
        try (Fixture f = fixture("network")) {
            long expiresAt = f.clock.millis() + Duration.ofMinutes(10).toMillis();

            f.writer.set("network", "BAN", OWNER, "p-1", "t-1", "Griefing", expiresAt, false);

            String key = RedisTopology.punishKey("network", OWNER);
            assertEquals("blackhole:punish:network:" + OWNER, key);
            String json = f.connection.sync().get(key);
            assertNotNull(json, "the key exists");
            assertTrue(json.contains("\"enforcement\":\"BAN\"") && json.contains("\"section\":\"network\"") && json.contains("\"state\":\"SET\""), json);
            long ttl = f.connection.sync().pttl(key);
            assertTrue(ttl > 0 && ttl <= Duration.ofMinutes(10).toMillis(), "TTL follows the remaining duration but was " + ttl);
            String message = f.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "a SET message is published on the sync channel");
            assertTrue(message.contains("\"state\":\"SET\"") && message.contains("\"enforcement\":\"BAN\""), message);
        }
    }

    @Test
    void aPermanentPunishmentIsStoredWithoutATtl() {
        try (Fixture f = fixture("chat")) {
            f.writer.set("chat", "MUTE", OWNER, "p-2", null, "Muted for good", null, false);

            assertEquals(-1L, f.connection.sync().pttl(RedisTopology.punishKey("chat", OWNER)), "no expiry on a permanent punishment");
        }
    }

    @Test
    void anAlreadyExpiredPunishmentIsNotResurrected() {
        try (Fixture f = fixture("network")) {
            f.writer.set("network", "BAN", OWNER, "p-3", null, "old", f.clock.millis() - 1000, false);

            assertNull(f.connection.sync().get(RedisTopology.punishKey("network", OWNER)));
        }
    }

    @Test
    void clearingDeletesOnlyThatSectionsKeyAndPublishesACleared() throws InterruptedException {
        try (Fixture f = fixture("chat")) {
            f.writer.set("chat", "MUTE", OWNER, "p-4", null, "mute", null, false);
            f.writer.set("network", "BAN", OWNER, "p-5", null, "ban", null, false);
            assertNotNull(f.messages.poll(5, TimeUnit.SECONDS), "SET message of the mute");
            assertNotNull(f.messages.poll(5, TimeUnit.SECONDS), "SET message of the ban");

            f.writer.clear("chat", "MUTE", OWNER, "p-4");

            assertNull(f.connection.sync().get(RedisTopology.punishKey("chat", OWNER)), "the chat key is gone");
            assertNotNull(f.connection.sync().get(RedisTopology.punishKey("network", OWNER)), "the ban key is untouched");
            String message = f.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "a CLEARED message is published");
            assertTrue(message.contains("\"state\":\"CLEARED\"") && message.contains("\"section\":\"chat\""), message);
            f.connection.sync().del(RedisTopology.punishKey("network", OWNER));
        }
    }
}
