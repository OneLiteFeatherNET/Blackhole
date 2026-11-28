package net.onelitefeather.blackhole.backend.intel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.audit.AuditPayloadGuard;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.intel.dto.AllowListDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.InMemoryIntelRecordStore;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffAuditStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelAllowListTest {

    private static final String OWNER = "a".repeat(128);

    private final TestClock clock = new TestClock();
    private final InMemoryIntelRecordStore store = new InMemoryIntelRecordStore();
    private final IntelPolicyCache cache = new IntelPolicyCache(this.store);
    private final StaffAuditRecorder audit = new StaffAuditRecorder(new InMemoryStaffAuditStore(),
            new DomainEventPublisher(new RecordingEventTransport(), this.clock), new AuditPayloadGuard(), this.clock,
            new SimpleMeterRegistry());
    private final IntelAllowListService service = new IntelAllowListService(this.store, this.cache, this.audit, this.clock);

    private static Actor moderator() {
        return new Actor(UUID.randomUUID(), "Mod", ActorKind.HUMAN, false, false);
    }

    private static Actor admin() {
        return new Actor(UUID.randomUUID(), "Admin", ActorKind.HUMAN, false, true);
    }

    private AllowListDTO add(Actor actor, String reason, Integer days) {
        return this.service.add(new AllowListDTO.CreateRequest(OWNER, reason, days), actor);
    }

    @Test
    void aModeratorExemptsAPlayerForThirtyDays() {
        AllowListDTO result = add(moderator(), "Works at a VPN-using employer", 30);

        AllowListDTO.Response response = assertInstanceOf(AllowListDTO.Response.class, result, "30 days is allowed");
        assertEquals(this.clock.millis() + Duration.ofDays(30).toMillis(), response.expiresAt(), "the exemption ends in 30 days");
        assertTrue(this.cache.allowListed(OWNER, this.clock.millis()), "the owner is exempt right away");
    }

    @Test
    void aModeratorAskingForAPermanentExemptionGetsTheForbiddenDurationError() {
        AllowListDTO result = add(moderator(), "Works at a VPN-using employer", null);

        assertInstanceOf(AllowListDTO.Error.class, result, "a permanent exemption is refused for a moderator");
        assertEquals(ErrorKind.FORBIDDEN_DURATION, ((AllowListDTO.Error) result).kind(), "the error names the duration rule");
        assertFalse(this.cache.allowListed(OWNER, this.clock.millis()), "nothing was stored");
    }

    @Test
    void anAdminMayExemptPermanently() {
        AllowListDTO result = add(admin(), "Verified by the network owner", null);

        AllowListDTO.Response response = assertInstanceOf(AllowListDTO.Response.class, result, "an admin may be permanent");
        assertNull(response.expiresAt(), "a permanent exemption has no expiry");
    }

    @Test
    void aModeratorExemptionLongerThanNinetyDaysIsRefused() {
        AllowListDTO result = add(moderator(), "Works at a VPN-using employer", 91);

        assertInstanceOf(AllowListDTO.Error.class, result, "a temporary exemption is at most 90 days");
        assertEquals(ErrorKind.VALIDATION, ((AllowListDTO.Error) result).kind(), "the duration is out of range");
    }

    @Test
    void anExemptionWithoutAReasonIsRefused() {
        AllowListDTO result = add(moderator(), "   ", 30);

        assertInstanceOf(AllowListDTO.Error.class, result, "the reason is required");
        assertEquals(ErrorKind.VALIDATION, ((AllowListDTO.Error) result).kind(), "a blank reason is a validation error");
    }

    @Test
    void anExpiredExemptionNoLongerApplies() {
        add(moderator(), "Works at a VPN-using employer", 30);

        this.clock.advance(Duration.ofDays(31));

        assertFalse(this.cache.allowListed(OWNER, this.clock.millis()), "the 30-day exemption has run out");
    }
}
