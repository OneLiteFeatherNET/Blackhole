package net.onelitefeather.blackhole.backend.guard;

import net.onelitefeather.blackhole.backend.support.StaffDelegations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.areaban.AreaBan;
import net.onelitefeather.blackhole.backend.areaban.AreaBanCache;
import net.onelitefeather.blackhole.backend.areaban.AreaBanService;
import net.onelitefeather.blackhole.backend.areaban.InMemoryAreaBanStore;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BurstRuleConflictTest {

    private static final long ASN = 64500L;
    private static final long START = Instant.parse("2026-10-29T12:00:00Z").toEpochMilli();

    @Test
    void aRefusalForAnAsnWithAnActiveBanCreatesNothingAndPublishesNothing() {
        InMemoryAreaBanStore areas = new InMemoryAreaBanStore();
        AreaBanCache cache = new AreaBanCache(areas);
        RecordingEventTransport transport = new RecordingEventTransport();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(START), ZoneOffset.UTC);
        AreaBanService bans = new AreaBanService(areas, cache, new DomainEventPublisher(transport, clock), clock, StaffDelegations.withoutLinks(clock), StaffDelegations.recorder(clock));
        GuardRefusalService service = new GuardRefusalService(new InMemoryGuardRefusalStore(), bans,
                new BurstSettings(true, 1, Duration.ofMinutes(5), Duration.ofHours(2)), new SimpleMeterRegistry());
        service.recordRefusal(ASN, "Example Hosting", "DE", START);
        int published = transport.published().size();

        service.recordRefusal(ASN, "Example Hosting", "DE", START + 1);

        assertEquals(published, transport.published().size(), "a second burst for a banned ASN must publish nothing");
        assertEquals(1, areas.listUnlifted().size(), "a second burst for a banned ASN must not create a second ban");
    }

    @Test
    void aLosingReplicaOfTheInsertRaceGetsNothingAndPublishesNothing() {
        RecordingEventTransport transport = new RecordingEventTransport();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(START), ZoneOffset.UTC);
        InMemoryAreaBanStore winner = new InMemoryAreaBanStore();
        winner.insert(new AreaBan(java.util.UUID.randomUUID(), AreaBan.Kind.ASN, "64500", "other replica",
                AreaBan.Source.AUTO_BURST, null, START, START + 1, null, null, null));
        // This replica's read saw no ban yet, so its insert is the one the partial unique index refuses.
        InMemoryAreaBanStore stale = new InMemoryAreaBanStore() {
            @Override
            public Optional<AreaBan> findUnlifted(AreaBan.Kind kind, String value) {
                return Optional.empty();
            }

            @Override
            public boolean insert(AreaBan ban) {
                return winner.insert(ban);
            }
        };
        AreaBanService bans = new AreaBanService(stale, new AreaBanCache(stale),
                new DomainEventPublisher(transport, clock), clock, StaffDelegations.withoutLinks(clock), StaffDelegations.recorder(clock));

        Optional<AreaBan> created = bans.createBurstBan(ASN, Duration.ofHours(2));

        assertFalse(created.isPresent(), "the replica that lost the insert must not report a ban");
        assertTrue(transport.published().isEmpty(), "the replica that lost the insert must not publish");
    }
}
