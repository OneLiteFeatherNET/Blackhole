package net.onelitefeather.blackhole.backend.events;

/**
 * Well-known exchange names shared between publishers, consumers, and {@link BlackholeRabbitTopology}.
 */
public final class RabbitTopology {

    /** Topic exchange for domain events (routing key = event type, e.g. {@code punishment.created}). */
    public static final String EVENTS_EXCHANGE = "blackhole.events";

    private RabbitTopology() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
