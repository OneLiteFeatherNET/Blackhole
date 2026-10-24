package net.onelitefeather.blackhole.backend.events;

import io.micronaut.json.JsonMapper;
import io.nats.client.JetStream;
import io.nats.client.Message;
import io.nats.client.PublishOptions;
import io.nats.client.impl.NatsMessage;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * {@link EventTransport} over JetStream: the envelope as JSON on {@code blackhole.events.<eventType>},
 * with {@code Nats-Msg-Id} set to the event id so a publish retry inside the stream's duplicate
 * window is stored once. {@code publishAsync} never blocks the caller (which may be a Netty
 * event-loop thread).
 */
@Singleton
public class NatsEventTransport implements EventTransport {

    private final NatsConnectionProvider connections;
    private final JsonMapper mapper;
    private final StreamProvisioner provisioner;

    public NatsEventTransport(NatsConnectionProvider connections, JsonMapper mapper, StreamProvisioner provisioner) {
        this.connections = connections;
        this.mapper = mapper;
        this.provisioner = provisioner;
    }

    static Message message(DomainEvent event, byte[] body) {
        return NatsMessage.builder()
                .subject(EventSubjects.forEventType(event.eventType()))
                .data(body)
                .build();
    }

    static PublishOptions publishOptions(DomainEvent event) {
        return PublishOptions.builder()
                .stream(NatsStreams.STREAM)
                .messageId(event.eventId())
                .build();
    }

    @Override
    public CompletionStage<Void> publish(String eventType, DomainEvent event) {
        try {
            Message message = message(event, this.mapper.writeValueAsBytes(event));
            JetStream jetStream = this.connections.connection().jetStream();
            CompletableFuture<Void> result = jetStream.publishAsync(message, publishOptions(event)).thenApply(ack -> null);
            result.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    // The stream may be missing (NATS was down at startup): check it again, off this thread.
                    this.provisioner.markStale();
                    this.provisioner.ensureStreamInBackground();
                }
            });
            return result;
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
