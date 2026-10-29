package net.onelitefeather.blackhole.backend.discord.ticket;

import net.onelitefeather.blackhole.backend.support.StaffDelegations;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdLeakReason;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdLeakSource;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.AppealProtocolGenerator;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.InMemoryProtocolStore;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolVersion;
import net.onelitefeather.blackhole.backend.discord.DiscordTicketProperties;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordTicketDTO;
import net.onelitefeather.blackhole.backend.discord.ticket.SharePosts;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordTicketDTO.Code;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffAuditStore;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.BanIdTestBed;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.FakeCaseStore;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.FakeTicketStore;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryCaseEventRepository;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingProtocols;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ticket rules over in-memory stores: the uniform open failure, the statement and waiting rules, the bound participant, the state machine
 * and the protocol gates. A fixed clock, no database, no network, no sleeps.
 */
class TicketServiceTest {

    private static final String PUNISHMENT = "punishment-1";
    private static final String OWNER = "ab".repeat(64);
    private static final String PLAYER = "111111111111111111";
    private static final String OTHER = "222222222222222222";
    private static final UUID APPEAL = UUID.fromString("a99ea100-0000-4000-8000-000000000001");
    private static final Actor BOT = new Actor(UUID.fromString("b07b07b0-0000-4000-8000-000000000001"), "bot", ActorKind.SERVICE, false);
    private static final Actor DISCORD_BOT = new Actor(UUID.fromString("b07b07b0-0000-4000-8000-000000000002"), "bot", ActorKind.SERVICE,
            false, false, false, true);
    private static final UUID STAFF = UUID.fromString("5ca00000-0000-4000-8000-000000000001");
    private static final String STAFF_DISCORD = "333333333333333333";

