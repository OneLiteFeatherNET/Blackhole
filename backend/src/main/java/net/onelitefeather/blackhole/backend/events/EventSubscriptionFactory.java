package net.onelitefeather.blackhole.backend.events;

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
}
