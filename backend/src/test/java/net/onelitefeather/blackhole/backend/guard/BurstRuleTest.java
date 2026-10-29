package net.onelitefeather.blackhole.backend.guard;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BurstRuleTest {

    private static final long ASN = 64500L;
    private static final long START = Instant.parse("2026-10-29T12:00:00Z").toEpochMilli();

    private final InMemoryAreaBanStore areas = new InMemoryAreaBanStore();
    private final AreaBanCache cache = new AreaBanCache(this.areas);
    private final RecordingEventTransport transport = new RecordingEventTransport();
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(START), ZoneOffset.UTC);

    private GuardRefusalService service(boolean enabled) {
        AreaBanService bans = new AreaBanService(this.areas, this.cache,
                new DomainEventPublisher(this.transport, this.clock), this.clock);
        return new GuardRefusalService(new InMemoryGuardRefusalStore(), bans,
                new BurstSettings(enabled, 10, Duration.ofMinutes(5), Duration.ofHours(2)));
    }

    private List<AreaBan> bans() {
        return this.areas.listUnlifted();
    }

    @Test
    void withTheRuleDisabledNoAutomaticBanIsCreated() {
        GuardRefusalService service = service(false);

        for (int i = 0; i < 100; i++) {
            service.recordRefusal(ASN, "Example Hosting", "DE", START + i);
        }

        assertTrue(bans().isEmpty(), "with the burst rule off, refusals must never create an area ban");
    }

    @Test
    void tenRefusalsWithinFiveMinutesCreateAnAutomaticBanOfTwoHours() {
        GuardRefusalService service = service(true);

        for (int i = 0; i < 10; i++) {
            service.recordRefusal(ASN, "Example Hosting", "DE", START + i * 1000L);
        }

        AreaBan ban = bans().getFirst();
        assertEquals(AreaBan.Source.AUTO_BURST, ban.source(), "the ban must be marked as automatic");
        assertEquals(START + Duration.ofHours(2).toMillis(), ban.expiresAt(), "the automatic ban must last two hours");
    }

    @Test
    void tenRefusalsSpreadOverSixMinutesDoNotCreateABan() {
        GuardRefusalService service = service(true);

        for (int i = 0; i < 10; i++) {
            service.recordRefusal(ASN, "Example Hosting", "DE", START + i * 40_000L);
        }

        assertTrue(bans().isEmpty(), "ten refusals spread over more than the five minute window must not create a ban");
    }
}
