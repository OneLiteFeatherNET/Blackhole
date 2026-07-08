package net.onelitefeather.blackhole.velocity.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import net.onelitefeather.blackhole.client.api.PunishProfileApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.velocity.component.PunishmentTemplateComponent;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Read-only mirror of the backend's Redis-cached active-punishment state (written solely by the
 * backend's {@code RedisSyncConsumer}). Backs two things:
 *
 * <ul>
 *     <li>a login/chat fast path that avoids an HTTP round-trip to the backend on every check
 *     ({@link #fetchAndTrack} / {@link #getChatBanFast}), seeded from Redis or, on a cache miss,
 *     from the caller's own HTTP fallback via {@link #seedChatBan};</li>
 *     <li>immediate cross-proxy enforcement: when a ban is applied on a different proxy while a
 *     player is connected to this one, the pub/sub delta received here disconnects them right
 *     away instead of waiting for their next login.</li>
 * </ul>
 *
 * <p>Every public lookup either returns a definitive answer or throws, so callers can catch and
 * fall back to the pre-existing HTTP check - Redis being unreachable must never make a
 * login/chat check fail outright.</p>
 */
public final class RedisSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisSyncService.class);

    private final ProxyServer proxyServer;
    private final BlackholeConfig config;
    private final PunishProfileApi punishProfileApi;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Absent key = never looked up this session; {@code Optional.empty()} = confirmed inactive. */
    private final ConcurrentMap<String, Optional<PunishmentSyncMessage>> banCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Optional<PunishmentSyncMessage>> chatBanCache = new ConcurrentHashMap<>();

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisPubSubConnection<String, String> pubSubConnection;

    @Inject
    public RedisSyncService(@NotNull ProxyServer proxyServer, @NotNull BlackholeConfig config, @NotNull ApiClient apiClient) {
        this.proxyServer = proxyServer;
        this.config = config;
        this.punishProfileApi = new PunishProfileApi(apiClient);
    }

    /**
     * Connects to Redis and subscribes to the punishment-sync channel. A failure here is logged
     * and leaves the service disconnected rather than propagating - every proxy must keep working
     * against the backend over plain HTTP if Redis is unavailable at startup.
     */
    public void connect() {
        try {
            this.client = RedisClient.create(this.config.getRedisUri());
            this.connection = this.client.connect();
            this.pubSubConnection = this.client.connectPubSub();
            this.pubSubConnection.addListener(new RedisPubSubAdapter<String, String>() {
                @Override
                public void message(String channel, String message) {
                    onMessage(message);
                }
            });
            this.pubSubConnection.sync().subscribe(RedisTopology.PUNISHMENT_SYNC_CHANNEL);
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
     * Reads both the ban and chat-ban keys for {@code ownerHash} and seeds both local caches from
     * the result, so the chat check right after login is a guaranteed cache hit.
     *
     * @return the active ban, if any
     * @throws IllegalStateException if Redis isn't connected - callers must fall back to HTTP
     */
    public Optional<PunishmentSyncMessage> fetchAndTrack(@NotNull String ownerHash) {
        requireConnected();
        RedisCommands<String, String> sync = this.connection.sync();
        Optional<PunishmentSyncMessage> ban = parse(sync.get(RedisTopology.banKey(ownerHash)));
        Optional<PunishmentSyncMessage> chat = parse(sync.get(RedisTopology.chatBanKey(ownerHash)));
        this.banCache.put(ownerHash, ban);
        this.chatBanCache.put(ownerHash, chat);
        return ban;
    }

    /**
     * @return {@code null} if this player's mute state was never looked up this session (caller
     * must fall back to HTTP and then call {@link #seedChatBan}), otherwise a definitive answer
     */
    public @Nullable Optional<PunishmentSyncMessage> getChatBanFast(@NotNull String ownerHash) {
        return this.chatBanCache.get(ownerHash);
    }

    public void seedChatBan(@NotNull String ownerHash, @NotNull Optional<PunishmentSyncMessage> value) {
        this.chatBanCache.put(ownerHash, value);
    }

    @Subscribe
    public void onDisconnect(@NotNull DisconnectEvent event) {
        String ownerHash = UUIDConverter.convertToSHA(event.getPlayer().getUniqueId());
        this.banCache.remove(ownerHash);
        this.chatBanCache.remove(ownerHash);
    }

    private void onMessage(String rawJson) {
        PunishmentSyncMessage message;
        try {
            message = this.objectMapper.readValue(rawJson, PunishmentSyncMessage.class);
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse incoming punishment sync message: {}", e.getMessage());
            return;
        }
        boolean active = PunishmentSyncMessage.STATE_SET.equals(message.state());
        Optional<PunishmentSyncMessage> value = active ? Optional.of(message) : Optional.empty();

        if (PunishmentSyncMessage.SLOT_CHAT_BAN.equals(message.slot())) {
            this.chatBanCache.put(message.owner(), value);
            return;
        }

        this.banCache.put(message.owner(), value);
        if (active) {
            kickIfConnectedHere(message);
        }
    }

    /**
     * A ban was just applied on (possibly) another proxy. The owner hash is one-way, so the only
     * way to tell whether the affected player is connected to this proxy is to hash every
     * currently-online player and compare - the same thing {@code PlayerLoginListener}/
     * {@code PlayerChatListener} already do per-player, just run here across the online list.
     */
    private void kickIfConnectedHere(PunishmentSyncMessage message) {
        for (Player player : this.proxyServer.getAllPlayers()) {
            if (!UUIDConverter.convertToSHA(player.getUniqueId()).equals(message.owner())) {
                continue;
            }
            try {
                PunishProfileDTO profile = this.punishProfileApi.getById(message.owner());
                PunishEntryDTO activeBan = profile.getActiveBan();
                if (activeBan != null) {
                    player.disconnect(PunishmentTemplateComponent.of(activeBan.getTemplate(), profile));
                }
            } catch (ApiException e) {
                LOGGER.error("Failed to fetch profile for cross-proxy kick of {}: {}", player.getUsername(), e.getMessage());
            }
            return;
        }
    }

    private Optional<PunishmentSyncMessage> parse(@Nullable String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(this.objectMapper.readValue(json, PunishmentSyncMessage.class));
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse cached punishment sync value: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void requireConnected() {
        if (this.connection == null) {
            throw new IllegalStateException("Redis is not connected");
        }
    }
}
