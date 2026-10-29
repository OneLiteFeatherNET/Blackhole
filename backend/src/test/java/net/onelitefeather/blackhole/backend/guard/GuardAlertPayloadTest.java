package net.onelitefeather.blackhole.backend.guard;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.areaban.AreaBan;
import net.onelitefeather.blackhole.backend.areaban.AreaBanEvents;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The payloads of the alerting events are whitelists: no address, no owner hash, no player name. */
class GuardAlertPayloadTest {

    private static final String IP = "192.0.2.10";
    private static final String OWNER = "e".repeat(128);
    private static final String PLAYER_NAME = "Steve";
    private static final Set<String> AREA_BAN_KEYS = Set.of("kind", "value", "source", "createdAt", "expiresAt", "liftedAt");
    /** A created ban also carries its reason and the display name of the staff member it is for. */
    private static final Set<String> CREATED_KEYS = Set.of("kind", "value", "reason", "source", "createdAt", "expiresAt", "actorName");

    private static AreaBan burstBan() {
        return new AreaBan(UUID.randomUUID(), AreaBan.Kind.ASN, "64500", "burst", AreaBan.Source.AUTO_BURST, null,
                1_000L, 2_000L, null, null, null);
    }

    @Test
    void theAreaBanCreatedPayloadHoldsOnlyTheWhitelistedKeys() {
        Map<String, Object> payload = AreaBanEvents.created(burstBan(), null);

        assertTrue(CREATED_KEYS.containsAll(payload.keySet()), "the created payload must hold only whitelisted keys: " + payload.keySet());
    }

    @Test
    void theAreaBanCreatedPayloadNamesTheStaffMemberItIsFor() {
        Map<String, Object> payload = AreaBanEvents.created(burstBan(), "Staff Name");

        assertEquals("Staff Name", payload.get("actorName"), "the created payload must carry the delegated staff display name");
    }

    @Test
    void theAreaBanLiftedPayloadHoldsOnlyTheWhitelistedKeys() {
        Map<String, Object> payload = AreaBanEvents.lifted(burstBan().lifted(1_500L, "reason", "admin"));

        assertTrue(AREA_BAN_KEYS.containsAll(payload.keySet()), "the lifted payload must hold only whitelisted keys: " + payload.keySet());
    }

    @Test
    void noAlertPayloadCarriesAnAddressAnOwnerHashOrAPlayerName() {
        RecordingEventTransport transport = new RecordingEventTransport();
        Clock clock = Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC);
        InMemoryGuardRefusalStore refusals = new InMemoryGuardRefusalStore();
        refusals.recordRefusal(64500L, "Example Hosting", "DE");
        new GuardSummaryService(refusals, new DomainEventPublisher(transport, clock), clock, new SimpleMeterRegistry()).publish();
        new DomainEventPublisher(transport, clock).publish("area_ban.created", AreaBanEvents.created(burstBan(), null));

        for (RecordingEventTransport.Published published : transport.published()) {
            String rendered = published.event().payload().toString();
            assertFalse(rendered.contains(IP), "no alert may carry an address: " + rendered);
            assertFalse(rendered.contains(OWNER), "no alert may carry an owner hash: " + rendered);
            assertFalse(rendered.contains(PLAYER_NAME), "no alert may carry a player name: " + rendered);
        }
    }
}
