package net.onelitefeather.blackhole.backend.firewall;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.events.DomainEvent;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.events.EventTransport;
import net.onelitefeather.blackhole.backend.ipban.IpBanEvents;
import net.onelitefeather.blackhole.backend.ipban.IpBanFixture;
import net.onelitefeather.blackhole.backend.ipban.IpBanProtection;
import net.onelitefeather.blackhole.backend.ipban.IpBanService;
import net.onelitefeather.blackhole.backend.ipban.dto.IpBanDTO;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two replicas, each with its own feed cache, over one ban table. Replica A writes through its service. The events A
 * publishes are kept in a list, so a test decides whether replica B receives them (the event) or not (a lost event, which
 * only the marker catches).
 */
class FeedReplicasTest {

    private static final long NOW = 1_780_000_000_000L;
    private static final String BANNED = "203.0.113.7";

    /** An event bus that keeps what was published, so a test can deliver an event to another replica or drop it. */
    static final class Bus implements EventTransport {

        final List<DomainEvent> published = new ArrayList<>();

        @Override
        public CompletableFuture<Void> publish(String eventType, DomainEvent event) {
            this.published.add(event);
            return CompletableFuture.completedFuture(null);
        }
    }

    private final FeedTestStore store = new FeedTestStore();
    private final FeedTestClock clock = new FeedTestClock(NOW);
    private final Bus bus = new Bus();
    private final FeedCache replicaA = cache();
    private final FeedCache replicaB = cache();
    private final IpBanService writerOnA = new IpBanService(this.store, new IpBanProtection(""),
            new IpBanEvents(new DomainEventPublisher(this.bus, this.clock)), this.replicaA, this.clock, Duration.ofDays(30));

    private FeedCache cache() {
        return new FeedCache(this.store, new IpBanProtection(""), List.of(), this.clock, new SimpleMeterRegistry());
    }

    private static String ipsBody(FeedCache cache) {
        Optional<FeedCache.Built> built = cache.current();
        return new String(built.orElseThrow().ips().withoutComments().body(), StandardCharsets.US_ASCII);
    }

    private static IpBanDTO.CreateRequest banOf(String address) {
        return new IpBanDTO.CreateRequest(address, "Bot wave", null, null, null);
    }

    private DomainEvent changeEvent() {
        return this.bus.published.stream().filter(event -> IpBanEvents.CHANGED.equals(event.eventType())).findFirst()
                .orElseThrow(() -> new AssertionError("the write published no ipban.changed event"));
    }

    @Test
    void aBanWrittenOnAnotherReplicaReachesThisFeedOnItsNextTickWithoutAnEvent() {
        ipsBody(this.replicaB);
        this.writerOnA.create(banOf(BANNED), IpBanFixture.admin());
        this.bus.published.clear();

        assertFalse(ipsBody(this.replicaB).contains(BANNED), "without a tick or an event, replica B still serves its old feed");

        this.replicaB.tick();

        assertTrue(ipsBody(this.replicaB).contains(BANNED), "the marker changed, so replica B rebuilt on its tick");
    }

    @Test
    void aLiftOnAnotherReplicaDropsTheBanFromThisFeedOnItsNextTick() {
        this.writerOnA.create(banOf(BANNED), IpBanFixture.admin());
        String banId = changeEvent().payload().get("banId").toString();
        ipsBody(this.replicaB);
        this.writerOnA.lift(banId, new IpBanDTO.LiftRequest("False positive"), IpBanFixture.admin());

        this.replicaB.tick();

        assertFalse(ipsBody(this.replicaB).contains(BANNED), "the lift moved the marker, so replica B dropped the ban on its tick");
    }

    @Test
    void anIpBanEventRebuildsAnotherReplicasFeedWithoutWaitingForAnyTick() {
        ipsBody(this.replicaB);
        CapturingSubscriptions subscriptions = new CapturingSubscriptions();
        new FeedChangeConsumer(subscriptions, this.replicaB).start();
        this.writerOnA.create(banOf(BANNED), IpBanFixture.admin());

        subscriptions.deliver(changeEvent());

        assertTrue(ipsBody(this.replicaB).contains(BANNED), "the delivered event marked replica B dirty and it rebuilt at once");
    }

    @Test
    void twoQuietTicksReadNoBanRowsAndOnlyTheMarker() {
        this.writerOnA.create(banOf(BANNED), IpBanFixture.admin());
        ipsBody(this.replicaB);
        int rowReads = this.store.activeReads.get();
        int markerReads = this.store.markerReads.get();

        this.replicaB.tick();
        this.replicaB.tick();

        assertEquals(rowReads, this.store.activeReads.get(), "a quiet tick reads no ban rows");
        assertEquals(markerReads + 2, this.store.markerReads.get(), "each quiet tick reads the marker exactly once");
    }
}
