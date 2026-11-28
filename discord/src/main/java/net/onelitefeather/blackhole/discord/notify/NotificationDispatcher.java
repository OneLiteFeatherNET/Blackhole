package net.onelitefeather.blackhole.discord.notify;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Between the broker and the handler: parses a message body and decides what to do with it, so the
 * NATS adapter stays a thin ack/nak shell and this is tested without a broker.
 */
public final class NotificationDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationHandler handler;
    private final GuardNotificationHandler guard;

    public NotificationDispatcher(@NotNull NotificationHandler handler, @NotNull GuardNotificationHandler guard) {
        this.handler = handler;
        this.guard = guard;
    }

    /** Never throws: an unexpected failure is retried, a message that is not an event is dropped. */
    public @NotNull NotificationHandler.Outcome onMessage(byte[] body) {
        var guardEvent = GuardEvent.parse(body);
        if (guardEvent.isPresent()) {
            try {
                return this.guard.handle(guardEvent.get());
            } catch (RuntimeException e) {
                LOGGER.error("Handling guard event {} failed unexpectedly", guardEvent.get().eventId(), e);
                return NotificationHandler.Outcome.RETRY;
            }
        }
        var event = NotificationEvent.parse(body);
        if (event.isEmpty()) {
            LOGGER.warn("Dropping a message that is not a report, appeal or guard event");
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
