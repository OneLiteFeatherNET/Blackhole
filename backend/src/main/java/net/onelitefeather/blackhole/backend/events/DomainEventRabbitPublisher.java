package net.onelitefeather.blackhole.backend.events;

import io.micronaut.rabbitmq.annotation.Binding;
import io.micronaut.rabbitmq.annotation.RabbitClient;
import org.reactivestreams.Publisher;

/**
 * Publishes to the domain event exchange. The return type is deliberately reactive: a {@code void}
 * client method is implemented by blocking on the publish, and Reactor refuses that on a Netty
 * event-loop thread, which is where controllers and the services they call run. The returned
 * publisher is cold; {@link DomainEventPublisher} subscribes to it.
 */
@RabbitClient(RabbitTopology.EVENTS_EXCHANGE)
public interface DomainEventRabbitPublisher {

    Publisher<Void> publish(@Binding String routingKey, DomainEvent event);
}
