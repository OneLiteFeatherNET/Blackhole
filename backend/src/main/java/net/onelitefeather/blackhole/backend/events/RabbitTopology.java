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
     * Durable queue bound to {@link #EVENTS_EXCHANGE} with routing key {@code #} - every domain
     * event lands here so {@code WebhookDispatchConsumer} can fan it out to matching
     * {@code EventSubscriptionEntity} rows. Its dead-letter-exchange is
     * {@link #WEBHOOK_RETRY_EXCHANGE}, forming a TTL+DLX delayed-retry loop (native RabbitMQ
     * pattern, no extra plugin/library) with {@link #WEBHOOK_RETRY_QUEUE}.
     */
    public static final String WEBHOOK_DISPATCH_QUEUE = "blackhole.webhook.dispatch";

    /** Direct exchange a failed dispatch is dead-lettered to, to sit out its retry delay. */
    public static final String WEBHOOK_RETRY_EXCHANGE = "blackhole.webhook.retry";

    /** Routing key between {@link #WEBHOOK_RETRY_EXCHANGE} and {@link #WEBHOOK_RETRY_QUEUE}. */
    public static final String WEBHOOK_RETRY_ROUTING_KEY = "retry";

    /**
     * Holds a failed dispatch for its retry delay (via {@code x-message-ttl}), then dead-letters
     * back to {@link #WEBHOOK_DISPATCH_QUEUE} by name via the default exchange once the TTL
     * expires - the other half of the retry loop.
     */
    public static final String WEBHOOK_RETRY_QUEUE = "blackhole.webhook.retry.queue";

    /** Parking queue for dispatches that exhausted their retry budget. */
    public static final String WEBHOOK_FAILED_QUEUE = "blackhole.webhook.failed";

    /**
     * Durable queue bound to {@link #EVENTS_EXCHANGE} with routing key {@code signal.#} - feeds
     * {@code EloSignalConsumer}, which applies gameplay-ELO deltas for external anticheat
     * signals (Phase 4's {@code POST /signal}). A separate queue from
     * {@link #WEBHOOK_DISPATCH_QUEUE} since a topic exchange delivers independently to every
     * queue whose binding matches - same pattern as that queue coexisting with
     * {@link #CACHE_INVALIDATE_EXCHANGE}'s consumers.
     */
    public static final String ELO_SIGNAL_QUEUE = "blackhole.elo.signal";

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
