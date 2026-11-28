package net.onelitefeather.blackhole.backend.intel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.AuditPayloadGuard;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.intel.dto.PolicyDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.InMemoryIntelRecordStore;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffAuditStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelPolicyServiceTest {

    private final TestClock clock = new TestClock();
    private final InMemoryIntelRecordStore store = new InMemoryIntelRecordStore();
    private final InMemoryStaffAuditStore auditStore = new InMemoryStaffAuditStore();
    private final IntelPolicyCache cache = new IntelPolicyCache(this.store);
    private final StaffAuditRecorder audit = new StaffAuditRecorder(this.auditStore,
            new DomainEventPublisher(new RecordingEventTransport(), this.clock), new AuditPayloadGuard(), this.clock,
            new SimpleMeterRegistry());
    private final IntelPolicyService service = new IntelPolicyService(this.store, this.cache, this.audit, this.clock);

    private static Actor moderator() {
        return new Actor(UUID.randomUUID(), "Mod", ActorKind.HUMAN, false, false);
    }

    private static Actor admin() {
        return new Actor(UUID.randomUUID(), "Admin", ActorKind.HUMAN, false, true);
    }

    @Test
    void aModeratorSettingDenyGetsTheForbiddenPolicyErrorAndTheValueIsUnchanged() {
        PolicyDTO result = this.service.update(new PolicyDTO.UpdateRequest(IntelClass.VPN, IntelPolicy.DENY), moderator());

        assertInstanceOf(PolicyDTO.Error.class, result, "a moderator may not set DENY");
        assertEquals(ErrorKind.FORBIDDEN_POLICY, ((PolicyDTO.Error) result).kind(), "the error names the policy rule");
        assertEquals(IntelPolicy.FLAG, this.cache.policyOf(IntelClass.VPN), "the refused change leaves the policy at FLAG");
    }

    @Test
    void anAdminSettingDenyStoresItAndReturnsThePreviousValue() {
        PolicyDTO result = this.service.update(new PolicyDTO.UpdateRequest(IntelClass.VPN, IntelPolicy.DENY), admin());

        PolicyDTO.Response response = assertInstanceOf(PolicyDTO.Response.class, result, "an admin may set DENY");
        assertEquals(IntelPolicy.DENY, response.policy(), "the new policy is DENY");
        assertEquals(IntelPolicy.FLAG, response.previous(), "the response names the value before the change");
        assertEquals(IntelPolicy.DENY, this.cache.policyOf(IntelClass.VPN), "the in-memory policy follows at once");
    }

    @Test
    void thePolicyChangeIsRecordedInTheAuditLogWithThePreviousValue() {
        this.service.update(new PolicyDTO.UpdateRequest(IntelClass.HOSTING, IntelPolicy.DENY), admin());

        var rows = this.auditStore.page(Long.MAX_VALUE, 10, AuditAction.INTEL_POLICY_UPDATE.wireName(), null, null);
        assertEquals(1, rows.size(), "one audited policy change");
        assertTrue(rows.getFirst().entry().targetRef().contains("FLAG>DENY"),
                "the audit entry names the previous and the new value: " + rows.getFirst().entry().targetRef());
    }
}
