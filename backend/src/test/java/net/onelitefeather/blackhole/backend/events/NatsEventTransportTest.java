package net.onelitefeather.blackhole.backend.events;

import io.nats.client.Message;
import io.nats.client.PublishOptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** How an envelope is turned into a JetStream message (no connection needed). */
class NatsEventTransportTest {

    private static final DomainEvent EVENT = new DomainEvent("2f1c1a5e-0000-4000-8000-000000000001", "report.created", 9L, 1,
            Map.of("owner", "abc"));

    @Test
    void theMessageGoesToTheSubjectOfTheEventTypeWithTheEncodedEnvelope() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        Message message = NatsEventTransport.message(EVENT, body);

        assertEquals("blackhole.events.report.created", message.getSubject());
        assertArrayEquals(body, message.getData());
    }

    @Test
    void theMessageIdForDedupeIsTheEventId() {
        PublishOptions options = NatsEventTransport.publishOptions(EVENT);

        assertEquals("2f1c1a5e-0000-4000-8000-000000000001", options.getMessageId());
        assertEquals(NatsStreams.STREAM, options.getStream(), "the server checks the subject is captured by the stream");
    }

    @Test
    void anEventTypeThatCannotBeASubjectIsRefusedBeforeAnythingIsSent() {
        DomainEvent hostile = new DomainEvent("id", "report.*", 9L, 1, Map.of());

        assertThrows(IllegalArgumentException.class, () -> NatsEventTransport.message(hostile, new byte[0]));
    }
}
