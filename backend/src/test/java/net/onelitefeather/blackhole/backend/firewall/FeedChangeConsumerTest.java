package net.onelitefeather.blackhole.backend.firewall;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.events.DomainEvent;
import net.onelitefeather.blackhole.backend.ipban.IpBanEvents;
import net.onelitefeather.blackhole.backend.ipban.IpBanProtection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeedChangeConsumerTest {

    private static final long NOW = 1_780_000_000_000L;

    private static FeedCache cache(FeedTestStore store) {
        return new FeedCache(store, new IpBanProtection(""), List.of(), new FeedTestClock(NOW), new SimpleMeterRegistry());
    }

    private static DomainEvent event(String eventType) {
        return new DomainEvent(UUID.randomUUID().toString(), eventType, NOW, 1, Map.of("banId", UUID.randomUUID().toString()));
    }

    @Test
    void theReplicaSubscribesEphemerallyToTheIpBanChangeEventOnly() {
        CapturingSubscriptions subscriptions = new CapturingSubscriptions();

        new FeedChangeConsumer(subscriptions, cache(new FeedTestStore())).start();

        assertEquals(List.of(IpBanEvents.CHANGED), subscriptions.eventTypes,
                "the feed is rebuilt by the change event alone, on an ephemeral subscription that needs no replica name");
    }

    @Test
    void anEventOfAnotherTypeLeavesTheFeedAlone() {
        FeedTestStore store = new FeedTestStore();
        CapturingSubscriptions subscriptions = new CapturingSubscriptions();
        new FeedChangeConsumer(subscriptions, cache(store)).start();

        subscriptions.deliver(event("punishment.created"));

        assertEquals(0, store.activeReads.get(), "an unrelated event does not rebuild the feed");
    }
}
