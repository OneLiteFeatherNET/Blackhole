package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;

import java.util.Map;

/**
 * A {@link DomainEventPublisher} that drops events instead of sending them to RabbitMQ.
 */
public final class RecordingEventPublisher extends DomainEventPublisher {

    public RecordingEventPublisher() {
        super(null);
    }

    @Override
    public void publish(String eventType, Map<String, Object> payload) {
        // events are not under test here
    }
}
