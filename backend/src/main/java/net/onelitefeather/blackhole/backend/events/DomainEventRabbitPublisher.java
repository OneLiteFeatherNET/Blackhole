package net.onelitefeather.blackhole.backend.events;

import io.micronaut.rabbitmq.annotation.Binding;
import io.micronaut.rabbitmq.annotation.RabbitClient;

@RabbitClient(RabbitTopology.EVENTS_EXCHANGE)
public interface DomainEventRabbitPublisher {

    void publish(@Binding String routingKey, DomainEvent event);
}
