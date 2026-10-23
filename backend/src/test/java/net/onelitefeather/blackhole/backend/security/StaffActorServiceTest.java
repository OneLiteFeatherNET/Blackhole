package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.dto.StaffActorDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.StaffLinkTestBed;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffActorServiceTest {

    private static final UUID LEAVER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Actor ADMIN = new Actor(UUID.fromString("44444444-4444-4444-8444-444444444444"), "Admin", ActorKind.HUMAN, false);

    private final InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> store =
            InMemoryRepository.<StaffActorRepository, StaffActorEntity, UUID>of(StaffActorRepository.class, StaffActorEntity::getOid).build();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final TestClock clock = new TestClock();
    private final StaffLinkTestBed links = new StaffLinkTestBed(this.events, this.clock);
    private final StaffActorService service = new StaffActorService(this.store.repository(), this.events, this.links.service);

    @Test
    void anonymizingClearsTheDisplayNameAndKeepsTheOidAndKind() {
        this.store.given(new StaffActorEntity(LEAVER, ActorKind.HUMAN, "Lea Leaver", 1L), LEAVER);

        StaffActorDTO result = this.service.anonymize(LEAVER, ADMIN);

        assertEquals(LEAVER, assertInstanceOf(StaffActorDTO.Response.class, result).oid());
        StaffActorEntity row = this.store.repository().findById(LEAVER).orElseThrow();
        assertNull(row.getDisplayName(), "the name is gone");
        assertEquals(ActorKind.HUMAN, row.getKind(), "the principal itself stays");
    }

    @Test
    void theAnonymizationIsAuditedWithTheAdminsOid() {
        this.store.given(new StaffActorEntity(LEAVER, ActorKind.HUMAN, "Lea Leaver", 1L), LEAVER);

        this.service.anonymize(LEAVER, ADMIN);

        var event = this.events.eventsOfType(StaffActorService.ANONYMIZED_EVENT).getFirst();
        assertEquals(LEAVER.toString(), event.payload().get("oid"));
        assertEquals(ADMIN.oid().toString(), event.payload().get("anonymizedBy"), "the admin's verified oid is recorded");
        assertTrue(event.payload().values().stream().noneMatch("Lea Leaver"::equals), "the erased name is not published");
    }

    @Test
    void anUnknownPrincipalIsNotFoundAndNothingIsPublished() {
        StaffActorDTO result = this.service.anonymize(LEAVER, ADMIN);

        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(StaffActorDTO.Error.class, result).kind());
        assertTrue(this.events.events().isEmpty(), "no audit event for a no-op");
    }

    @Test
    void anonymizingAlsoRemovesTheMinecraftLinkAndPublishesRemovedWithReasonAnonymized() {
        this.store.given(new StaffActorEntity(LEAVER, ActorKind.HUMAN, "Lea Leaver", 1L), LEAVER);
        this.links.givenLink(LEAVER, UUID.fromString("55555555-5555-4555-8555-555555555555"));

        this.service.anonymize(LEAVER, ADMIN);

        assertTrue(this.links.linkOf(LEAVER).isEmpty(), "a leaving person must not stay attributable by uuid");
        var removed = this.events.eventsOfType(StaffLinkService.REMOVED_EVENT).getFirst();
        assertEquals("ANONYMIZED", removed.payload().get("reason"), "audited with its reason");
        assertEquals(LEAVER.toString(), removed.payload().get("oid"), "identified by oid");
    }

    @Test
    void anonymizingAPersonWithoutALinkPublishesNoRemoval() {
        this.store.given(new StaffActorEntity(LEAVER, ActorKind.HUMAN, "Lea Leaver", 1L), LEAVER);

        this.service.anonymize(LEAVER, ADMIN);

        assertTrue(this.events.eventsOfType(StaffLinkService.REMOVED_EVENT).isEmpty(), "nothing to remove");
    }
}
