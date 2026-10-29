package net.onelitefeather.blackhole.backend.guard;

import net.onelitefeather.blackhole.backend.guard.dto.PresenceDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardPresenceServiceTest {

    private static final Instant START = Instant.parse("2026-10-29T12:00:00Z");

    private final InMemoryPresenceStore store = new InMemoryPresenceStore();
    private final Clock clock = Clock.fixed(START, ZoneOffset.UTC);
    private final GuardSettings settings = GuardSettings.of("STRICT", Duration.ofMinutes(20), "01:00-07:00", "Europe/Berlin",
            Duration.ofMinutes(5));
    private final GuardPresenceService presence = new GuardPresenceService(this.store, this.settings, this.clock);

    @Test
    void aHeartbeatWithNoStaffMakesStaffAbsent() {
        presence.report(new PresenceDTO.Heartbeat("velocity-1", 0));

        assertFalse(presence.staffPresent(), "a heartbeat with zero staff online must leave staff absent");
    }

    @Test
    void aHeartbeatWithStaffMakesStaffPresent() {
        presence.report(new PresenceDTO.Heartbeat("velocity-1", 2));

        assertTrue(presence.staffPresent(), "a heartbeat with staff online must make staff present");
    }

    @Test
    void anEntryOlderThanTheTtlIsIgnored() {
        store.report("velocity-1", 3, START.minus(Duration.ofMinutes(6)).toEpochMilli());

        assertFalse(presence.staffPresent(), "an entry older than the five minute ttl must not count as present");
    }

    @Test
    void onlyTheFreshProxyCountsWhenAnotherProxyIsStale() {
        store.report("velocity-1", 3, START.minus(Duration.ofMinutes(10)).toEpochMilli());
        store.report("velocity-2", 1, START.minus(Duration.ofMinutes(1)).toEpochMilli());

        assertTrue(presence.staffPresent(), "the fresh proxy with staff must count even while another proxy is stale");
    }

    @Test
    void aHeartbeatWithZeroStaffDoesNotMarkStaffSeen() {
        presence.report(new PresenceDTO.Heartbeat("velocity-1", 0));

        assertEquals(java.util.OptionalLong.empty(), presence.lastStaffSeen(),
                "a heartbeat with no staff must not move the last-seen time");
    }

    @Test
    void aHeartbeatWithStaffMarksStaffSeenAtTheClockTime() {
        presence.report(new PresenceDTO.Heartbeat("velocity-1", 1));

        assertEquals(START.toEpochMilli(), presence.lastStaffSeen().getAsLong(),
                "a heartbeat with staff must record the time from the injected clock");
    }

    @Test
    void aMissingProxyIdIsRefusedAsAValidationError() {
        PresenceDTO result = presence.report(new PresenceDTO.Heartbeat(" ", 1));

        PresenceDTO.Error error = assertInstanceOf(PresenceDTO.Error.class, result, "a blank proxy id must be refused");
        assertEquals(ErrorKind.VALIDATION, error.kind(), "a blank proxy id is a validation error");
    }
}
