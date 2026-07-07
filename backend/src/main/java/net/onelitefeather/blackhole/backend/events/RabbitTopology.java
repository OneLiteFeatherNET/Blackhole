package net.onelitefeather.blackhole.backend.events;

/**
 * Well-known exchange names shared between publishers, consumers, and {@link BlackholeRabbitTopology}.
 */
public final class RabbitTopology {

    /** Topic exchange for domain events (routing key = event type, e.g. {@code punishment.created}). */
    public static final String EVENTS_EXCHANGE = "blackhole.events";

    /** Fanout exchange: every backend replica invalidates its own local profile cache. */
    public static final String CACHE_INVALIDATE_EXCHANGE = "blackhole.cache.invalidate";

    private RabbitTopology() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
