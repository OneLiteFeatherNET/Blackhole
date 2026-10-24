package net.onelitefeather.blackhole.backend.events;

/** Receives the decoded events of one {@link EventSubscription}. */
@FunctionalInterface
public interface EventHandler {

    /**
     * Handles one event. The subscription acknowledges the message once this returns or throws
     * (a throw is logged by the adapter); the bus is a best-effort side channel.
     */
    void handle(DomainEvent event) throws Exception;
}
