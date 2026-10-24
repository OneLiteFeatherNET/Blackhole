package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.events.DomainEvent;
import net.onelitefeather.blackhole.backend.events.EventConsumer;
import net.onelitefeather.blackhole.backend.events.EventHandler;
import net.onelitefeather.blackhole.backend.events.EventSubscription;
import net.onelitefeather.blackhole.backend.events.EventSubscriptionFactory;

import java.util.ArrayList;
import java.util.List;

/** An {@link EventSubscriptionFactory} that keeps the handler so a test can deliver events directly. */
public class FakeEventSubscriptions implements EventSubscriptionFactory {

    private final List<EventConsumer> subscribed = new ArrayList<>();
    private EventHandler handler;
    private boolean closed;

    @Override
    public EventSubscription subscribe(EventConsumer consumer, EventHandler eventHandler) {
        this.subscribed.add(consumer);
        this.handler = eventHandler;
        return () -> this.closed = true;
    }

    /** Hands one event to the handler the way a broker adapter would. */
    public void deliver(DomainEvent event) throws Exception {
        this.handler.handle(event);
    }

    public List<EventConsumer> subscribed() {
        return this.subscribed;
    }

    public boolean closed() {
        return this.closed;
    }
}
