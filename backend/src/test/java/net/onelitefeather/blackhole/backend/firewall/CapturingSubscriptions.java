package net.onelitefeather.blackhole.backend.firewall;

import net.onelitefeather.blackhole.backend.events.DomainEvent;
import net.onelitefeather.blackhole.backend.events.EventConsumer;
import net.onelitefeather.blackhole.backend.events.EventHandler;
import net.onelitefeather.blackhole.backend.events.EventSubscription;
import net.onelitefeather.blackhole.backend.events.EventSubscriptionFactory;

import java.util.List;

/**
 * A subscription factory that keeps the event types and the handler it was given, so a test delivers events by hand, as the
 * broker would. It runs no thread.
 */
final class CapturingSubscriptions implements EventSubscriptionFactory {

    List<String> eventTypes;
    EventHandler handler;

    @Override
    public EventSubscription subscribe(EventConsumer consumer, EventHandler handler) {
        throw new AssertionError("the feed change consumer must not use a durable consumer");
    }

    @Override
    public EventSubscription subscribeEphemeral(List<String> eventTypes, EventHandler handler) {
        this.eventTypes = eventTypes;
        this.handler = handler;
        return () -> { };
    }

    /** Hands one event to the captured handler, as the broker would. */
    void deliver(DomainEvent event) {
        try {
            this.handler.handle(event);
        } catch (Exception e) {
            throw new AssertionError("the event handler failed", e);
        }
    }
}
