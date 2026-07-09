package net.onelitefeather.blackhole.backend.punishment;

import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sole writer of active-punishment state into Redis. Called only from {@link RedisSyncConsumer}
 * as a side effect of a domain event, never from a request thread - so a Redis hiccup here can
 * never fail the primary write path that triggered it, matching
 * {@code CacheInvalidationPublisher}'s resilience convention.
 */
@Singleton
public class PunishmentRedisWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PunishmentRedisWriter.class);
    private static final String SLOT_BAN = "BAN";
    private static final String SLOT_CHAT_BAN = "CHAT_BAN";

    private final StatefulRedisConnection<String, String> connection;
    private final JsonMapper jsonMapper;

    public PunishmentRedisWriter(StatefulRedisConnection<String, String> connection, JsonMapper jsonMapper) {
        this.connection = connection;
        this.jsonMapper = jsonMapper;
    }

    public void setBan(String owner, String type, String punishmentIdentifier, String templateIdentifier, Long expiresAt) {
        set(RedisTopology.banKey(owner), new PunishmentSyncMessage(
                owner, SLOT_BAN, "SET", type, punishmentIdentifier, templateIdentifier, expiresAt
        ), expiresAt);
    }

    public void clearBan(String owner, String type, String punishmentIdentifier) {
        clear(RedisTopology.banKey(owner), new PunishmentSyncMessage(
                owner, SLOT_BAN, "CLEARED", type, punishmentIdentifier, null, null
        ));
    }

    public void setChatBan(String owner, String punishmentIdentifier, String templateIdentifier, Long expiresAt) {
        set(RedisTopology.chatBanKey(owner), new PunishmentSyncMessage(
                owner, SLOT_CHAT_BAN, "SET", "CHAT", punishmentIdentifier, templateIdentifier, expiresAt
        ), expiresAt);
    }

    public void clearChatBan(String owner, String punishmentIdentifier) {
        clear(RedisTopology.chatBanKey(owner), new PunishmentSyncMessage(
                owner, SLOT_CHAT_BAN, "CLEARED", "CHAT", punishmentIdentifier, null, null
        ));
    }

    private void set(String key, PunishmentSyncMessage message, Long expiresAt) {
        try {
            String json = this.jsonMapper.writeValueAsString(message);
            if (expiresAt != null) {
                long ttlMillis = expiresAt - System.currentTimeMillis();
                if (ttlMillis > 0) {
                    this.connection.sync().psetex(key, ttlMillis, json);
                } else {
                    // Already expired by the time this event was processed - don't resurrect it.
                    this.connection.sync().del(key);
                }
            } else {
                this.connection.sync().set(key, json);
            }
            this.connection.sync().publish(RedisTopology.PUNISHMENT_SYNC_CHANNEL, json);
        } catch (Exception e) {
            LOGGER.error("Failed to write Redis punishment cache for key {}: {}", key, e.getMessage());
        }
    }

    private void clear(String key, PunishmentSyncMessage message) {
        try {
            this.connection.sync().del(key);
            String json = this.jsonMapper.writeValueAsString(message);
            this.connection.sync().publish(RedisTopology.PUNISHMENT_SYNC_CHANNEL, json);
        } catch (Exception e) {
            LOGGER.error("Failed to clear Redis punishment cache for key {}: {}", key, e.getMessage());
        }
    }
}
