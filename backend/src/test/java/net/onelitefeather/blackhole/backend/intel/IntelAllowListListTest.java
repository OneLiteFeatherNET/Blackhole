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

class IntelAllowListListTest {

    private final TestClock clock = new TestClock();
    private final InMemoryIntelRecordStore store = new InMemoryIntelRecordStore();
    private final IntelPolicyCache cache = new IntelPolicyCache(this.store);
    private final StaffAuditRecorder audit = new StaffAuditRecorder(new InMemoryStaffAuditStore(),
            new DomainEventPublisher(new RecordingEventTransport(), this.clock), new AuditPayloadGuard(), this.clock,
            new SimpleMeterRegistry());
    private final IntelAllowListService service = new IntelAllowListService(this.store, this.cache, this.audit, this.clock);

    private static String owner(int n) {
        return String.format("%0128x", n);
    }

    private static Actor moderator() {
        return new Actor(UUID.randomUUID(), "Mod", ActorKind.HUMAN, false, false);
    }

    private static Actor admin() {
        return new Actor(UUID.randomUUID(), "Admin", ActorKind.HUMAN, false, true);
    }

    /** Adds an exemption and moves the clock a minute on, so that the newest entry is the last one added. */
    private void exempt(int n, Actor actor, Integer days) {
        this.service.add(new AllowListDTO.CreateRequest(owner(n), "Works at a VPN-using employer", days), actor);
        this.clock.advance(Duration.ofMinutes(1));
    }

    private AllowListDTO.Page page(int page, int size) {
        AllowListDTO result = this.service.list(page, size);
        return assertInstanceOf(AllowListDTO.Page.class, result, "a valid page is listed");
    }

    @Test
    void anEntryPastItsExpiryIsListedAsExpired() {
        exempt(1, moderator(), 30);
        this.clock.advance(Duration.ofDays(31));

        AllowListDTO.Entry entry = page(0, 50).entries().getFirst();

        assertTrue(entry.expired(), "an exemption that ended 1 day ago is shown as expired");
    }

    @Test
    void anEntryInsideItsWindowIsNotListedAsExpired() {
        exempt(1, moderator(), 30);
        this.clock.advance(Duration.ofDays(29));

        assertFalse(page(0, 50).entries().getFirst().expired(), "an exemption with 1 day left is still active");
    }

    @Test
    void aPermanentEntryIsNeverListedAsExpired() {
        exempt(1, admin(), null);
        this.clock.advance(Duration.ofDays(3650));

        AllowListDTO.Entry entry = page(0, 50).entries().getFirst();

        assertFalse(entry.expired(), "a permanent exemption never expires");
        assertNull(entry.expiresAt(), "a permanent exemption has no expiry");
    }

    @Test
    void entriesAreListedNewestFirstWithTheirActorAndReason() {
        Actor mod = moderator();
        exempt(1, mod, 30);
        exempt(2, mod, 7);

        AllowListDTO.Entry newest = page(0, 50).entries().getFirst();

        assertEquals(owner(2), newest.owner(), "the newest exemption comes first");
        assertEquals(mod.oid().toString(), newest.actorOid(), "the actor's oid is shown");
        assertEquals("Works at a VPN-using employer", newest.reason(), "the reason is shown");
    }

    @Test
    void theSecondPageHoldsTheRemainingEntriesAndNoNextPage() {
        Actor mod = moderator();
        exempt(1, mod, 30);
        exempt(2, mod, 30);
        exempt(3, mod, 30);

        AllowListDTO.Page first = page(0, 2);
        AllowListDTO.Page second = page(1, 2);

        assertEquals(2, first.entries().size(), "the first page is full");
        assertTrue(first.hasMore(), "a further page exists");
        assertEquals(1, second.entries().size(), "the second page holds the last entry");
        assertFalse(second.hasMore(), "no page follows the last one");
    }

    @Test
    void aPageSizeOutsideOneToOneHundredIsRefused() {
        AllowListDTO tooLarge = this.service.list(0, 101);
        AllowListDTO zero = this.service.list(0, 0);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(AllowListDTO.Error.class, tooLarge).kind(),
                "a size above 100 is a validation error");
        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(AllowListDTO.Error.class, zero).kind(),
                "a size of 0 is a validation error");
    }
}
