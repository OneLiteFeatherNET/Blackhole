package net.onelitefeather.blackhole.discord.notify;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Between the broker and the handler: parses a message body and decides what to do with it, so the
 * RabbitMQ adapter stays a thin ack/nack shell and this is tested without a broker.
 */
public final class NotificationDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationHandler handler;

    public NotificationDispatcher(@NotNull NotificationHandler handler) {
        this.handler = handler;
    }

    /** Never throws: an unexpected failure is retried, a message that is not an event is dropped. */
    public @NotNull NotificationHandler.Outcome onMessage(byte[] body) {
        var event = NotificationEvent.parse(body);
        if (event.isEmpty()) {
            LOGGER.warn("Dropping a message that is not a report or appeal event");
            return NotificationHandler.Outcome.DROP;
        }
        try {
            return this.handler.handle(event.get());
        } catch (RuntimeException e) {
            LOGGER.error("Handling event {} failed unexpectedly", event.get().eventId(), e);
            return NotificationHandler.Outcome.RETRY;
        }
    }
}
