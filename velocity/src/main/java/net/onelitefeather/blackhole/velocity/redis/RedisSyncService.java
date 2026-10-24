package net.onelitefeather.blackhole.velocity.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import net.onelitefeather.blackhole.velocity.clientrule.ClientRuleNotice;
import net.onelitefeather.blackhole.velocity.clientrule.ClientRuleNoticeChannel;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
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
 * The proxy's Redis view of the backend: the shared-cache lists of sections and templates used for
 * tab completion, and the client-rule pub/sub channels. Punishment state, section changes and report
 * notices arrive over NATS and HTTP (see {@code NatsSyncService}).
 *
 * <p>Every read either returns a definitive answer or says "unavailable" (an empty {@link Optional}), so
 * callers fall back to the backend over HTTP.</p>
 */
public final class RedisSyncService implements SharedCacheReader, ClientRuleNoticeChannel {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisSyncService.class);
    /** Bounds one shared-cache read, so a slow Redis can't stall tab completion. */
    private static final long SHARED_CACHE_TIMEOUT_MILLIS = 200;

    private final BlackholeConfig config;
    private final SyncCodec codec = new SyncCodec();
    private final List<Consumer<String>> clientRuleListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ClientRuleNotice>> clientRuleNoticeListeners = new CopyOnWriteArrayList<>();

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisPubSubConnection<String, String> pubSubConnection;

    public RedisSyncService(@NotNull BlackholeConfig config) {
        this.config = config;
    }

    /** Registers a listener for client rule changes (the changed rule's key); call before {@link #connect}. */
    public void onClientRuleChanged(@NotNull Consumer<String> listener) {
        this.clientRuleListeners.add(listener);
    }

    @Override
    public void onClientRuleNotice(@NotNull Consumer<ClientRuleNotice> listener) {
        this.clientRuleNoticeListeners.add(listener);
    }

    @Override
    public boolean publish(@NotNull ClientRuleNotice notice) {
        StatefulRedisConnection<String, String> conn = this.connection;
        if (conn == null) {
            return false;
        }
        try {
            return conn.sync().publish(RedisTopology.CLIENT_RULE_HIT_CHANNEL, ClientRuleNotice.encode(notice)) > 0;
        } catch (RuntimeException e) {
            LOGGER.warn("A client rule notice could not be published, delivering it on this proxy only: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Connects to Redis. A failure here is logged and leaves the service disconnected rather than
     * propagating - every proxy must keep working against the backend over plain HTTP.
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
            this.pubSubConnection.sync().subscribe(RedisTopology.CLIENT_RULE_CHANGED_CHANNEL, RedisTopology.CLIENT_RULE_HIT_CHANNEL);
            LOGGER.info("Connected to Redis for the shared tab-completion cache and client rules at {}", this.config.getRedisUri());
        } catch (RuntimeException e) {
            LOGGER.error("Failed to connect to Redis at {} - falling back to HTTP: {}", this.config.getRedisUri(), e.getMessage());
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
     * The backend's cached section list; empty if unavailable, so the registry uses the HTTP API.
     */
    public Optional<List<SectionInfo>> readCachedSections() {
        return readShared(RedisTopology.SECTIONS_ALL_KEY).flatMap(this.codec::sections);
    }

    /**
     * Reads one shared-cache key. Empty on a miss, when Redis isn't connected, and on an error or
     * timeout, so the caller falls back to the HTTP API.
     */
    @Override
    public Optional<String> readShared(@NotNull String key) {
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
        if (RedisTopology.CLIENT_RULE_CHANGED_CHANNEL.equals(channel)) {
            notifyAll(this.clientRuleListeners, message);
            return;
        }
        if (RedisTopology.CLIENT_RULE_HIT_CHANNEL.equals(channel)) {
            ClientRuleNotice.decode(message).ifPresentOrElse(
                    notice -> notifyAll(this.clientRuleNoticeListeners, notice),
                    () -> LOGGER.warn("Ignoring an unreadable client rule notice"));
        }
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
