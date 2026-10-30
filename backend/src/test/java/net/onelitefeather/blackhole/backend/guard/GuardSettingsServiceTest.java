package net.onelitefeather.blackhole.backend.guard;

import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.guard.dto.GuardSettingsDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.StaffDelegations;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffAuditStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardSettingsServiceTest {

    /** 12:00 in Berlin on 29 October 2026, before the night window. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T11:00:00Z"), ZoneOffset.UTC);
    private static final UUID ADMIN_OID = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final Actor ADMIN = new Actor(ADMIN_OID, "Admin Name", ActorKind.HUMAN, false, true, true);

    private final InMemoryGuardSettingsStore store = InMemoryGuardSettingsStore.configured();
    private final RecordingEventTransport transport = new RecordingEventTransport();
    private final InMemoryStaffAuditStore audit = new InMemoryStaffAuditStore();
    private final GuardSettingsService service = new GuardSettingsService(this.store,
            new DomainEventPublisher(this.transport, CLOCK), StaffDelegations.recorder(CLOCK, this.audit), CLOCK);

    /** The configured values, spelled as the panel sends them. */
    private static GuardSettingsDTO.UpdateRequest configuredValues() {
        return new GuardSettingsDTO.UpdateRequest("STRICT", 20, true, "01:00", "07:00", "Europe/Berlin");
    }

    @Test
    void readSeedsTheSettingsAndShowsTheNextNightWindow() {
        GuardSettingsDTO.Response response = assertInstanceOf(GuardSettingsDTO.Response.class, this.service.read(),
                "a read of the seeded settings must succeed");

        assertEquals("STRICT", response.unattendedLevel(), "the seeded level must be the configured one");
        assertEquals(20, response.absenceThresholdMinutes(), "the seeded threshold must be the configured one");
        assertEquals(Instant.parse("2026-10-30T00:00:00Z").toEpochMilli(), response.nextNightWindowStart(),
                "the next window starts at 01:00 Berlin the following day");
        assertEquals(Instant.parse("2026-10-30T06:00:00Z").toEpochMilli(), response.nextNightWindowEnd(),
                "the next window ends at 07:00 Berlin the following day");
        assertNull(response.updatedByOid(), "the seeded row names no administrator");
    }

    @Test
    void aDisabledWindowShowsNoNextWindow() {
        InMemoryGuardSettingsStore disabled = new InMemoryGuardSettingsStore(new UnattendedSettings(GuardLevel.STRICT,
                Duration.ofMinutes(20), false, NightWindow.parse("01:00-07:00"), ZoneId.of("Europe/Berlin"), 0L, null));
        GuardSettingsService service = new GuardSettingsService(disabled, new DomainEventPublisher(this.transport, CLOCK),
                StaffDelegations.recorder(CLOCK, this.audit), CLOCK);

        GuardSettingsDTO.Response response = assertInstanceOf(GuardSettingsDTO.Response.class, service.read(), "a read must succeed");

        assertNull(response.nextNightWindowStart(), "a disabled window has no start to show");
        assertNull(response.nextNightWindowEnd(), "a disabled window has no end to show");
    }

    @Test
    void aValidSaveStoresTheValuesAuditsItAndPublishesOneEvent() {
        GuardSettingsDTO.UpdateRequest change = new GuardSettingsDTO.UpdateRequest("LOCKDOWN", 30, true, "22:00", "06:00", "Europe/Berlin");

        GuardSettingsDTO result = this.service.replace(change, ADMIN);

        GuardSettingsDTO.Response response = assertInstanceOf(GuardSettingsDTO.Response.class, result, "a valid save must succeed");
        assertEquals("LOCKDOWN", response.unattendedLevel(), "the saved level must be in the response");
        assertEquals(ADMIN_OID.toString(), response.updatedByOid(), "the save must be attributed to the token's oid");
        assertEquals(Duration.ofMinutes(30), this.store.current().absenceThreshold(), "the store must hold the saved threshold");
        assertEquals(1, this.audit.rows().size(), "one save must write one audit entry");
        assertEquals("guard.settings.set", this.audit.rows().getFirst().action(), "the audit action must be guard.settings.set");
        assertEquals(1, this.transport.published().size(), "a save that changes values must publish one event");
        assertEquals("guard.settings_changed", this.transport.published().getFirst().eventType(), "the event must be the settings change");
    }

    @Test
    void anOffLevelIsRefusedWithoutWritingAuditingOrPublishing() {
        UnattendedSettings before = this.store.current();
        GuardSettingsDTO.UpdateRequest off = new GuardSettingsDTO.UpdateRequest("OFF", 20, true, "01:00", "07:00", "Europe/Berlin");

        GuardSettingsDTO result = this.service.replace(off, ADMIN);

        GuardSettingsDTO.Error error = assertInstanceOf(GuardSettingsDTO.Error.class, result, "OFF must be refused");
        assertEquals(ErrorKind.VALIDATION, error.kind(), "a refused level is a validation error");
        assertEquals(before, this.store.current(), "a refused save must leave the stored settings unchanged");
        assertTrue(this.audit.rows().isEmpty(), "a refused save must not be audited");
        assertTrue(this.transport.published().isEmpty(), "a refused save must not publish");
    }

    @Test
    void aSaveOfTheSameValuesIsAuditedButNotAnnounced() {
        this.service.replace(configuredValues(), ADMIN);

        assertEquals(1, this.audit.rows().size(), "an attempt is audited even when no value changes");
        assertTrue(this.transport.published().isEmpty(), "a save that changes nothing must not announce a change");
    }

    @Test
    void aTooShortThresholdIsRefusedWithTheValidationKind() {
        GuardSettingsDTO.UpdateRequest four = new GuardSettingsDTO.UpdateRequest("STRICT", 4, true, "01:00", "07:00", "Europe/Berlin");

        GuardSettingsDTO result = this.service.replace(four, ADMIN);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(GuardSettingsDTO.Error.class, result, "four minutes must be refused").kind(),
                "a threshold outside its bounds is a validation error");
    }
}
