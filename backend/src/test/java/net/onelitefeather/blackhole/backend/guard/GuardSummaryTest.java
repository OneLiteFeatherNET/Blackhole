package net.onelitefeather.blackhole.backend.guard;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardSummaryTest {

    private static final Instant START = Instant.parse("2026-10-29T12:00:00Z");

    private final InMemoryGuardRefusalStore store = new InMemoryGuardRefusalStore();
    private final RecordingEventTransport transport = new RecordingEventTransport();
    private final Clock clock = Clock.fixed(START, ZoneOffset.UTC);
    private final GuardSummaryService summary = new GuardSummaryService(this.store,
            new DomainEventPublisher(this.transport, this.clock), this.clock, new SimpleMeterRegistry());

    @Test
    void aWindowWithRefusalsPublishesOneSummaryWithTheTopAsnsInOrder() {
        store.recordRefusal(1L, "Small", "DE");
        store.recordRefusal(2L, "Large", "US");
        store.recordRefusal(2L, "Large", "US");

        summary.publish();

        assertEquals(1, transport.published().size(), "one summary must be published for the window");
        Map<String, Object> payload = transport.published().getFirst().event().payload();
        assertEquals(3L, payload.get("total"), "the total must be the sum of the refusals");
        List<?> top = (List<?>) payload.get("top");
        assertEquals("2", ((Map<?, ?>) top.getFirst()).get("asn"), "the ASN with the most refusals must come first");
    }

    @Test
    void anEmptyWindowPublishesNothing() {
        summary.publish();

        assertTrue(transport.published().isEmpty(), "a window with no refusals must not publish");
    }

    @Test
    void theCountersAreClearedAfterPublishing() {
        store.recordRefusal(1L, "Small", "DE");
        summary.publish();

        summary.publish();

        assertEquals(1, transport.published().size(), "a second publish must not repeat the cleared window");
    }
}
