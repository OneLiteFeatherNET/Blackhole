package net.onelitefeather.blackhole.backend.events;

import io.micronaut.core.convert.ConversionService;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stream and consumer settings must equal the table in design D4. */
class NatsStreamsTest {

    private static final NatsSettings SETTINGS = new NatsSettings(
            "nats://localhost:4222", "", "blackhole-backend", Duration.ofSeconds(5), 3, Duration.ofDays(7), 2_147_483_648L);

    @Test
    void theStreamCapturesAllEventSubjectsOnDisk() {
        StreamConfiguration stream = NatsStreams.stream(SETTINGS);

        assertEquals("BLACKHOLE_EVENTS", stream.getName());
        assertEquals(List.of("blackhole.events.>"), stream.getSubjects());
        assertEquals(StorageType.File, stream.getStorageType());
        assertEquals(RetentionPolicy.Limits, stream.getRetentionPolicy());
        assertEquals(DiscardPolicy.Old, stream.getDiscardPolicy());
    }

    @Test
    void theStreamIsBoundedByAgeBytesAndReplicasFromTheSettings() {
        StreamConfiguration stream = NatsStreams.stream(SETTINGS);

        assertEquals(Duration.ofDays(7), stream.getMaxAge());
        assertEquals(2_147_483_648L, stream.getMaxBytes());
        assertEquals(3, stream.getReplicas());
    }

    @Test
    void theStreamDedupesForTwoMinutesAndCannotBeEditedMessageByMessage() {
        StreamConfiguration stream = NatsStreams.stream(SETTINGS);

        assertEquals(Duration.ofMinutes(2), stream.getDuplicateWindow());
        assertTrue(stream.getDenyDelete(), "deny_delete");
        assertTrue(stream.getDenyPurge(), "deny_purge");
    }

    @Test
    void theRedisSyncConsumerFiltersTheFiveSubjectsAndAcksExplicitly() {
        ConsumerConfiguration consumer = NatsStreams.consumer(EventConsumers.REDIS_SYNC);

        assertEquals("blackhole-redis-sync", consumer.getDurable());
        assertEquals(List.of(
                "blackhole.events.punishment.created",
                "blackhole.events.punishment.updated",
                "blackhole.events.punishment.expired",
                "blackhole.events.punishment.revoked",
                "blackhole.events.appeal.resolved"), consumer.getFilterSubjects());
        assertEquals(AckPolicy.Explicit, consumer.getAckPolicy());
        assertEquals(Duration.ofSeconds(30), consumer.getAckWait());
        assertEquals(DeliverPolicy.New, consumer.getDeliverPolicy());
        assertEquals(1, consumer.getMaxDeliver(), "a single attempt, as the best-effort Redis sync wants");
    }

    @Test
    void anIsoDurationFromTheEnvironmentIsUnderstoodByTheConfigurationConversion() {
        Duration parsed = ConversionService.SHARED.convert("P7D", Duration.class).orElseThrow();

        assertEquals(Duration.ofDays(7), parsed);
    }
}
