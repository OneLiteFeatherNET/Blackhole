package net.onelitefeather.blackhole.backend.audit;

import net.onelitefeather.blackhole.backend.audit.dto.AuditDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffAuditStore;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditReadServiceTest {

    private static final UUID MOD = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final InMemoryStaffAuditStore store = new InMemoryStaffAuditStore();
    private final AuditService service = new AuditService(store);

    private void record(String action, UUID actor) {
        store.insert(new StaffAuditEntry(UUID.randomUUID(), 1L, actor, null, Surface.PANEL, action, "NONE", null, actor));
    }

    private static AuditDTO.Page page(AuditDTO result) {
        return assertInstanceOf(AuditDTO.Page.class, result);
    }

    @Test
    void entriesComeNewestFirst() {
        record("kick.create", MOD);
        record("punishment.revoke", MOD);
        record("staff-link.unlink", MOD);

        AuditDTO.Page page = page(service.page(null, null, null, null, null));

        assertEquals(List.of("staff-link.unlink", "punishment.revoke", "kick.create"),
                page.items().stream().map(AuditDTO.Item::action).toList());
    }

    @Test
    void aFullPageCarriesACursorAndAShortLastPageDoesNot() {
        record("kick.create", MOD);
        record("kick.create", MOD);
        record("kick.create", MOD);

        AuditDTO.Page first = page(service.page(2, null, null, null, null));
        AuditDTO.Page last = page(service.page(2, first.nextCursor(), null, null, null));

        assertEquals(2, first.items().size());
        assertNotNull(first.nextCursor(), "a full page offers the next cursor");
        assertEquals(1, last.items().size());
        assertNull(last.nextCursor(), "the last page has no next cursor");
    }

    @Test
    void pagesDoNotOverlapAndTogetherHoldEveryEntry() {
        for (int i = 0; i < 5; i++) {
            record("kick.create", MOD);
        }

        Set<UUID> seen = new HashSet<>();
        String cursor = null;
        do {
            AuditDTO.Page next = page(service.page(2, cursor, null, null, null));
            next.items().forEach(item -> assertTrue(seen.add(item.auditId()), "no entry appears twice"));
            cursor = next.nextCursor();
        } while (cursor != null);

        assertEquals(5, seen.size(), "every entry is listed once");
    }

    @Test
    void aLimitBelowOneIsRefused() {
        assertInstanceOf(AuditDTO.Error.class, service.page(0, null, null, null, null));
    }

    @Test
    void aLimitAboveOneHundredIsRefused() {
        assertInstanceOf(AuditDTO.Error.class, service.page(101, null, null, null, null));
    }

    @Test
    void aCursorThatIsNotOursIsRefused() {
        assertInstanceOf(AuditDTO.Error.class, service.page(10, "not-a-cursor", null, null, null));
    }

    @Test
    void theActionFilterKeepsOnlyThatAction() {
        record("kick.create", MOD);
        record("punishment.revoke", MOD);

        AuditDTO.Page page = page(service.page(null, null, "punishment.revoke", null, null));

        assertEquals(List.of("punishment.revoke"), page.items().stream().map(AuditDTO.Item::action).toList());
    }

    @Test
    void theActorFilterKeepsOnlyThatActor() {
        record("kick.create", MOD);
        record("kick.create", ADMIN);

        AuditDTO.Page page = page(service.page(null, null, null, ADMIN, null));

        assertEquals(List.of(ADMIN), page.items().stream().map(AuditDTO.Item::actorOid).toList());
    }
}
