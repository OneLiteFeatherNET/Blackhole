package net.onelitefeather.blackhole.backend.events;

import io.micronaut.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** What happens to one pulled message: decode, hand to the handler, acknowledge. */
final class NatsDelivery {

    private static final Logger LOGGER = LoggerFactory.getLogger(NatsDelivery.class);

    private NatsDelivery() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * Decodes {@code body} and passes the event to {@code handler}. The message is acknowledged in every
     * case, after the handler returned or failed: a failure (or a body that is no event) is logged and
     * not retried, the way the bus is meant to work (a missed hint heals on the next mutation).
     */
    static void deliver(byte[] body, JsonMapper mapper, String consumerName, EventHandler handler, Runnable ack) {
        try {
            handler.handle(mapper.readValue(body, DomainEvent.class));
        } catch (Exception e) {
            LOGGER.error("Failed to process a domain event for {}: {}", consumerName, e.getMessage());
        } finally {
            ack.run();
        }
    }
}
