package net.onelitefeather.blackhole.backend.events;

import java.util.List;

/**
 * A named, durable consumer of the event bus and the event types it wants.
 *
 * @param name       the durable name, shared by every instance that takes part in the work
 * @param eventTypes the event types delivered to this consumer
 */
public record EventConsumer(String name, List<String> eventTypes) {

    public EventConsumer {
        eventTypes = List.copyOf(eventTypes);
    }
}
