package net.onelitefeather.blackhole.backend.casetimeline;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.audit.AuditPayloadGuard;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseDTO;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;

import net.onelitefeather.blackhole.backend.casetimeline.service.CaseService;
import net.onelitefeather.blackhole.backend.casetimeline.MessageContent;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.security.StaffActorEntity;
import net.onelitefeather.blackhole.backend.security.StaffActorRepository;
import net.onelitefeather.blackhole.backend.support.BanIdTestBed;
import net.onelitefeather.blackhole.backend.support.FakeCaseStore;
import net.onelitefeather.blackhole.backend.support.InMemoryCaseEventRepository;
import net.onelitefeather.blackhole.backend.support.FakeTicketStore;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffAuditStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.StaffDelegations;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The claim from Discord: the bot names the staff member, the case assignee is that member, and the case rules still hold. */
class CaseClaimDiscordTest {

    private static final String OWNER = "ab".repeat(64);
    private static final String ID = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final UUID STAFF = UUID.nameUUIDFromBytes("claiming-moderator".getBytes());
    private static final Actor BOT = new Actor(UUID.nameUUIDFromBytes("discord-bot".getBytes()), "Blackhole Discord", ActorKind.SERVICE,
            false, false, false, true);

    private final TestClock clock = new TestClock();
    private final InMemoryCaseEventRepository log = new InMemoryCaseEventRepository();
    private final FakeCaseStore store = new FakeCaseStore(this.log);
    private final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier)
                    .finder("findByShortId", args -> this.punishments.all().stream().filter(p -> args[0].equals(p.getShortId())).toList())
                    .build();
    private final InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> staff =
            InMemoryRepository.<StaffActorRepository, StaffActorEntity, UUID>of(StaffActorRepository.class, StaffActorEntity::getOid).build();
    private final StaffDelegations.Linked linked = StaffDelegations.linked(this.clock);
    private final InMemoryStaffAuditStore auditStore = new InMemoryStaffAuditStore();
    private final CaseService service = new CaseService(
            this.punishments.repository(), this.staff.repository(), this.store, this.store, this.log,
            new RecordingEventPublisher(), this.clock, new BanIdTestBed(this.punishments.repository()).service,
            new MessageContent("onelitefeather.net"), this.linked.delegation(), StaffDelegations.recorder(this.clock, this.auditStore),
            new FakeTicketStore());

    /** A case with one punishment and a moderator who is a known staff member, linked to Discord, with a fresh snapshot. */
    private void givenCaseAndLinkedModerator() {
        this.punishments.given(new PunishmentEntity(ID, UUID.randomUUID(), OWNER, "network", false, 1L, null, new HashMap<>()), ID);
        this.staff.given(new StaffActorEntity(STAFF, ActorKind.HUMAN, "Moderator", 1L), STAFF);
        this.linked.givenLinkedStaff(STAFF, "123456789012345678", "MODERATOR", this.clock.millis());
    }

    private static CaseDTO.UpdateRequest claim(long expectedVersion, UUID assignee, UUID onBehalfOf) {
        return new CaseDTO.UpdateRequest(expectedVersion, null, assignee, false, null, null, onBehalfOf);
    }

    @Test
    void aClaimFromDiscordSetsTheAssigneeToTheStaffMember() {
        givenCaseAndLinkedModerator();

        CaseDTO result = this.service.update(ID, claim(0L, STAFF, STAFF), BOT);

        assertEquals(STAFF, assertInstanceOf(CaseDTO.Response.class, result).assigneeOid(), "the staff member is the assignee");
    }

    @Test
    void aClaimFromDiscordIsAuditedUnderTheStaffMemberOnceAsADiscordAction() {
        givenCaseAndLinkedModerator();

        this.service.update(ID, claim(0L, STAFF, STAFF), BOT);

        var rows = this.auditStore.rows().stream().filter(row -> row.action().equals("case.update")).toList();
        assertEquals(1, rows.size(), "one case.update entry");
        assertEquals(STAFF, rows.getFirst().actorOid(), "the staff member is the actor");
        assertEquals(BOT.oid(), rows.getFirst().recordedByOid(), "the bot recorded it");
    }

    @Test
    void aClaimWithAStaleVersionIs409AndTheAssigneeIsUnchanged() {
        givenCaseAndLinkedModerator();

        CaseDTO result = this.service.update(ID, claim(7L, STAFF, STAFF), BOT);

        assertEquals(CaseErrorKind.CONFLICT, assertInstanceOf(CaseDTO.Error.class, result).kind(), "the case changed since the bot read it");
        assertTrue(this.log.all().isEmpty(), "no case event was appended for the refused claim");
    }

    @Test
    void aClaimOfAnUnknownAssigneeIs422() {
        givenCaseAndLinkedModerator();
        UUID unknown = UUID.nameUUIDFromBytes("nobody".getBytes());

        CaseDTO result = this.service.update(ID, claim(0L, unknown, STAFF), BOT);

        assertEquals(CaseErrorKind.UNPROCESSABLE, assertInstanceOf(CaseDTO.Error.class, result).kind(), "not a known human staff member");
    }

    @Test
    void aClaimFromTheBotWithoutOnBehalfOfIsRefused() {
        givenCaseAndLinkedModerator();

        CaseDTO result = this.service.update(ID, claim(0L, STAFF, null), BOT);

        assertEquals(CaseErrorKind.FORBIDDEN, assertInstanceOf(CaseDTO.Error.class, result).kind(), "the bot cannot act as itself here");
    }
}
