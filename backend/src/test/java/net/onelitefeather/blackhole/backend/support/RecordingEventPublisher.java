package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link DomainEventPublisher} over an in-memory {@link RecordingEventTransport}: it records
 * events instead of sending them to a broker.
 */
public class RecordingEventPublisher extends DomainEventPublisher {

    /** One published event. */
    public record Event(String type, Map<String, Object> payload) {
    }

    private final List<Event> events;

    public RecordingEventPublisher() {
        this(new CopyOnWriteArrayList<>());
    }

    private RecordingEventPublisher(List<Event> events) {
        super(new RecordingEventTransport(published ->
                events.add(new Event(published.eventType(), published.event().payload()))), Clock.systemUTC());
        this.events = events;
    }

    /** All events published so far, in order (mutable, so a test can clear it). */
    public List<Event> events() {
        return this.events;
    }

    /** The events of one type, in order. */
    public List<Event> eventsOfType(String type) {
        return this.events.stream().filter(event -> event.type().equals(type)).toList();
    }
}
