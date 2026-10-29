package net.onelitefeather.blackhole.backend.discord;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.casetimeline.CaseDatabase;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.ChatFiles;
import net.onelitefeather.blackhole.backend.casetimeline.JdbcCaseWriter;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordTicketDTO;
import net.onelitefeather.blackhole.backend.discord.ingest.DiscordIngestService;
import net.onelitefeather.blackhole.backend.discord.ingest.JdbcDiscordIngestStore;
import net.onelitefeather.blackhole.backend.discord.ticket.DiscordTicket;
import net.onelitefeather.blackhole.backend.discord.ticket.DiscordTicketStore;
import net.onelitefeather.blackhole.backend.discord.ticket.JdbcDiscordTicketStore;
import net.onelitefeather.blackhole.backend.discord.ticket.Participant;
import net.onelitefeather.blackhole.backend.discord.ticket.ParticipantKind;
import net.onelitefeather.blackhole.backend.discord.ticket.TicketState;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.DiscordLinkTestBed;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ticket and ingest statements against a real PostgreSQL schema: constraints, cascade, one statement per change, dedupe, edits, deletes,
 * the rescan, the completeness proof and the outbox. Each test migrates a schema of its own; the clock is a {@link TestClock}.
 */
@Tag("integration")
class DiscordTicketJdbcIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "ab".repeat(64);
    private static final String ID = "ticket-case-1";
    private static final UUID APPEAL = UUID.fromString("a99ea100-0000-4000-8000-000000000001");
    private static final UUID BOT_OID = UUID.fromString("b07b07b0-0000-4000-8000-000000000001");
    private static final Actor BOT = new Actor(BOT_OID, "bot", ActorKind.SERVICE, false);
    private static final String PLAYER = "111111111111111111";
    private static final String STAFF = "222222222222222222";
    private static final String STRANGER = "333333333333333333";
    private static final UUID STAFF_OID = UUID.fromString("5aff0000-0000-4000-8000-000000000001");

    private final TestClock clock = new TestClock();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final DiscordLinkTestBed links = new DiscordLinkTestBed(this.events, this.clock);
    private final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier).build();
    private CaseDatabase db;
    private JdbcDiscordTicketStore tickets;
    private DiscordIngestService ingest;
    private DiscordTicket ticket;
    private Participant player;

    @BeforeEach
    void givenAMigratedSchemaAndAnOpenTicket() {
        this.db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        this.tickets = new JdbcDiscordTicketStore(this.db.dataSource);
        this.ingest = new DiscordIngestService(this.tickets, new JdbcDiscordIngestStore(this.db.dataSource), this.links.links.repository(),
                this.punishments.repository(), this.events, this.clock, DiscordTicketProperties.defaults(), null, ChatFiles.NONE);
        this.punishments.given(new PunishmentEntity(ID, UUID.randomUUID(), OWNER, "network", false, 1L, null, new HashMap<>()), ID);
        this.db.execute("INSERT INTO punishments (identifier, source, recorded_by_oid, owner, section_key, permanent, lifted, created_at, short_id, "
                + "meta_data, ban_id_hash, ban_id_enc) VALUES (?, ?, ?, ?, 'network', false, false, 1, 'AAAAAAA1', '{}'::jsonb, ?, ?)",
                ID, BOT_OID, BOT_OID, OWNER, ID.getBytes(), new byte[]{1});
        new JdbcCaseWriter(this.db.dataSource).ensureCase(ID, 10L);
        this.player = this.tickets.ensureParticipant(ID, ParticipantKind.PLAYER, PLAYER, "Player", false, 10L);
        this.ticket = this.tickets.open(new DiscordTicketStore.NewTicket(UUID.randomUUID(), ID, APPEAL, this.player.id(), 10L, BOT_OID));
        this.tickets.channelReady(this.ticket.id(), "900000000000000001", "800000000000000001", 11L);
        this.ticket = this.tickets.ticket(this.ticket.id()).orElseThrow();
        this.links.givenLink(DiscordLinkKind.STAFF, STAFF_OID.toString(), STAFF);
    }

    private static DiscordTicketDTO.IngestItem item(long id, String author, boolean staffRole, String text, long at) {
        return new DiscordTicketDTO.IngestItem(Long.toString(id), author, "Name", false, staffRole, at, null, text, null, null);
    }

    private DiscordTicketDTO.Ingested post(DiscordTicketDTO.IngestItem... items) {
        return assertInstanceOf(DiscordTicketDTO.Ingested.class,
                this.ingest.ingest(this.ticket.id(), new DiscordTicketDTO.IngestRequest(List.of(items), null, false, null), BOT));
    }

    private List<Object> types() {
        return this.db.column("SELECT type FROM case_event WHERE punishment_identifier = ? ORDER BY seq", ID);
    }

    private long count(String type) {
        return this.db.count("SELECT count(*) FROM case_event WHERE punishment_identifier = ? AND type = ?", ID, type);
    }

    private String payloadOf(String type) {
        return this.db.text("SELECT payload::text FROM case_event WHERE punishment_identifier = ? AND type = ? ORDER BY seq LIMIT 1", ID, type);
    }

    // ---- schema ----

    @Test
    void openingAppendsTicketOpenedWithTheNumberAndNoDiscordId() {
        assertEquals(1, count("ticket.opened"));
        String payload = payloadOf("ticket.opened");
        assertTrue(payload.contains("\"ticketNo\""), payload);
        assertFalse(payload.contains(PLAYER), "no Discord id in an event");
        assertEquals("PLAYER", this.db.text("SELECT visibility FROM case_event WHERE type = 'ticket.opened' AND punishment_identifier = ?", ID));
    }

    @Test
    void aStaffLockAndUnlockEachAppendTheirTicketFrozenEventInTheSameStatement() {
        CaseActor staff = new CaseActor(ActorTrust.VERIFIED, STAFF_OID, STAFF_OID, null);
        this.tickets.setStaffFrozen(this.ticket.id(), true, 20L, staff);
        this.tickets.setStaffFrozen(this.ticket.id(), false, 21L, staff);

        assertEquals(2, count("ticket.frozen"), "one event per change");
        assertEquals("STAFF", this.db.text("SELECT payload ->> 'freezeSource' FROM case_event WHERE type = 'ticket.frozen' AND punishment_identifier = ? "
                + "ORDER BY seq LIMIT 1", ID), "the lock names its source");
        assertEquals(1, this.db.count("SELECT count(*) FROM case_event WHERE type = 'ticket.frozen' AND punishment_identifier = ? "
                + "AND payload ->> 'frozen' = 'false' AND payload ->> 'freezeSource' IS NULL", ID), "the unlock carries no source");
        assertEquals("STAFF", this.db.text("SELECT visibility FROM case_event WHERE type = 'ticket.frozen' AND punishment_identifier = ? "
                + "ORDER BY seq LIMIT 1", ID), "staff only");
        assertFalse(payloadOf("ticket.frozen").contains(STAFF), "no Discord id in the payload");
    }

    @Test
    void aSecondLiveTicketForTheSameAppealAndPlayerIsRefusedByTheDatabase() {
        assertThrows(DiscordTicketStore.DuplicateTicketException.class,
                () -> this.tickets.open(new DiscordTicketStore.NewTicket(UUID.randomUUID(), ID, APPEAL, this.player.id(), 12L, BOT_OID)));
        assertEquals(1, count("ticket.opened"), "the failed statement appended no event either");
    }

    @Test
    void aClosedTicketDoesNotBlockANewOneAndTicketNumbersGrow() {
        this.tickets.transition(this.ticket.id(), Set.of(TicketState.OPEN), TicketState.CLOSED, 12L);

        DiscordTicket second = this.tickets.open(new DiscordTicketStore.NewTicket(UUID.randomUUID(), ID, APPEAL, this.player.id(), 13L, BOT_OID));

        assertEquals(this.ticket.ticketNo() + 1, second.ticketNo());
    }

    @Test
    void aChannelBelongsToOneTicket() {
        DiscordTicket second = this.tickets.open(new DiscordTicketStore.NewTicket(UUID.randomUUID(), ID, UUID.randomUUID(),
                this.tickets.ensureParticipant(ID, ParticipantKind.PLAYER, STRANGER, null, false, 12L).id(), 12L, BOT_OID));

        assertThrows(DiscordTicketStore.ChannelTakenException.class, () -> this.tickets.channelReady(second.id(), "900000000000000001", null, 13L));
    }

    @Test
    void deletingThePunishmentRemovesParticipantsTicketsAndLinks() {
        post(item(1000, PLAYER, false, "hello", 100));

        this.db.execute("DELETE FROM punishments WHERE identifier = ?", ID);

        assertEquals(0, this.db.count("SELECT count(*) FROM case_participant"));
        assertEquals(0, this.db.count("SELECT count(*) FROM discord_ticket"));
        assertEquals(0, this.db.count("SELECT count(*) FROM discord_message_link"));
    }

    @Test
    void erasingAParticipantRemovesTheDiscordIdAndNameAndKeepsTheRow() {
        assertTrue(this.tickets.eraseParticipant(ID, this.player.id(), 50L));

        Participant erased = this.tickets.participant(this.player.id()).orElseThrow();
        assertEquals(null, erased.discordUserId());
        assertEquals(null, erased.displayName());
        assertEquals(50L, erased.erasedAt());
        assertFalse(this.tickets.eraseParticipant(ID, this.player.id(), 60L), "erasing twice changes nothing");
    }

    // ---- ingest ----

    @Test
    void aPlayerMessageIsRecordedWithTheDiscordTimeTheBoundParticipantAndPlayerTrust() {
        DiscordTicketDTO.Ingested result = post(item(1000, PLAYER, false, "I did nothing", 5_000));

        assertEquals(1, result.added());
        assertEquals("PLAYER", this.db.text("SELECT actor_trust FROM case_event WHERE type = 'message.added' AND punishment_identifier = ?", ID));
        assertEquals(5_000L, this.db.count("SELECT occurred_at FROM case_event WHERE type = 'message.added' AND punishment_identifier = ?", ID));
        String payload = payloadOf("message.added");
        assertTrue(payload.contains("\"channel\": \"DISCORD\""), payload);
        assertTrue(payload.contains(this.player.id().toString()), "the opaque participant id");
        assertFalse(payload.contains(PLAYER), "the Discord id is never in the payload");
        assertEquals("1000", result.ingestedCursor());
    }

    @Test
    void theSameBatchTwiceRecordsOneEventPerMessage() {
        post(item(1000, PLAYER, false, "once", 100), item(1001, STAFF, true, "twice", 200));

        DiscordTicketDTO.Ingested again = post(item(1000, PLAYER, false, "once", 100), item(1001, STAFF, true, "twice", 200));

        assertEquals(0, again.added());
        assertEquals(2, again.unchanged());
        assertEquals(2, count("message.added"));
        assertEquals(2, this.db.count("SELECT count(*) FROM discord_message_link WHERE ticket_id = ?", this.ticket.id()));
    }

    @Test
    void aLinkedStaffMemberWithTheRoleIsVerifiedWithTheLinkedOidAndNoParticipant() {
        post(item(1000, STAFF, true, "we looked", 100));

        assertEquals("VERIFIED", this.db.text("SELECT actor_trust FROM case_event WHERE type = 'message.added' AND punishment_identifier = ?", ID));
        assertEquals(STAFF_OID.toString(), this.db.text("SELECT actor_oid::text FROM case_event WHERE type = 'message.added' AND punishment_identifier = ?", ID));
        assertEquals(0, this.db.count("SELECT count(*) FROM case_participant WHERE discord_user_id = ?", STAFF),
                "a verified staff member is identified by the link; no Discord id is stored for the message");
    }

    @Test
    void aStaffRoleHolderWithoutALinkIsUnverifiedAndAnExternalUserHasNoTrust() {
        post(item(1000, "444444444444444444", true, "hello from staff", 100), item(1001, STRANGER, false, "hi", 200));

        assertEquals(List.of("UNVERIFIED", "UNVERIFIED"), this.db.column(
                "SELECT actor_trust FROM case_event WHERE type = 'message.added' AND punishment_identifier = ? ORDER BY seq", ID));
        assertEquals(List.of("STAFF", "EXTERNAL"), this.db.column(
                "SELECT payload -> 'author' ->> 'kind' FROM case_event WHERE type = 'message.added' AND punishment_identifier = ? ORDER BY seq", ID));
    }

    @Test
    void anotherBotIsRecordedAsBotWithStaffVisibility() {
        post(new DiscordTicketDTO.IngestItem("1000", STRANGER, "Helper", true, false, 100, null, "automation", null, null));

        assertEquals("STAFF", this.db.text("SELECT visibility FROM case_event WHERE type = 'message.added' AND punishment_identifier = ?", ID));
        assertTrue(payloadOf("message.added").contains("\"BOT\""));
    }

    @Test
    void anUpdateWithTheSameTextAppendsNoEditAndADifferentTextDoes() {
        post(item(1000, PLAYER, false, "first", 100));

        DiscordTicketDTO.Ingested same = post(item(1000, PLAYER, false, "first", 100));
        DiscordTicketDTO.Ingested changed = post(item(1000, PLAYER, false, "second", 100));
        DiscordTicketDTO.Ingested replay = post(item(1000, PLAYER, false, "second", 100));

        assertEquals(0, same.edited());
        assertEquals(1, changed.edited());
        assertEquals(0, replay.edited());
        assertEquals(1, count("message.edited"));
        assertTrue(payloadOf("message.edited").contains("second"), "the full new text");
        assertTrue(payloadOf("message.added").contains("first"), "the original stays in the record");
    }

    @Test
    void anEditKeepsTheAuthorTrustAndVisibilityOfTheOriginal() {
        post(item(1000, PLAYER, false, "first", 100));
        post(item(1000, PLAYER, false, "second", 100));

        assertEquals(List.of("PLAYER", "PLAYER"), this.db.column(
                "SELECT actor_trust FROM case_event WHERE punishment_identifier = ? AND type IN ('message.added','message.edited') ORDER BY seq", ID));
    }

    @Test
    void aDeleteKeepsTheLastTextReadableAndIsRecordedOnce() {
        post(item(1000, PLAYER, false, "I will regret this", 100));

        DiscordTicketDTO.Ingested first = assertInstanceOf(DiscordTicketDTO.Ingested.class,
                this.ingest.ingest(this.ticket.id(), new DiscordTicketDTO.IngestRequest(null, List.of("1000"), false, null), BOT));
        DiscordTicketDTO.Ingested again = assertInstanceOf(DiscordTicketDTO.Ingested.class,
                this.ingest.ingest(this.ticket.id(), new DiscordTicketDTO.IngestRequest(null, List.of("1000"), false, null), BOT));

        assertEquals(1, first.deleted());
        assertEquals(0, again.deleted());
        assertEquals(1, count("message.deleted"));
        assertTrue(payloadOf("message.added").contains("I will regret this"));
        assertFalse(payloadOf("message.deleted").contains("contentUnavailable"));
    }

    @Test
    void aDeleteOfAnUnrecordedMessageIsMarkedContentUnavailable() {
        this.ingest.ingest(this.ticket.id(), new DiscordTicketDTO.IngestRequest(null, List.of("777"), false, null), BOT);

        assertEquals(1, count("message.deleted"));
        assertTrue(payloadOf("message.deleted").contains("\"contentUnavailable\": true"), payloadOf("message.deleted"));
        assertEquals("STAFF", this.db.text("SELECT visibility FROM case_event WHERE type = 'message.deleted' AND punishment_identifier = ?", ID));
    }

    @Test
    void aFullRescanInsertsMissingEditsChangedAndMarksVanishedMessagesAsDetectedAtCatchup() {
        post(item(1000, PLAYER, false, "stays", 100), item(1001, PLAYER, false, "will change", 200), item(1002, PLAYER, false, "will vanish", 300));

        DiscordTicketDTO.Ingested rescan = assertInstanceOf(DiscordTicketDTO.Ingested.class, this.ingest.ingest(this.ticket.id(),
                new DiscordTicketDTO.IngestRequest(List.of(item(1000, PLAYER, false, "stays", 100), item(1001, PLAYER, false, "changed while down", 200),
                        item(1003, STAFF, true, "written while down", 400)), null, true, List.of("1000", "1001", "1003")), BOT));

        assertEquals(1, rescan.added());
        assertEquals(1, rescan.edited());
        assertEquals(1, rescan.deleted());
        assertTrue(payloadOf("message.deleted").contains("\"detectedAtCatchup\": true"), payloadOf("message.deleted"));
        assertFalse(payloadOf("message.deleted").contains("contentUnavailable"), "the text of the vanished message is kept");
    }

    @Test
    void attachmentsAreStoredAsMetadataWithoutAnyUrl() {
        post(new DiscordTicketDTO.IngestItem("1000", STAFF, "Staff", false, true, 100, null, "see file", List.of(
                new DiscordTicketDTO.IngestAttachment("file", "5000", "log.txt", "text/plain", 2048L, null, null, null, null),
                new DiscordTicketDTO.IngestAttachment("sticker", null, "wave", null, null, null, null, null, null)),
                List.of(new DiscordTicketDTO.IngestEmbed("link", "example.org"))));

        String payload = payloadOf("message.added");
        assertTrue(payload.contains("log.txt") && payload.contains("2048") && payload.contains("example.org") && payload.contains("wave"), payload);
        assertFalse(payload.contains("http"), "no CDN URL");
    }

    @Test
    void aPlayerMessageMovesAWaitingCaseToInReviewOnce() {
        this.db.execute("UPDATE punishment_case SET status = 'WAITING' WHERE punishment_identifier = ?", ID);

        post(item(1000, PLAYER, false, "hello", 100));
        post(item(1001, PLAYER, false, "again", 200));

        assertEquals("IN_REVIEW", this.db.text("SELECT status FROM punishment_case WHERE punishment_identifier = ?", ID));
        assertEquals(1, count("case.status_changed"));
    }

    @Test
    void aMalformedBatchRecordsNothing() {
        DiscordTicketDTO result = this.ingest.ingest(this.ticket.id(), new DiscordTicketDTO.IngestRequest(
                List.of(item(1000, PLAYER, false, "fine", 100), item(1001, "not-a-user", false, "bad", 100)), null, false, null), BOT);

        assertInstanceOf(DiscordTicketDTO.Error.class, result);
        assertEquals(0, count("message.added"));
    }

    @Test
    void nothingInTheEventsOrTheLogIsADiscordUserIdOrAnAuthorName() {
        post(item(1000, PLAYER, false, "hello", 100), item(1001, STRANGER, false, "hi", 200));

        assertEquals(0, this.db.count("SELECT count(*) FROM case_event WHERE punishment_identifier = ? AND (payload::text LIKE ? OR payload::text LIKE ?)",
                ID, "%" + PLAYER + "%", "%" + STRANGER + "%"));
        assertEquals(0, this.events.events().stream().filter(e -> e.payload().toString().contains(PLAYER) || e.payload().toString().contains(STRANGER)).count());
    }

    // ---- completeness ----

    @Test
    void theProofIsAcceptedOnlyWhenCountAndLastIdMatchTheTally() {
        post(item(1000, PLAYER, false, "a", 100), item(1001, PLAYER, false, "b", 200));

        assertFalse(this.tickets.complete(this.ticket.id(), 1, 1001, 300L, BOT_OID), "count one lower");
        assertFalse(this.tickets.complete(this.ticket.id(), 2, 1000, 300L, BOT_OID), "last id not the highest");
        assertEquals(0, count("ticket.transcript_complete"));
        assertTrue(this.tickets.complete(this.ticket.id(), 2, 1001, 300L, BOT_OID));

        DiscordTicket closed = this.tickets.ticket(this.ticket.id()).orElseThrow();
        assertEquals(TicketState.CLOSED, closed.state());
        assertEquals(2, closed.transcriptCount());
        assertEquals(1001L, closed.transcriptLastMessageId());
        assertEquals(1, count("ticket.transcript_complete"));
        assertTrue(payloadOf("ticket.transcript_complete").contains("\"lastMessageId\": \"1001\""), payloadOf("ticket.transcript_complete"));
    }

    @Test
    void theProofIsRecordedOnceHoweverOftenItIsSent() {
        assertTrue(this.tickets.complete(this.ticket.id(), 0, 0, 300L, BOT_OID));

        assertFalse(this.tickets.complete(this.ticket.id(), 0, 0, 400L, BOT_OID));
        assertEquals(1, count("ticket.transcript_complete"));
    }

    @Test
    void deletedMessagesAreNotCountedAndTheEventCountsEditsAndDeletes() {
        post(item(1000, PLAYER, false, "a", 100), item(1001, PLAYER, false, "b", 200));
        post(item(1000, PLAYER, false, "a edited", 100));
        this.ingest.ingest(this.ticket.id(), new DiscordTicketDTO.IngestRequest(null, List.of("1001"), false, null), BOT);

        assertTrue(this.tickets.complete(this.ticket.id(), 1, 1000, 300L, BOT_OID));

        String payload = payloadOf("ticket.transcript_complete");
        assertTrue(payload.contains("\"edits\": 1") && payload.contains("\"deletes\": 1"), payload);
    }

    // ---- archive and delete events ----

    @Test
    void archivingAndDeletingAppendTheirEventsOnce() {
        this.tickets.complete(this.ticket.id(), 0, 0, 300L, BOT_OID);

        assertTrue(this.tickets.archive(this.ticket.id(), 3, 400L, BOT_OID).isPresent());
        assertTrue(this.tickets.archive(this.ticket.id(), 3, 410L, BOT_OID).isEmpty(), "not closed any more");
        assertTrue(this.tickets.deleted(this.ticket.id(), 500L, BOT_OID).isPresent());
        assertTrue(this.tickets.deleted(this.ticket.id(), 510L, BOT_OID).isEmpty());

        assertEquals(1, count("ticket.archived"));
        assertEquals(1, count("ticket.channel_deleted"));
        assertTrue(payloadOf("ticket.channel_deleted").contains("\"transcriptComplete\": true"));
    }

    @Test
    void aChannelDeletedBeforeCompletionIsRecordedAsIncompleteWithTheLastMessageTime() {
        post(item(1000, PLAYER, false, "a", 4_321));

        this.tickets.deleted(this.ticket.id(), 500L, BOT_OID);

        String payload = payloadOf("ticket.channel_deleted");
        assertTrue(payload.contains("\"transcriptComplete\": false") && payload.contains("4321"), payload);
    }

    // ---- outbox ----

    private String panelMessage(long at, String text) {
        UUID id = UUID.randomUUID();
        this.db.execute("INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust, subject_id, "
                + "visibility, payload) VALUES (?, ?, 'message.added', 1, ?, 'LIVE', 'VERIFIED', ?, 'PLAYER', ?::jsonb)",
                UUID.randomUUID(), ID, at, id.toString(),
                "{\"messageId\":\"" + id + "\",\"author\":{\"kind\":\"STAFF\"},\"channel\":\"PANEL\",\"text\":\"" + text + "\"}");
        return id.toString();
    }

    private void editPanel(String messageId, long at, String text) {
        this.db.execute("INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust, subject_id, "
                + "visibility, payload) VALUES (?, ?, 'message.edited', 1, ?, 'LIVE', 'VERIFIED', ?, 'PLAYER', ?::jsonb)",
                UUID.randomUUID(), ID, at, messageId, "{\"messageId\":\"" + messageId + "\",\"text\":\"" + text + "\"}");
    }

    @Test
    void theOutboxHoldsPanelMessagesInOrderAndNeverAMessageWrittenInDiscord() {
        panelMessage(100, "from the team");
        post(item(1000, PLAYER, false, "from discord", 200));
        panelMessage(300, "second from the team");

        List<DiscordTicketStore.OutboxItem> outbox = this.tickets.outbox(this.ticket.id(), 0, 50);

        assertEquals(List.of("from the team", "second from the team"), outbox.stream().map(DiscordTicketStore.OutboxItem::text).toList());
        assertTrue(outbox.getFirst().seq() < outbox.getLast().seq());
        assertTrue(outbox.stream().allMatch(i -> i.kind() == DiscordTicketStore.OutboxItem.Kind.ADD));
    }

    @Test
    void anEditBeforeTheRelayIsFoldedIntoTheMessageAndAfterTheLinkItIsAnEditItem() {
        String message = panelMessage(100, "original");
        editPanel(message, 150, "corrected");

        DiscordTicketStore.OutboxItem add = this.tickets.outbox(this.ticket.id(), 0, 50).getFirst();
        assertEquals(1, this.tickets.outbox(this.ticket.id(), 0, 50).size(), "the edit is not a separate item before the relay");
        assertEquals("corrected", add.text(), "the current text is relayed");

        this.tickets.acknowledge(this.ticket.id(), add.seq(), UUID.fromString(message), 5555L, 600L);
        editPanel(message, 700, "corrected again");

        DiscordTicketStore.OutboxItem edit = this.tickets.outbox(this.ticket.id(), add.seq(), 50).getFirst();
        assertEquals(DiscordTicketStore.OutboxItem.Kind.EDIT, edit.kind());
        assertEquals(5555L, edit.discordMessageId());
        assertEquals("corrected again", edit.text());
    }

    @Test
    void aMessageDeletedBeforeTheRelayIsSkippedAndOneDeletedAfterIsADeleteItem() {
        String skipped = panelMessage(100, "never relayed");
        this.db.execute("INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust, subject_id, "
                + "visibility, payload) VALUES (?, ?, 'message.deleted', 1, 120, 'LIVE', 'VERIFIED', ?, 'PLAYER', ?::jsonb)",
                UUID.randomUUID(), ID, skipped, "{\"messageId\":\"" + skipped + "\",\"deletedBy\":\"STAFF\"}");
        String kept = panelMessage(200, "relayed then removed");
        DiscordTicketStore.OutboxItem add = this.tickets.outbox(this.ticket.id(), 0, 50).getFirst();
        assertEquals("relayed then removed", add.text());
        this.tickets.acknowledge(this.ticket.id(), add.seq(), UUID.fromString(kept), 6666L, 600L);
        this.db.execute("INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust, subject_id, "
                + "visibility, payload) VALUES (?, ?, 'message.deleted', 1, 800, 'LIVE', 'VERIFIED', ?, 'PLAYER', ?::jsonb)",
                UUID.randomUUID(), ID, kept, "{\"messageId\":\"" + kept + "\",\"deletedBy\":\"STAFF\"}");

        DiscordTicketStore.OutboxItem delete = this.tickets.outbox(this.ticket.id(), add.seq(), 50).getFirst();

        assertEquals(DiscordTicketStore.OutboxItem.Kind.DELETE, delete.kind());
        assertEquals(6666L, delete.discordMessageId());
    }

    @Test
    void linkingAdvancesTheCursorMonotonicallyAndTheSameDiscordMessageIsLinkedOnce() {
        UUID message = UUID.randomUUID();

        assertEquals(10L, this.tickets.acknowledge(this.ticket.id(), 10, message, 7000L, 600L).orElseThrow());
        assertEquals(10L, this.tickets.acknowledge(this.ticket.id(), 4, message, 7000L, 700L).orElseThrow());

        assertEquals(1, this.db.count("SELECT count(*) FROM discord_message_link WHERE ticket_id = ? AND direction = 'RELAYED'", this.ticket.id()));
        assertEquals(10L, this.tickets.ticket(this.ticket.id()).orElseThrow().relayedSeq());
    }

    @Test
    void aRestartedRelayStartsAfterTheCursorAndRepostsNothing() {
        String first = panelMessage(100, "one");
        String second = panelMessage(200, "two");
        DiscordTicketStore.OutboxItem one = this.tickets.outbox(this.ticket.id(), 0, 50).getFirst();
        this.tickets.acknowledge(this.ticket.id(), one.seq(), UUID.fromString(first), 8001L, 600L);

        long cursor = this.tickets.ticket(this.ticket.id()).orElseThrow().relayedSeq();
        List<DiscordTicketStore.OutboxItem> afterRestart = this.tickets.outbox(this.ticket.id(), cursor, 50);

        assertEquals(List.of("two"), afterRestart.stream().map(DiscordTicketStore.OutboxItem::text).toList());
        assertEquals(second, afterRestart.getFirst().messageId());
    }
}
