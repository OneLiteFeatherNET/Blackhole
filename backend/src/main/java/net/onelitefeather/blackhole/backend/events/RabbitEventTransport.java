package net.onelitefeather.blackhole.backend.events;

import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.concurrent.CompletionStage;

/** {@link EventTransport} over the RabbitMQ topic exchange {@code blackhole.events}. */
@Singleton
public class RabbitEventTransport implements EventTransport {

    private final DomainEventRabbitPublisher publisher;

    public RabbitEventTransport(DomainEventRabbitPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public CompletionStage<Void> publish(String eventType, DomainEvent event) {
        return Mono.from(this.publisher.publish(eventType, event)).then().toFuture();
    }
}
