package net.onelitefeather.blackhole.backend.events;

import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;

import java.time.Duration;
import java.util.List;

/** The JetStream stream and durable consumers of the event bus, as pure configuration builders. */
public final class NatsStreams {

    /** The stream that captures {@link EventSubjects#ALL}. */
    public static final String STREAM = "BLACKHOLE_EVENTS";

    private static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(2);
    private static final Duration ACK_WAIT = Duration.ofSeconds(30);
    /** An ephemeral consumer that has not pulled for this long is deleted by the server. */
    private static final Duration EPHEMERAL_INACTIVE = Duration.ofSeconds(30);

    private NatsStreams() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * The stream: file storage, limits retention (three independent consumers read overlapping
     * subjects, so a work queue does not fit), old messages dropped when full, a duplicate window for
     * the {@code Nats-Msg-Id} dedupe and no single-message delete or purge.
     */
    public static StreamConfiguration stream(NatsSettings settings) {
        return StreamConfiguration.builder()
                .name(STREAM)
                .subjects(EventSubjects.ALL)
                .storageType(StorageType.File)
                .retentionPolicy(RetentionPolicy.Limits)
                .discardPolicy(DiscardPolicy.Old)
                .maxAge(settings.streamMaxAge())
                .maxBytes(settings.streamMaxBytes())
                .replicas(settings.streamReplicas())
                .duplicateWindow(DUPLICATE_WINDOW)
                .denyDelete(true)
                .denyPurge(true)
                .build();
    }

    /**
     * A durable pull consumer for the event types of {@code consumer}: explicit ack, one attempt per
     * message (the bus is a best-effort side channel), starting with new messages on first creation.
     */
    public static ConsumerConfiguration consumer(EventConsumer consumer) {
        List<String> filters = consumer.eventTypes().stream().map(EventSubjects::forEventType).toList();
        return ConsumerConfiguration.builder()
                .durable(consumer.name())
                .filterSubjects(filters)
                .ackPolicy(AckPolicy.Explicit)
                .ackWait(ACK_WAIT)
                .deliverPolicy(DeliverPolicy.New)
                .maxDeliver(1)
                .build();
    }

    /**
     * A non-durable pull consumer for one replica: no name, explicit ack, one attempt, starting with new messages. The server
     * deletes it after {@link #EPHEMERAL_INACTIVE} without a pull, so a replica that is gone (a rollout, a crash) leaves no
     * consumer behind.
     */
    public static ConsumerConfiguration ephemeral(List<String> eventTypes) {
        List<String> filters = eventTypes.stream().map(EventSubjects::forEventType).toList();
        return ConsumerConfiguration.builder()
                .filterSubjects(filters)
                .ackPolicy(AckPolicy.Explicit)
                .ackWait(ACK_WAIT)
                .deliverPolicy(DeliverPolicy.New)
                .maxDeliver(1)
                .inactiveThreshold(EPHEMERAL_INACTIVE)
                .build();
    }
}
