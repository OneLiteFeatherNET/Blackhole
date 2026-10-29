package net.onelitefeather.blackhole.backend.guard;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.areaban.AreaBanCache;
import net.onelitefeather.blackhole.backend.areaban.AreaBanService;
import net.onelitefeather.blackhole.backend.areaban.InMemoryAreaBanStore;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.guard.dto.GuardDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GuardServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC);

    private static GuardService service(InMemoryGuardStateStore states) {
        GuardStateCache cache = new GuardStateCache(states);
        GuardSettings settings = GuardSettings.of("STRICT", Duration.ofMinutes(20), "01:00-07:00", "Europe/Berlin",
                Duration.ofMinutes(5));
        GuardPresenceService presence = new GuardPresenceService(new InMemoryPresenceStore(), settings, CLOCK, cache);
        GuardTransitionService transitions = new GuardTransitionService(states, new InMemoryGuardChangeStore(), presence,
                new UnattendedEvaluator(settings), new DomainEventPublisher(new RecordingEventTransport(), CLOCK), cache, CLOCK);
        return new GuardService(states, transitions);
    }

    @Test
    void setLevelRefusesAnUnknownLevelAsAValidationError() {
        GuardDTO result = service(new InMemoryGuardStateStore(GuardState.initial(0L)))
                .setLevel(new GuardDTO.CreateRequest("NIGHTMARE"), "moderator-oid");

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(GuardDTO.Error.class, result, "an unknown level must be refused").kind(),
                "an unknown level is a validation error");
    }

    @Test
    void setLevelStoresTheManualLevelAndReturnsTheStateInForce() {
        GuardDTO result = service(new InMemoryGuardStateStore(GuardState.initial(0L)))
                .setLevel(new GuardDTO.CreateRequest("lockdown"), "moderator-oid");

        GuardDTO.Response state = assertInstanceOf(GuardDTO.Response.class, result, "a valid level must be stored");
        assertEquals("LOCKDOWN", state.effectiveLevel(), "the manual LOCKDOWN must be the level in force");
    }

    @Test
    void stateIsNotFoundWhenTheGuardRowIsMissing() {
        GuardDTO result = service(new InMemoryGuardStateStore(null)).state();

        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(GuardDTO.Error.class, result, "a missing row must be reported").kind(),
                "a missing guard row is not found");
    }

    @Test
    void aBurstBanAndAPublishedSummaryAreCountedOnTheirCounters() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RecordingEventTransport transport = new RecordingEventTransport();
        InMemoryAreaBanStore areas = new InMemoryAreaBanStore();
        AreaBanService bans = new AreaBanService(areas, new AreaBanCache(areas), new DomainEventPublisher(transport, CLOCK), CLOCK);
        InMemoryGuardRefusalStore refusalStore = new InMemoryGuardRefusalStore();
        GuardRefusalService refusals = new GuardRefusalService(refusalStore, bans,
                new BurstSettings(true, 1, Duration.ofMinutes(5), Duration.ofHours(2)), meters);

        refusals.recordRefusal(64500L, "Example Hosting", "DE", CLOCK.millis());
        new GuardSummaryService(refusalStore, new DomainEventPublisher(transport, CLOCK), CLOCK, meters).publish();

        assertEquals(1.0, meters.counter("blackhole.guard.bursts").count(), "one burst ban must be counted");
        assertEquals(1.0, meters.counter("blackhole.guard.summary.published").count(), "one published summary must be counted");
    }
}
