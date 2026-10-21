package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A {@link DomainEventPublisher} that records events instead of sending them to RabbitMQ.
 */
public final class RecordingEventPublisher extends DomainEventPublisher {

    /** One published event. */
    public record Event(String type, Map<String, Object> payload) {
    }

    private final List<Event> events = new ArrayList<>();

    public RecordingEventPublisher() {
        super(null);
    }

    @Override
    public void publish(String eventType, Map<String, Object> payload) {
        this.events.add(new Event(eventType, payload));
    }

    /** All events published so far, in order. */
    public List<Event> events() {
        return this.events;
    }

    /** The events of one type, in order. */
    public List<Event> eventsOfType(String type) {
        return this.events.stream().filter(event -> event.type().equals(type)).toList();
    }
}
