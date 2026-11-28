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
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelAllowListRemoveTest {

    private static final String OWNER = "a".repeat(128);

    private final TestClock clock = new TestClock();
    private final InMemoryIntelRecordStore store = new InMemoryIntelRecordStore();
    private final IntelPolicyCache cache = new IntelPolicyCache(this.store);
    private final InMemoryStaffAuditStore auditStore = new InMemoryStaffAuditStore();
    private final StaffAuditRecorder audit = new StaffAuditRecorder(this.auditStore,
            new DomainEventPublisher(new RecordingEventTransport(), this.clock), new AuditPayloadGuard(), this.clock,
            new SimpleMeterRegistry());
    private final IntelAllowListService service = new IntelAllowListService(this.store, this.cache, this.audit, this.clock);

    private static Actor moderator() {
        return new Actor(UUID.randomUUID(), "Mod", ActorKind.HUMAN, false, false);
    }

    private static Actor admin() {
        return new Actor(UUID.randomUUID(), "Admin", ActorKind.HUMAN, false, true);
    }

    @Test
    void aModeratorRemovesAnExemptionAndTheOwnerIsNoLongerExempt() {
        this.service.add(new AllowListDTO.CreateRequest(OWNER, "Works at a VPN-using employer", 30), moderator());
        assertTrue(this.cache.allowListed(OWNER, this.clock.millis()), "precondition: the owner is exempt");

        AllowListDTO result = this.service.remove(OWNER, moderator());

        assertInstanceOf(AllowListDTO.Removed.class, result, "the removal succeeds");
        assertFalse(this.cache.allowListed(OWNER, this.clock.millis()), "the cache forgets the exemption at once");
        assertTrue(this.store.loadAllowList().isEmpty(), "the entry is gone from the store");
    }

    @Test
    void aModeratorCannotRemoveAPermanentExemptionAndTheEntryStays() {
        this.service.add(new AllowListDTO.CreateRequest(OWNER, "Verified by the network owner", null), admin());

        AllowListDTO result = this.service.remove(OWNER, moderator());

        assertEquals(ErrorKind.FORBIDDEN_DURATION, assertInstanceOf(AllowListDTO.Error.class, result).kind(),
                "a permanent exemption is removed by an admin only, as it is created by an admin only");
        assertTrue(this.cache.allowListed(OWNER, this.clock.millis()), "the exemption still applies");
    }

    @Test
    void anAdminMayRemoveAPermanentExemption() {
        this.service.add(new AllowListDTO.CreateRequest(OWNER, "Verified by the network owner", null), admin());

        AllowListDTO result = this.service.remove(OWNER, admin());

        assertInstanceOf(AllowListDTO.Removed.class, result, "an admin may remove a permanent exemption");
        assertFalse(this.cache.allowListed(OWNER, this.clock.millis()), "the exemption no longer applies");
    }

    @Test
    void removingAnOwnerWithoutAnExemptionIsNotFound() {
        AllowListDTO result = this.service.remove(OWNER, moderator());

        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(AllowListDTO.Error.class, result).kind(),
                "there is nothing to remove");
    }

    @Test
    void aMalformedOwnerIsRefusedAsValidation() {
        AllowListDTO result = this.service.remove("not-a-hash", moderator());

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(AllowListDTO.Error.class, result).kind(),
                "the owner must be a sha-512 hash");
    }

    @Test
    void theRemovalIsAuditedWithTheRemoveAction() {
        this.service.add(new AllowListDTO.CreateRequest(OWNER, "Works at a VPN-using employer", 30), moderator());
        this.clock.advance(Duration.ofMinutes(1));

        this.service.remove(OWNER, moderator());

        assertEquals("intel.allow-list.remove", this.auditStore.rows().getLast().action(),
                "the removal is recorded with its own action, after the add");
    }
}
