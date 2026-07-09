package net.onelitefeather.blackhole.backend.events;

/**
 * Well-known exchange names shared between publishers, consumers, and {@link BlackholeRabbitTopology}.
 */
public final class RabbitTopology {

    /** Topic exchange for domain events (routing key = event type, e.g. {@code punishment.created}). */
    public static final String EVENTS_EXCHANGE = "blackhole.events";

    /** Fanout exchange: every backend replica invalidates its own local profile cache. */
    public static final String CACHE_INVALIDATE_EXCHANGE = "blackhole.cache.invalidate";

    /**
     * Durable queue bound to {@link #EVENTS_EXCHANGE} with routing keys {@code punishment.created},
     * {@code punishment.expired}, {@code punishment.revoked} and {@code appeal.resolved} - feeds
     * {@code RedisSyncConsumer}, which mirrors active-punishment state into Redis so every
     * Velocity proxy in a multi-proxy network sees consistent ban/mute state without querying
     * this API on every login/chat message. A single shared queue (not a per-replica one like
     * {@link #CACHE_INVALIDATE_EXCHANGE}'s consumers): Redis is the one cross-proxy source of
     * truth, so each event must be processed exactly once, not by every backend replica.
     */
    public static final String REDIS_SYNC_QUEUE = "blackhole.redis.sync";

    private RabbitTopology() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
