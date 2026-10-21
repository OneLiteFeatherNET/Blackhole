package net.onelitefeather.blackhole.velocity.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The proxy's connection to the backend's Redis: a read-only view of the mirrored active
 * punishments (one key per section and owner, written solely by the backend), the shared-cache
 * lists of sections and templates, and the two pub/sub channels - punishment sync messages and
 * section changes - which it hands to registered listeners.
 *
 * <p>Every read either returns a definitive answer or says "unavailable" (an exception from
 * {@link #read}, an empty {@link Optional} from the cache reads), so callers can fall back to the
 * backend over HTTP - Redis being unreachable must never make a login or chat check fail outright.</p>
 */
public final class RedisSyncService implements PunishmentState.KeyReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisSyncService.class);
    /** Bounds one shared-cache read, so a slow Redis can't stall tab completion. */
    private static final long SHARED_CACHE_TIMEOUT_MILLIS = 200;

    private final BlackholeConfig config;
    private final SyncCodec codec = new SyncCodec();
    private final List<Consumer<PunishmentSyncMessage>> syncListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> sectionListeners = new CopyOnWriteArrayList<>();

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisPubSubConnection<String, String> pubSubConnection;

    public RedisSyncService(@NotNull BlackholeConfig config) {
        this.config = config;
    }

    /** Registers a listener for punishment sync messages; call before {@link #connect}. */
    public void onPunishmentSync(@NotNull Consumer<PunishmentSyncMessage> listener) {
        this.syncListeners.add(listener);
    }

    /** Registers a listener for section changes (the changed section's key); call before {@link #connect}. */
    public void onSectionChanged(@NotNull Consumer<String> listener) {
        this.sectionListeners.add(listener);
    }

    /**
     * Connects to Redis and subscribes to the sync channels. A failure here is logged and leaves
     * the service disconnected rather than propagating - every proxy must keep working against the
     * backend over plain HTTP if Redis is unavailable at startup.
     */
    public void connect() {
        try {
            this.client = RedisClient.create(this.config.getRedisUri());
            this.connection = this.client.connect();
            this.pubSubConnection = this.client.connectPubSub();
            this.pubSubConnection.addListener(new RedisPubSubAdapter<String, String>() {
                @Override
                public void message(String channel, String message) {
                    onMessage(channel, message);
                }
            });
            this.pubSubConnection.sync().subscribe(RedisTopology.PUNISHMENT_SYNC_CHANNEL, RedisTopology.SECTION_CHANGED_CHANNEL);
            LOGGER.info("Connected to Redis for cross-proxy punishment sync at {}", this.config.getRedisUri());
        } catch (RuntimeException e) {
            LOGGER.error("Failed to connect to Redis at {} - falling back to per-request HTTP checks: {}", this.config.getRedisUri(), e.getMessage());
            shutdown();
        }
    }

    public void shutdown() {
        if (this.pubSubConnection != null) {
            this.pubSubConnection.close();
            this.pubSubConnection = null;
        }
        if (this.connection != null) {
            this.connection.close();
            this.connection = null;
        }
        if (this.client != null) {
            this.client.shutdown();
            this.client = null;
        }
    }

    /**
     * @throws IllegalStateException if Redis isn't connected - callers must fall back to HTTP
     */
    @Override
    public Optional<PunishmentSyncMessage> read(@NotNull String section, @NotNull String owner) {
        StatefulRedisConnection<String, String> conn = this.connection;
        if (conn == null) {
            throw new IllegalStateException("Redis is not connected");
        }
        return this.codec.tryMessage(conn.sync().get(RedisTopology.punishKey(section, owner)));
    }

    /**
     * The backend's cached punishment template list, for tab completion and template lookup. Empty
     * on a miss, when Redis isn't connected, or on an error or timeout, so the caller falls back to
     * the HTTP API (which repopulates the cache).
     */
    public Optional<List<PunishTemplateResponse>> readCachedTemplates() {
        return readSharedCache(RedisTopology.TEMPLATES_ALL_KEY).flatMap(this.codec::templates);
    }

    /**
     * The backend's cached section list; empty if unavailable, so the registry uses the HTTP API.
     */
    public Optional<List<SectionInfo>> readCachedSections() {
        return readSharedCache(RedisTopology.SECTIONS_ALL_KEY).flatMap(this.codec::sections);
    }

    private Optional<String> readSharedCache(@NotNull String key) {
        StatefulRedisConnection<String, String> conn = this.connection;
        if (conn == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(conn.async().get(key).get(SHARED_CACHE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.warn("Shared cache read failed for {}, falling back to HTTP: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private void onMessage(String channel, String message) {
        if (RedisTopology.SECTION_CHANGED_CHANNEL.equals(channel)) {
            notifyAll(this.sectionListeners, message);
            return;
        }
        Optional<PunishmentSyncMessage> parsed = this.codec.tryMessage(message);
        if (parsed.isEmpty()) {
            LOGGER.warn("Ignoring an unreadable punishment sync message");
            return;
        }
        notifyAll(this.syncListeners, parsed.get());
    }

    /** A failing listener must neither stop the others nor kill the Redis callback thread. */
    private static <T> void notifyAll(List<Consumer<T>> listeners, T value) {
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(value);
            } catch (RuntimeException e) {
                LOGGER.error("A Redis listener failed: {}", e.getMessage());
            }
        }
    }
}