    private final TestClock clock = new TestClock();
    private final InMemoryCaseEventRepository log = new InMemoryCaseEventRepository();
    private final FakeCaseStore cases = new FakeCaseStore(this.log);
    private final FakeTicketStore store = new FakeTicketStore();
    private final InMemoryProtocolStore protocolStore = new InMemoryProtocolStore();
    private final RecordingProtocols generator = new RecordingProtocols();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final InMemoryRepository<AppealRepository, AppealEntity, UUID> appealRows =
            InMemoryRepository.<AppealRepository, AppealEntity, UUID>of(AppealRepository.class, AppealEntity::getIdentifier).build();
    private final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier).build();
    private final BanIdTestBed banIds = new BanIdTestBed(this.punishments.repository());
    private final StaffDelegations.Linked linked = StaffDelegations.linked(this.clock);
    private final InMemoryStaffAuditStore auditStore = new InMemoryStaffAuditStore();
    private final StaffAuditRecorder auditRecorder = StaffDelegations.recorder(this.clock, this.auditStore);
    private TicketAppeals.Outcome appealOutcome = new TicketAppeals.Outcome.Existing(APPEAL);
    private int appealCalls;
    private String banId;
    private TicketService service;

    @BeforeEach
    void givenAPunishmentWithABanId() {
        this.punishments.given(new PunishmentEntity(PUNISHMENT, UUID.randomUUID(), OWNER, "network", false, 1L, null, new HashMap<>()), PUNISHMENT);
        this.banId = this.banIds.issueFor(PUNISHMENT);
        this.service = new TicketService(this.store, this.banIds.service, this.punishments.repository(), (punishment, statement) -> {
            this.appealCalls++;
            return this.appealOutcome;
        }, this.cases, this.cases, this.protocolStore, this.generator, this.events, this.clock, DiscordTicketProperties.defaults(),
                new SharePosts(null, null), this.linked.delegation(), this.auditRecorder, this.appealRows.repository());
    }

    private DiscordTicketDTO.Opened open(String discordUser, String ban, String statement) {
        return assertInstanceOf(DiscordTicketDTO.Opened.class, this.service.open(new DiscordTicketDTO.OpenRequest(discordUser, "Name", ban, statement), BOT));
    }

    private DiscordTicketDTO.Error openFails(String discordUser, String ban, String statement) {
        return assertInstanceOf(DiscordTicketDTO.Error.class, this.service.open(new DiscordTicketDTO.OpenRequest(discordUser, "Name", ban, statement), BOT));
    }

    private UUID openTicket() {
        return open(PLAYER, this.banId, "I did nothing").ticket().id();
    }

    private UUID openChannelTicket() {
        UUID id = openTicket();
        assertInstanceOf(DiscordTicketDTO.Ticket.class, this.service.patch(id, new DiscordTicketDTO.PatchRequest("900000000000000001", "800000000000000001", null, null, null), BOT));
        return id;
    }

    private DiscordTicketDTO patchState(UUID id, TicketState state) {
        return this.service.patch(id, new DiscordTicketDTO.PatchRequest(null, null, state, null, null), BOT);
    }

    private void givenStoredProtocol(int version) {
        this.protocolStore.rows.add(new ProtocolVersion(UUID.randomUUID(), PUNISHMENT, APPEAL, version, UUID.randomUUID(), "digest", 3, 1L, 3L,
                ProtocolVersion.Status.ACTIVE, 1L));
    }

    private UUID closedTicket() {
        UUID id = openChannelTicket();
        this.store.tally = new DiscordTicketStore.Tally(2, 1234, 0, 0);
        assertInstanceOf(DiscordTicketDTO.Closed.class, this.service.close(id, new DiscordTicketDTO.CloseRequest(2, "1234"), BOT));
        return id;
    }

    // ---- open ----

    @Test
    void aValidBanIdOpensAPendingTicketBoundToTheDiscordUser() {
        DiscordTicketDTO.Opened opened = open(PLAYER, this.banId, "I did nothing");

        assertTrue(opened.created());
        assertEquals(TicketState.PENDING_CHANNEL, opened.ticket().state());
        assertEquals(1, opened.ticket().ticketNo());
        assertEquals(PLAYER, opened.ticket().playerDiscordUserId(), "the bot needs the bound user to set the overwrite");
        assertEquals(APPEAL, opened.ticket().appealId());
        assertEquals(List.of("ticket.opened"), this.store.appended);
    }

    @Test
    void theBoundParticipantIsAPlayerParticipantOfTheCase() {
        open(PLAYER, this.banId, "I did nothing");

        Participant bound = this.store.participantByDiscordUser(PUNISHMENT, PLAYER).orElseThrow();
        assertEquals(ParticipantKind.PLAYER, bound.kind());
    }

    @Test
    void anUnknownBanIdAndALeakedOneAndOneBoundToAnotherUserAllGetTheSameAnswer() {
        DiscordTicketDTO.Error unknown = openFails(PLAYER, "ZZZZZZZZ", "x");
        this.cases.ensureCase(PUNISHMENT, 1L);
        UUID other = openTicketBoundTo(OTHER);
        DiscordTicketDTO.Error bound = openFails(PLAYER, this.banId, "x");
        this.store.tickets.remove(other);
        this.cases.markBanIdLeaked(PUNISHMENT, BanIdLeakReason.SHARED, BanIdLeakSource.STAFF, 1L, CaseActor.system());
        DiscordTicketDTO.Error leaked = openFails(PLAYER, this.banId, "x");

        assertEquals(ErrorKind.NOT_FOUND, unknown.kind());
        assertEquals(Code.NOT_ELIGIBLE, unknown.code());
        assertEquals(unknown, bound, "bound to another user: the same reply, no oracle");
        assertEquals(unknown, leaked, "a leaked Ban ID: the same reply");
    }

    private UUID openTicketBoundTo(String discordUser) {
        return open(discordUser, this.banId, "I did nothing").ticket().id();
    }

    @Test
    void theUserWhoHasTheTicketGetsItAgainAndNothingIsCreated() {
        UUID first = openTicket();
        this.appealCalls = 0;

        DiscordTicketDTO.Opened again = open(PLAYER, this.banId, null);

        assertFalse(again.created());
        assertEquals(first, again.ticket().id());
        assertEquals(0, this.appealCalls, "no second appeal work");
        assertEquals(1, this.store.tickets.size());
    }

    @Test
    void aMissingStatementIs400AndNothingIsCreated() {
        this.appealOutcome = new TicketAppeals.Outcome.StatementMissing();

        DiscordTicketDTO.Error error = openFails(PLAYER, this.banId, null);

        assertEquals(ErrorKind.VALIDATION, error.kind());
        assertEquals(Code.STATEMENT_REQUIRED, error.code());
        assertTrue(this.store.tickets.isEmpty());
        assertTrue(this.store.participants.isEmpty(), "no participant before the ticket exists");
    }

    @Test
    void anAppealThatIsNotPossibleYetNamesTheDateAndCreatesNothing() {
        this.appealOutcome = new TicketAppeals.Outcome.NotYet(1_900_000_000_000L);

        DiscordTicketDTO.Error error = openFails(PLAYER, this.banId, "please");

        assertEquals(ErrorKind.CONFLICT, error.kind());
        assertEquals(Code.NOT_YET, error.code());
        assertEquals(1_900_000_000_000L, error.availableFrom());
        assertTrue(this.store.tickets.isEmpty());
    }

    @Test
    void anAppealFiledByThisCallIsReported() {
        this.appealOutcome = new TicketAppeals.Outcome.Filed(APPEAL);

        assertTrue(open(PLAYER, this.banId, "please").appealFiled());
    }

    @Test
    void aMalformedDiscordUserIdIsRefusedBeforeAnythingIsLookedUp() {
        DiscordTicketDTO.Error error = openFails("not-a-snowflake", this.banId, "x");

        assertEquals(ErrorKind.VALIDATION, error.kind());
    }

    @Test
    void openingPublishesAnEventWithIdsAndTheHashedOwnerButNoDiscordIdOrBanId() {
        open(PLAYER, this.banId, "I did nothing");

        RecordingEventPublisher.Event event = this.events.eventsOfType("discord.ticket_opened").getFirst();
        String text = event.payload().toString();
        assertEquals(OWNER, event.payload().get("owner"));
        assertFalse(text.contains(PLAYER), "no Discord user id in an event");
        assertFalse(text.contains(this.banId), "no Ban ID in an event");
    }

    // ---- channel and state ----

    @Test
    void reportingTheChannelOpensThePendingTicketAndAnnouncesIt() {
        UUID id = openChannelTicket();

        DiscordTicketDTO.Ticket ticket = assertInstanceOf(DiscordTicketDTO.Ticket.class, this.service.get(id));
        assertEquals(TicketState.OPEN, ticket.state());
        assertEquals("900000000000000001", ticket.channelId());
        assertEquals(1, this.events.eventsOfType("discord.ticket_channel_ready").size());
    }

    @Test
    void reportingTheSameChannelAgainIsANoOp() {
        UUID id = openChannelTicket();

        DiscordTicketDTO again = this.service.patch(id, new DiscordTicketDTO.PatchRequest("900000000000000001", null, null, null, null), BOT);

        assertInstanceOf(DiscordTicketDTO.Ticket.class, again);
        assertEquals(1, this.events.eventsOfType("discord.ticket_channel_ready").size(), "announced once");
    }

    @Test
    void aChannelAnotherTicketUsesIsRefused() {
        UUID first = openChannelTicket();
        this.store.transition(first, Set.of(TicketState.OPEN), TicketState.CLOSED, 1L);
        this.appealOutcome = new TicketAppeals.Outcome.Existing(UUID.randomUUID());
        UUID second = openTicket();

        DiscordTicketDTO.Error error = assertInstanceOf(DiscordTicketDTO.Error.class,
                this.service.patch(second, new DiscordTicketDTO.PatchRequest("900000000000000001", null, null, null, null), BOT));

        assertEquals(Code.CHANNEL_TAKEN, error.code());
    }

    @Test
    void theFreezeIsMirroredOnTheTicket() {
        UUID id = openChannelTicket();

        DiscordTicketDTO.Ticket frozen = assertInstanceOf(DiscordTicketDTO.Ticket.class,
                this.service.patch(id, new DiscordTicketDTO.PatchRequest(null, null, null, true, null), BOT));

        assertTrue(frozen.frozen());
    }

    @Test
    void aTicketCannotJumpFromOpenToArchived() {
        UUID id = openChannelTicket();
        givenStoredProtocol(1);

        DiscordTicketDTO.Error error = assertInstanceOf(DiscordTicketDTO.Error.class, patchState(id, TicketState.ARCHIVED));

        assertEquals(Code.INVALID_STATE, error.code());
    }

    // ---- close ----

    @Test
    void aMatchingProofRecordsTheTranscriptAndStoresTheProtocol() {
        UUID id = openChannelTicket();
        this.store.tally = new DiscordTicketStore.Tally(3, 5555, 1, 0);
        this.generator.answer = new AppealProtocolGenerator.Result(AppealProtocolGenerator.Outcome.STORED, 2, UUID.randomUUID(), 4);

        DiscordTicketDTO.Closed closed = assertInstanceOf(DiscordTicketDTO.Closed.class, this.service.close(id, new DiscordTicketDTO.CloseRequest(3, "5555"), BOT));

        assertEquals(2, closed.protocolVersion());
        assertEquals(TicketState.CLOSED, closed.ticket().state());
        assertTrue(closed.ticket().transcriptComplete());
        assertEquals(List.of(APPEAL + ":true"), this.generator.generated, "the protocol is generated forced, in the same request");
        assertEquals(1, this.events.eventsOfType("discord.ticket_closed").size());
    }

    @Test
    void aProofOffByOneIs409AppendsNothingAndStoresNoProtocol() {
        UUID id = openChannelTicket();
        this.store.tally = new DiscordTicketStore.Tally(3, 5555, 0, 0);

        DiscordTicketDTO.Error error = assertInstanceOf(DiscordTicketDTO.Error.class, this.service.close(id, new DiscordTicketDTO.CloseRequest(2, "5555"), BOT));

        assertEquals(Code.TRANSCRIPT_MISMATCH, error.code());
        assertEquals(3, error.backendCount(), "the bot sees the backend's tally");
        assertEquals("5555", error.backendLastMessageId());
        assertFalse(this.store.appended.contains("ticket.transcript_complete"));
        assertTrue(this.generator.generated.isEmpty());
    }

    @Test
    void aWrongLastMessageIdIs409() {
        UUID id = openChannelTicket();
        this.store.tally = new DiscordTicketStore.Tally(3, 5555, 0, 0);

        DiscordTicketDTO.Error error = assertInstanceOf(DiscordTicketDTO.Error.class, this.service.close(id, new DiscordTicketDTO.CloseRequest(3, "5554"), BOT));

        assertEquals(Code.TRANSCRIPT_MISMATCH, error.code());
    }

    @Test
    void anEmptyChannelCanBeClosedWithCountZero() {
        UUID id = openChannelTicket();
        this.store.tally = new DiscordTicketStore.Tally(0, 0, 0, 0);

        assertInstanceOf(DiscordTicketDTO.Closed.class, this.service.close(id, new DiscordTicketDTO.CloseRequest(0, null), BOT));
    }

    @Test
    void whenTheProtocolCannotBeStoredCloseAnswers503AndTheEventStaysForARetry() {
        UUID id = openChannelTicket();
        this.store.tally = new DiscordTicketStore.Tally(1, 7, 0, 0);
        this.generator.answer = AppealProtocolGenerator.Result.of(AppealProtocolGenerator.Outcome.FAILED);

        DiscordTicketDTO.Error failed = assertInstanceOf(DiscordTicketDTO.Error.class, this.service.close(id, new DiscordTicketDTO.CloseRequest(1, "7"), BOT));
        this.generator.answer = new AppealProtocolGenerator.Result(AppealProtocolGenerator.Outcome.STORED, 1, UUID.randomUUID(), 2);
        DiscordTicketDTO.Closed retried = assertInstanceOf(DiscordTicketDTO.Closed.class, this.service.close(id, new DiscordTicketDTO.CloseRequest(1, "7"), BOT));

        assertEquals(ErrorKind.UNAVAILABLE, failed.kind());
        assertEquals(1, retried.protocolVersion());
        assertEquals(1, this.store.appended.stream().filter("ticket.transcript_complete"::equals).count(), "the event was recorded once");
    }

    @Test
    void tooLargeAProtocolAlsoAnswers503() {
        UUID id = openChannelTicket();
        this.store.tally = new DiscordTicketStore.Tally(0, 0, 0, 0);
        this.generator.answer = AppealProtocolGenerator.Result.of(AppealProtocolGenerator.Outcome.TOO_LARGE);

        assertEquals(ErrorKind.UNAVAILABLE, assertInstanceOf(DiscordTicketDTO.Error.class, this.service.close(id, new DiscordTicketDTO.CloseRequest(0, null), BOT)).kind());
    }

    @Test
    void closingAnUndecidedAppealCompletesTheTranscriptButNamesNoProtocolVersion() {
        UUID id = openChannelTicket();
        this.store.tally = new DiscordTicketStore.Tally(0, 0, 0, 0);
        this.generator.answer = AppealProtocolGenerator.Result.of(AppealProtocolGenerator.Outcome.NOT_DECIDED);

        DiscordTicketDTO.Closed closed = assertInstanceOf(DiscordTicketDTO.Closed.class, this.service.close(id, new DiscordTicketDTO.CloseRequest(0, null), BOT));

        assertNull(closed.protocolVersion(), "the bot must not archive without a version");
    }

    @Test
    void closeCannotStartWhileTheChannelIsPending() {
        UUID id = openTicket();

        assertEquals(Code.INVALID_STATE, assertInstanceOf(DiscordTicketDTO.Error.class, this.service.close(id, new DiscordTicketDTO.CloseRequest(0, null), BOT)).code());
    }

    // ---- gates ----

    @Test
    void archivingWithoutAStoredProtocolIs409ProtocolRequiredAndTheStateStays() {
        UUID id = closedTicket();

        DiscordTicketDTO.Error error = assertInstanceOf(DiscordTicketDTO.Error.class, patchState(id, TicketState.ARCHIVED));

        assertEquals(ErrorKind.CONFLICT, error.kind());
        assertEquals(Code.PROTOCOL_REQUIRED, error.code());
        assertEquals(TicketState.CLOSED, this.store.tickets.get(id).state());
    }

    @Test
    void archivingWithAStoredProtocolWorksAndIsAnnounced() {
        UUID id = closedTicket();
        givenStoredProtocol(1);

        DiscordTicketDTO.Ticket archived = assertInstanceOf(DiscordTicketDTO.Ticket.class, patchState(id, TicketState.ARCHIVED));

        assertEquals(TicketState.ARCHIVED, archived.state());
        assertEquals(1, archived.protocolVersion());
        assertEquals(1, this.events.eventsOfType("discord.ticket_archived").size());
    }

    @Test
    void deletingWithAnOutdatedProtocolIs409UntilItIsCurrent() {
        UUID id = closedTicket();
        givenStoredProtocol(1);
        patchState(id, TicketState.ARCHIVED);
        this.generator.current = false;

        DiscordTicketDTO.Error refused = assertInstanceOf(DiscordTicketDTO.Error.class, patchState(id, TicketState.DELETED));
        this.generator.current = true;
        DiscordTicketDTO.Ticket deleted = assertInstanceOf(DiscordTicketDTO.Ticket.class, patchState(id, TicketState.DELETED));

        assertEquals(Code.PROTOCOL_REQUIRED, refused.code());
        assertEquals(TicketState.DELETED, deleted.state());
        assertEquals(1, this.events.eventsOfType("discord.ticket_deleted").size());
    }

    @Test
    void deletingWithoutAnyProtocolIs409() {
        UUID id = closedTicket();
        patchState(id, TicketState.CLOSED);
        this.protocolStore.rows.clear();

        assertEquals(Code.PROTOCOL_REQUIRED, assertInstanceOf(DiscordTicketDTO.Error.class, patchState(id, TicketState.DELETED)).code());
    }

    @Test
    void aChannelDeletedByAPersonIsRecordedWithoutTheProtocolGate() {
        UUID id = openChannelTicket();

        DiscordTicketDTO.Ticket gone = assertInstanceOf(DiscordTicketDTO.Ticket.class,
                this.service.patch(id, new DiscordTicketDTO.PatchRequest(null, null, null, null, true), BOT));

        assertEquals(TicketState.DELETED, gone.state());
        assertTrue(this.store.appended.contains("ticket.channel_deleted"));
    }

    @Test
    void aProvenCloseThatTheBotRepeatsAfterTheArchiveIsIdempotent() {
        UUID id = closedTicket();
        givenStoredProtocol(1);
        patchState(id, TicketState.ARCHIVED);

        DiscordTicketDTO.Closed again = assertInstanceOf(DiscordTicketDTO.Closed.class, this.service.close(id, new DiscordTicketDTO.CloseRequest(2, "1234"), BOT));

        assertEquals(TicketState.ARCHIVED, again.ticket().state());
        assertEquals(1, this.events.eventsOfType("discord.ticket_closed").size(), "announced once");
    }

    // ---- protocol/ensure ----

    @Test
    void ensureNamesTheCurrentVersionAndSaysWhetherItStoredOne() {
        UUID id = closedTicket();
        this.generator.answer = new AppealProtocolGenerator.Result(AppealProtocolGenerator.Outcome.STORED, 2, UUID.randomUUID(), 5);

        DiscordTicketDTO.ProtocolEnsured ensured = assertInstanceOf(DiscordTicketDTO.ProtocolEnsured.class, this.service.ensureProtocol(id));

        assertEquals(2, ensured.protocolVersion());
        assertTrue(ensured.stored());
    }

    @Test
    void ensureForAnUndecidedAppealIs409ProtocolRequired() {
        UUID id = closedTicket();
        this.generator.answer = AppealProtocolGenerator.Result.of(AppealProtocolGenerator.Outcome.NOT_DECIDED);

        assertEquals(Code.PROTOCOL_REQUIRED, assertInstanceOf(DiscordTicketDTO.Error.class, this.service.ensureProtocol(id)).code());
    }

    // ---- relay ----

    @Test
    void linkingAdvancesTheCursorAndNeverMovesItBack() {
        UUID id = openChannelTicket();

        DiscordTicketDTO.Linked first = assertInstanceOf(DiscordTicketDTO.Linked.class,
                this.service.link(id, new DiscordTicketDTO.LinkRequest(10, UUID.randomUUID(), "123456789012345678")));
        DiscordTicketDTO.Linked back = assertInstanceOf(DiscordTicketDTO.Linked.class, this.service.link(id, new DiscordTicketDTO.LinkRequest(4, null, null)));

        assertEquals(10, first.relayedSeq());
        assertEquals(10, back.relayedSeq());
    }

    @Test
    void halfALinkIsRefused() {
        UUID id = openChannelTicket();

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(DiscordTicketDTO.Error.class,
                this.service.link(id, new DiscordTicketDTO.LinkRequest(1, UUID.randomUUID(), null))).kind());
    }

    @Test
    void theOutboxStartsAtTheTicketCursorAndHidesNoSeq() {
        UUID id = openChannelTicket();
        this.store.outbox = List.of(
                new DiscordTicketStore.OutboxItem(5, DiscordTicketStore.OutboxItem.Kind.ADD, "m1", null, null, "hi", null, 1L, List.of()),
                new DiscordTicketStore.OutboxItem(9, DiscordTicketStore.OutboxItem.Kind.ADD, "m2", null, null, "there", null, 2L, List.of()));
        this.service.link(id, new DiscordTicketDTO.LinkRequest(5, null, null));

        DiscordTicketDTO.Outbox outbox = assertInstanceOf(DiscordTicketDTO.Outbox.class, this.service.outbox(id, null, null));

        assertEquals(List.of(9L), outbox.items().stream().map(DiscordTicketDTO.OutboxItem::seq).toList());
        assertEquals(5, outbox.relayedSeq());
    }

    @Test
    void anUnknownTicketIs404() {
        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(DiscordTicketDTO.Error.class, this.service.get(UUID.randomUUID())).kind());
        assertNotNull(this.service.list(Set.of()));
    }

    // ---- close on behalf of a staff member ----

    /** Gives the ticket's appeal a status, so the decision gate of a delegated close can be exercised. */
    private void givenAppealWithStatus(AppealStatus status) {
        AppealEntity appeal = new AppealEntity(this.punishments.repository().findById(PUNISHMENT).orElseThrow(), OWNER, "please", status,
                new HashMap<>(), 0L, 0L, new HashMap<>());
        this.appealRows.given(appeal, APPEAL);
    }

    @Test
    void aCloseOnBehalfOfAStaffMemberAfterTheDecisionIsRecordedUnderThatMember() {
        this.linked.givenLinkedStaff(STAFF, STAFF_DISCORD, "MODERATOR", this.clock.millis());
        UUID id = openChannelTicket();
        givenAppealWithStatus(AppealStatus.DENIED);
        this.store.tally = new DiscordTicketStore.Tally(3, 5555, 1, 0);
        this.generator.answer = new AppealProtocolGenerator.Result(AppealProtocolGenerator.Outcome.STORED, 2, UUID.randomUUID(), 4);

        DiscordTicketDTO.Closed closed = assertInstanceOf(DiscordTicketDTO.Closed.class,
                this.service.close(id, new DiscordTicketDTO.CloseRequest(3, "5555", STAFF), DISCORD_BOT));

        assertEquals(TicketState.CLOSED, closed.ticket().state());
        var closes = this.auditStore.rows().stream().filter(row -> row.action().equals("ticket.close")).toList();
        assertEquals(1, closes.size(), "one ticket.close entry for the success");
        assertEquals(STAFF, closes.getFirst().actorOid(), "the close is recorded under the staff member");
        assertEquals(DISCORD_BOT.oid(), closes.getFirst().recordedByOid(), "the bot recorded it");
    }

    @Test
    void aCloseOnBehalfOfBeforeTheDecisionIs409AndNoStaffMemberIsRecorded() {
        this.linked.givenLinkedStaff(STAFF, STAFF_DISCORD, "MODERATOR", this.clock.millis());
        UUID id = openChannelTicket();
        givenAppealWithStatus(AppealStatus.ELIGIBLE_PENDING_REVIEW);
        this.store.tally = new DiscordTicketStore.Tally(3, 5555, 1, 0);

        DiscordTicketDTO.Error error = assertInstanceOf(DiscordTicketDTO.Error.class,
                this.service.close(id, new DiscordTicketDTO.CloseRequest(3, "5555", STAFF), DISCORD_BOT));

        assertEquals(Code.INVALID_STATE, error.code(), "the appeal is not decided yet");
        assertTrue(this.auditStore.rows().isEmpty(), "no staff member is recorded as closing it");
        assertTrue(this.generator.generated.isEmpty(), "no protocol is generated");
    }

    // ---- a leak thaw never lifts a staff lock ----

    @Test
    void aLeakThawDoesNotLiftAStaffLock() {
        UUID id = openChannelTicket();
        this.store.setFrozen(id, true, DiscordTicket.FREEZE_STAFF, this.clock.millis());

        this.service.patch(id, new DiscordTicketDTO.PatchRequest(null, null, null, false, null), BOT);

        DiscordTicket ticket = this.store.ticket(id).orElseThrow();
        assertTrue(ticket.frozen(), "the staff lock survives the leak thaw");
        assertEquals(DiscordTicket.FREEZE_STAFF, ticket.freezeSource());
    }

    @Test
    void aLeakThawLiftsALeakFreeze() {
        UUID id = openChannelTicket();
        this.store.setFrozen(id, true, DiscordTicket.FREEZE_LEAK, this.clock.millis());

        this.service.patch(id, new DiscordTicketDTO.PatchRequest(null, null, null, false, null), BOT);

        assertFalse(this.store.ticket(id).orElseThrow().frozen(), "the leak freeze is thawed by the leak path");
    }
}
