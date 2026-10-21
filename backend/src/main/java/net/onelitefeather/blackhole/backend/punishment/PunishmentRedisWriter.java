package net.onelitefeather.blackhole.backend.punishment;

import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/**
 * The sole writer of active-punishment state into Redis. Called only from {@link PunishmentSyncHandler}
 * as a side effect of a domain event, never from a request thread - so a Redis hiccup here can
 * never fail the primary write path that triggered it, matching
 * {@link net.onelitefeather.blackhole.backend.cache.SharedCache}'s fail-open convention.
 *
 * <p>The state of one owner in one section lives under {@link RedisTopology#punishKey}; a temporary
 * punishment's key expires with it, a permanent one's does not.</p>
 */
@Singleton
public class PunishmentRedisWriter implements PunishmentMirror {

    private static final Logger LOGGER = LoggerFactory.getLogger(PunishmentRedisWriter.class);

    private final StatefulRedisConnection<String, String> connection;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public PunishmentRedisWriter(StatefulRedisConnection<String, String> connection, JsonMapper jsonMapper, Clock clock) {
        this.connection = connection;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public void set(String section, String enforcement, String owner, String punishmentIdentifier, String templateIdentifier, String reason, Long expiresAt) {
        String key = RedisTopology.punishKey(section, owner);
        try {
            String json = this.jsonMapper.writeValueAsString(new PunishmentSyncMessage(
                    owner, section, enforcement, "SET", punishmentIdentifier, templateIdentifier, reason, expiresAt
            ));
            if (expiresAt != null) {
                long ttlMillis = expiresAt - this.clock.millis();
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

    @Override
    public void clear(String section, String enforcement, String owner, String punishmentIdentifier) {
        String key = RedisTopology.punishKey(section, owner);
        try {
            this.connection.sync().del(key);
            String json = this.jsonMapper.writeValueAsString(new PunishmentSyncMessage(
                    owner, section, enforcement, "CLEARED", punishmentIdentifier, null, null, null
            ));
            this.connection.sync().publish(RedisTopology.PUNISHMENT_SYNC_CHANNEL, json);
        } catch (Exception e) {
            LOGGER.error("Failed to clear Redis punishment cache for key {}: {}", key, e.getMessage());
        }
    }
}
