package net.onelitefeather.blackhole.backend.guard;

import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.guard.dto.GuardSettingsDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffAuditStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.StaffDelegations;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guard.settings_changed payload is a whitelist: the settings and the change time, nothing that identifies a person. */
class GuardSettingsAlertPayloadTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T11:00:00Z"), ZoneOffset.UTC);
    private static final Set<String> KEYS = Set.of("unattendedLevel", "absenceThresholdMinutes", "nightWindowEnabled",
            "nightWindowStart", "nightWindowEnd", "timeZone", "changedAt", "source");
    private static final String STAFF_NAME = "Staff Member Name";
    private static final String IP = "192.0.2.10";

    private Map<String, Object> publishedPayload() {
        RecordingEventTransport transport = new RecordingEventTransport();
        GuardSettingsService service = new GuardSettingsService(InMemoryGuardSettingsStore.configured(),
                new DomainEventPublisher(transport, CLOCK), StaffDelegations.recorder(CLOCK, new InMemoryStaffAuditStore()), CLOCK);
        Actor admin = new Actor(UUID.fromString("00000000-0000-4000-8000-0000000000b2"), STAFF_NAME, ActorKind.HUMAN, false, true, true);

        service.replace(new GuardSettingsDTO.UpdateRequest("LOCKDOWN", 45, false, "01:00", "07:00", "Europe/Berlin"), admin);

        assertEquals(1, transport.published().size(), "the save must publish one settings event");
        return transport.published().getFirst().event().payload();
    }

    @Test
    void thePayloadHoldsOnlyTheWhitelistedKeys() {
        Map<String, Object> payload = publishedPayload();

        assertTrue(KEYS.containsAll(payload.keySet()), "the payload must hold only whitelisted keys: " + payload.keySet());
        assertEquals("PANEL", payload.get("source"), "the change came from the panel");
    }

    @Test
    void thePayloadNeverCarriesTheStaffNameOrAnAddress() {
        String rendered = publishedPayload().toString();

        assertFalse(rendered.contains(STAFF_NAME), "the alert must not name the staff member: " + rendered);
        assertFalse(rendered.contains(IP), "the alert must not carry an address: " + rendered);
    }
}
