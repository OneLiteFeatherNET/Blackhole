package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseConversationDTO;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseParticipantDTO;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseConversationService;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseParticipantService;
import net.onelitefeather.blackhole.backend.discord.ticket.Participant;
import net.onelitefeather.blackhole.backend.discord.ticket.ParticipantKind;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.BanIdTestBed;
import net.onelitefeather.blackhole.backend.support.FakeCaseStore;
import net.onelitefeather.blackhole.backend.support.FakeTicketStore;
import net.onelitefeather.blackhole.backend.support.InMemoryCaseEventRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.RecordingProtocols;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Erasing the Discord participants of a case: by an admin, and together with the player's messages. In-memory stores, a fixed clock. */
class CaseParticipantServiceTest {

    private static final String ID = "participant-case-1";
    private static final Actor ADMIN = new Actor(UUID.fromString("ad000000-0000-4000-8000-000000000001"), "Admin", ActorKind.HUMAN, false, true);

    private final TestClock clock = new TestClock();
    private final FakeTicketStore tickets = new FakeTicketStore();
    private final RecordingProtocols protocols = new RecordingProtocols();
    private final InMemoryCaseEventRepository log = new InMemoryCaseEventRepository();
    private final FakeCaseStore cases = new FakeCaseStore(this.log);
    private final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier)
                    .finder("findByShortId", args -> java.util.List.of()).build();
    private final CaseParticipantService service = new CaseParticipantService(this.punishments.repository(), this.tickets, this.protocols, this.clock);
    private Participant player;

    @BeforeEach
    void givenACaseWithABoundPlayer() {
        this.punishments.given(new PunishmentEntity(ID, UUID.randomUUID(), "ab".repeat(64), "network", false, 1L, null, new HashMap<>()), ID);
        this.cases.ensureCase(ID, 1L);
        this.player = this.tickets.ensureParticipant(ID, ParticipantKind.PLAYER, "111111111111111111", "Player", false, 1L);
    }

    @Test
    void erasingNullsTheDiscordIdAndNameAndRegeneratesTheProtocols() {
        CaseParticipantDTO result = this.service.erase(ID, this.player.id());

        assertEquals(true, assertInstanceOf(CaseParticipantDTO.Erased.class, result).erased());
        Participant erased = this.tickets.participant(this.player.id()).orElseThrow();
        assertNull(erased.discordUserId());
        assertNull(erased.displayName());
        assertTrue(erased.erased());
        assertEquals(java.util.List.of(ID), this.protocols.redacted, "the stored protocols are purged and regenerated");
    }

    @Test
    void erasingTwiceChangesNothingTheSecondTime() {
        this.service.erase(ID, this.player.id());

        CaseParticipantDTO again = this.service.erase(ID, this.player.id());

        assertFalse(assertInstanceOf(CaseParticipantDTO.Erased.class, again).erased());
        assertEquals(1, this.protocols.redacted.size());
    }

    @Test
    void aParticipantOfAnotherCaseIsNotFound() {
        Participant other = this.tickets.ensureParticipant("another-case", ParticipantKind.PLAYER, "222222222222222222", null, false, 1L);

        CaseParticipantDTO result = this.service.erase(ID, other.id());

        assertEquals(CaseErrorKind.NOT_FOUND, assertInstanceOf(CaseParticipantDTO.Error.class, result).kind());
        assertEquals("222222222222222222", this.tickets.participant(other.id()).orElseThrow().discordUserId(), "nothing was erased");
    }

    @Test
    void anUnknownCaseIsNotFound() {
        assertEquals(CaseErrorKind.NOT_FOUND, assertInstanceOf(CaseParticipantDTO.Error.class, this.service.erase("nope", this.player.id())).kind());
    }

    @Test
    void thePlayerSideEraseOfTheConversationAlsoClearsTheBoundParticipant() {
        RecordingEventPublisher events = new RecordingEventPublisher();
        BanIdTestBed banIds = new BanIdTestBed(this.punishments.repository());
        CaseConversationService conversation = new CaseConversationService(this.punishments.repository(), this.cases, this.cases, this.log, events,
                this.clock, banIds.service, this.protocols, this.service);

        CaseConversationDTO result = conversation.erasePlayerMessages(ID, new CaseConversationDTO.RedactRequest(NoteRedactionReason.OTHER), ADMIN);

        assertInstanceOf(CaseConversationDTO.EraseResponse.class, result);
        assertTrue(this.tickets.participant(this.player.id()).orElseThrow().erased(), "who the player was in Discord goes with their words");
    }
}
