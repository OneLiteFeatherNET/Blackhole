package net.onelitefeather.blackhole.backend.events;

import java.util.List;

/** Starts durable consumers on the event bus. */
public interface EventSubscriptionFactory {

    /**
     * Starts consuming. All instances that subscribe the same {@link EventConsumer} share the work:
     * each event is handled by exactly one of them.
     *
     * @param consumer the durable consumer to bind to
     * @param handler  receives each decoded event; the message is acknowledged after it returns
     * @return the running subscription
     */
    EventSubscription subscribe(EventConsumer consumer, EventHandler handler);

    /**
     * Starts a non-durable consumer for one replica's own use. Every replica that subscribes receives every event of the
     * types, and nothing is replayed: the consumer starts with new messages. The server deletes it once it has been idle for
     * a while, so a replica that is gone leaves nothing behind. A replica that misses events while it reconnects must catch
     * up by other means.
     *
     * @param eventTypes the event types delivered to this subscription
     * @param handler    receives each decoded event; the message is acknowledged after it returns
     * @return the running subscription
     */
    EventSubscription subscribeEphemeral(List<String> eventTypes, EventHandler handler);
}
