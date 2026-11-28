package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.audit.StaffAuditEntry;
import net.onelitefeather.blackhole.backend.audit.Surface;
import net.onelitefeather.blackhole.backend.security.dto.StaffActorDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryCaseEventRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffAuditStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.RecordingProtocols;
import net.onelitefeather.blackhole.backend.support.StaffLinkTestBed;
import net.onelitefeather.blackhole.backend.support.DiscordLinkTestBed;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Anonymizing a staff member erases the name on their audit rows and keeps the oid. */
class StaffAnonymizeClearsAuditNameTest {

    private static final UUID LEAVER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OTHER = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Actor ADMIN = new Actor(UUID.fromString("44444444-4444-4444-8444-444444444444"), "Admin", ActorKind.HUMAN, false);

    private final InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> store =
            InMemoryRepository.<StaffActorRepository, StaffActorEntity, UUID>of(StaffActorRepository.class, StaffActorEntity::getOid).build();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final TestClock clock = new TestClock();
    private final StaffLinkTestBed links = new StaffLinkTestBed(this.events, this.clock);
    private final DiscordLinkTestBed discord = new DiscordLinkTestBed(this.events, this.clock);
    private final InMemoryStaffAuditStore audit = new InMemoryStaffAuditStore();
    private final StaffActorService service = new StaffActorService(this.store.repository(), this.events, this.links.service,
            this.discord.service, new RecordingProtocols(), this.links.profileService, new InMemoryCaseEventRepository(), this.audit);

    @Test
    void anonymizingClearsTheNameOnTheRowsOfThatPersonAndKeepsTheOid() {
        this.store.given(new StaffActorEntity(LEAVER, ActorKind.HUMAN, "Lea Leaver", 1L), LEAVER);
        audit.insert(new StaffAuditEntry(UUID.randomUUID(), 1L, LEAVER, "Lea Leaver", Surface.PANEL, "kick.create", "NONE", null, LEAVER));
        audit.insert(new StaffAuditEntry(UUID.randomUUID(), 2L, OTHER, "Other", Surface.PANEL, "kick.create", "NONE", null, OTHER));

        StaffActorDTO result = this.service.anonymize(LEAVER, ADMIN);

        assertEquals(LEAVER, ((StaffActorDTO.Response) result).oid());
        List<StaffAuditEntry> rows = audit.rows();
        assertNull(rows.get(0).actorName(), "the leaver's name is gone from the audit");
        assertEquals(LEAVER, rows.get(0).actorOid(), "the oid stays");
        assertEquals("Other", rows.get(1).actorName(), "other people's names are untouched");
    }
}
