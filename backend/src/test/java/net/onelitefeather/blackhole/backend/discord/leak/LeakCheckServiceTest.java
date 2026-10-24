package net.onelitefeather.blackhole.backend.discord.leak;

import net.onelitefeather.blackhole.backend.casetimeline.BanIdLeakSource;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRecord;
import net.onelitefeather.blackhole.backend.discord.DiscordTicketProperties;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordLeakCheckDTO;
import net.onelitefeather.blackhole.backend.discord.ticket.ParticipantKind;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.BanIdTestBed;
import net.onelitefeather.blackhole.backend.support.FakeCaseStore;
import net.onelitefeather.blackhole.backend.support.FakeTicketStore;
import net.onelitefeather.blackhole.backend.support.InMemoryCaseEventRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The leak check: only a current Ban ID has an effect, the poster stays an opaque participant, and nothing identifying is emitted. */
class LeakCheckServiceTest {

    private static final String ID = "leak-1";
    private static final String OWNER = "ab".repeat(64);
    private static final String POSTER = "333333333333333333";
    private static final Actor BOT = new Actor(UUID.fromString("b07b07b0-0000-4000-8000-000000000001"), "bot", ActorKind.SERVICE, false);

    private final InMemoryCaseEventRepository log = new InMemoryCaseEventRepository();
    private final FakeCaseStore cases = new FakeCaseStore(this.log);
    private final FakeTicketStore tickets = new FakeTicketStore();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier).build();
    private final BanIdTestBed banIds = new BanIdTestBed(this.punishments.repository());
    private String banId;
    private LeakCheckService service;

    @BeforeEach
    void givenAPunishmentWithABanId() {
        this.punishments.given(new PunishmentEntity(ID, UUID.randomUUID(), OWNER, "network", false, 1L, null, new HashMap<>()), ID);
        this.banId = this.banIds.issueFor(ID);
        this.service = new LeakCheckService(this.banIds.service, this.punishments.repository(), this.tickets, this.cases, this.events, new TestClock(),
                DiscordTicketProperties.defaults());
    }

    private DiscordLeakCheckDTO.Result check(String poster, String... candidates) {
        return assertInstanceOf(DiscordLeakCheckDTO.Result.class, this.service.check(
                new DiscordLeakCheckDTO.Request(List.of(candidates), DiscordLeakCheckDTO.ChannelKind.PUBLIC, poster, false), BOT));
    }

    private List<CaseEventRecord> leaks() {
        return this.log.ofType(ID, "ban_id.leak_marked");
    }

    @Test
    void aCurrentBanIdMatchesAndIsMarkedLeakedWithTheDiscordSource() {
        DiscordLeakCheckDTO.Result result = check(POSTER, this.banId);

        assertEquals(1, result.matched());
        assertEquals(1, result.marked());
        CaseEventPayload.BanIdLeakMarked payload = assertInstanceOf(CaseEventPayload.BanIdLeakMarked.class, leaks().getFirst().payload());
        assertEquals(BanIdLeakSource.DISCORD_BOT, payload.source());
        assertEquals(false, payload.posterIsOwner(), "a stranger posted it");
    }

    @Test
    void aLookAlikeCausesNoEventAndNoParticipant() {
        DiscordLeakCheckDTO.Result result = check(POSTER, "ZZZZZZZZ", "not a code");

        assertEquals(0, result.matched());
        assertTrue(leaks().isEmpty());
        assertTrue(this.tickets.participants.isEmpty(), "the poster of a look-alike is not stored");
        assertTrue(this.events.events().isEmpty());
    }

    @Test
    void theSameBanIdAgainMatchesButMarksNothingNew() {
        check(POSTER, this.banId);

        DiscordLeakCheckDTO.Result again = check(POSTER, this.banId);

        assertEquals(1, again.matched());
        assertEquals(0, again.marked());
        assertEquals(1, leaks().size());
    }

    @Test
    void theBoundPlayerPostingTheirOwnBanIdIsTheOwner() {
        this.tickets.ensureParticipant(ID, ParticipantKind.PLAYER, POSTER, null, false, 1L);

        check(POSTER, this.banId);

        assertEquals(true, assertInstanceOf(CaseEventPayload.BanIdLeakMarked.class, leaks().getFirst().payload()).posterIsOwner());
    }

    @Test
    void theTypedFormWithSeparatorsAndLowerCaseMatches() {
        String spaced = "#" + this.banId.substring(0, 4).toLowerCase() + " " + this.banId.substring(4).toLowerCase();

        assertEquals(1, check(POSTER, spaced).matched());
    }

    @Test
    void atMostFiveCandidatesAreLookedAt() {
        DiscordLeakCheckDTO.Result result = check(POSTER, "ZZZZZZZ1", "ZZZZZZZ2", "ZZZZZZZ3", "ZZZZZZZ4", "ZZZZZZZ5", this.banId);

        assertEquals(0, result.matched(), "the sixth candidate is not examined");
    }

    @Test
    void theEventAndTheDomainEventCarryNoBanIdAndNoDiscordId() {
        check(POSTER, this.banId);

        String stored = leaks().getFirst().body().toString();
        String published = this.events.events().toString();
        assertFalse(stored.contains(this.banId) || published.contains(this.banId), "no Ban ID");
        assertFalse(stored.contains(POSTER) || published.contains(POSTER), "no Discord id: the poster is an opaque participant");
        assertEquals("case.ban_id_leak_marked", this.events.events().getFirst().type());
    }

    @Test
    void aBotPosterIsNotRecordedAsAParticipant() {
        this.service.check(new DiscordLeakCheckDTO.Request(List.of(this.banId), DiscordLeakCheckDTO.ChannelKind.PUBLIC, POSTER, true), BOT);

        assertTrue(this.tickets.participants.isEmpty());
        assertEquals(1, leaks().size());
    }

    @Test
    void aMalformedRequestIs400() {
        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(DiscordLeakCheckDTO.Error.class, this.service.check(
                new DiscordLeakCheckDTO.Request(List.of(this.banId), DiscordLeakCheckDTO.ChannelKind.PUBLIC, "x", false), BOT)).kind());
    }
}
