package net.onelitefeather.blackhole.backend.events;

import java.util.concurrent.CompletionStage;

/**
 * The publishing side of the domain event bus. The domain code depends on this port, never on a
 * broker client, so the broker can be swapped by replacing one adapter.
 */
public interface EventTransport {

    /**
     * Publishes one event. Must never block the calling thread (which may be a Netty event-loop
     * thread): the returned stage completes when the broker accepted the event, or fails.
     *
     * @param eventType the event type, the routing identity of the event
     * @param event     the envelope to publish
     * @return a stage that completes when the event was handed to the broker
     */
    CompletionStage<Void> publish(String eventType, DomainEvent event);
}
