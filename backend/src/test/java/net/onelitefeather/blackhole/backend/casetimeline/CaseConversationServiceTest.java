package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseConversationDTO;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseConversationService;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.BanIdTestBed;
import net.onelitefeather.blackhole.backend.support.FakeCaseStore;
import net.onelitefeather.blackhole.backend.support.InMemoryCaseEventRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The conversation rules over in-memory stores: visibility, trust, edits, redaction, the leak rule and the export. No database. */
class CaseConversationServiceTest {

    private static final String OWNER = "ab".repeat(64);
    private static final String ID = "BBBBBBBBBBBBBBBBBBBBBB";
    private static final Actor ALICE = new Actor(UUID.nameUUIDFromBytes("alice".getBytes()), "Alice", ActorKind.HUMAN, false);
    private static final Actor BOB = new Actor(UUID.nameUUIDFromBytes("bob".getBytes()), "Bob", ActorKind.HUMAN, false);
    private static final Actor ADMIN = new Actor(UUID.nameUUIDFromBytes("admin".getBytes()), "Admin", ActorKind.HUMAN, false, true);

    private final TestClock clock = new TestClock();
    private final InMemoryCaseEventRepository log = new InMemoryCaseEventRepository();
    private final FakeCaseStore store = new FakeCaseStore(log);
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier)
                    .finder("findByShortId", args -> this.punishments.all().stream().filter(p -> args[0].equals(p.getShortId())).toList())
                    .build();
    private final BanIdTestBed banIds = new BanIdTestBed(this.punishments.repository());
    private final CaseConversationService service = new CaseConversationService(
            this.punishments.repository(), this.store, this.store, this.log, this.events, this.clock, this.banIds.service);

    private void givenPunishment() {
        this.punishments.given(new PunishmentEntity(ID, UUID.randomUUID(), OWNER, "network", false, 1L, null, new HashMap<>()), ID);
        this.store.ensureCase(ID, 1L);
    }

    private static CaseConversationDTO.MessageRequest text(String text) {
        return new CaseConversationDTO.MessageRequest(text);
    }

    private static CaseErrorKind kindOf(CaseConversationDTO result) {
        return assertInstanceOf(CaseConversationDTO.Error.class, result).kind();
    }

    private String reply(Actor by, String text) {
        return assertInstanceOf(CaseConversationDTO.MessageResponse.class, this.service.postMessage(ID, text(text), by, null)).messageId();
    }

    private List<CaseEventRecord> ofType(String type) {
        return this.log.ofType(ID, type);
    }

    @Test
    void aPanelReplyIsAPlayerVisibleStaffMessageFromTheVerifiedActor() {
        givenPunishment();

        reply(ALICE, "Please tell us more.");

        CaseEventRecord stored = ofType("message.added").getFirst();
        assertEquals("PLAYER", stored.visibility(), "the server sets the visibility");
        assertEquals(ActorTrust.VERIFIED, stored.actorTrust());
        assertEquals(ALICE.oid(), stored.actorOid());
        CaseEventPayload.MessageAdded payload = assertInstanceOf(CaseEventPayload.MessageAdded.class, stored.payload());
        assertEquals(MessageAuthorKind.STAFF, payload.authorKind());
        assertEquals(MessageChannel.PANEL, payload.channel());
    }

    @Test
    void aPlayerMessageHasTrustPlayerNoOidAndIsVisibleToThePlayer() {
        givenPunishment();

        assertInstanceOf(CaseConversationDTO.MessageResponse.class, this.service.postPlayerMessage(ID, null, "I did not do it.", null));

        CaseEventRecord stored = ofType("message.added").getFirst();
        assertEquals(ActorTrust.PLAYER, stored.actorTrust());
        assertNull(stored.actorOid());
        assertEquals("PLAYER", stored.visibility());
        assertEquals(MessageAuthorKind.PLAYER, assertInstanceOf(CaseEventPayload.MessageAdded.class, stored.payload()).authorKind());
    }

    @Test
    void aPlayerMessageOnAWaitingCaseMovesItToInReviewBySystem() {
        givenPunishment();
        this.store.update(ID, 0, 2L, CaseActor.system(), CaseMeta.defaults(), new CaseMeta("WAITING", null, "NORMAL", List.of()));

        this.service.postPlayerMessage(ID, null, "Here is my answer.", null);

        assertEquals("IN_REVIEW", this.store.meta(ID).status());
        CaseEventRecord status = ofType("case.status_changed").getLast();
        assertEquals(new CaseEventPayload.StatusChanged("WAITING", "IN_REVIEW"), status.payload());
        assertEquals(ActorTrust.SYSTEM, status.actorTrust());
    }

    @Test
    void aStaffReplyDoesNotChangeAWaitingCase() {
        givenPunishment();
        this.store.update(ID, 0, 2L, CaseActor.system(), CaseMeta.defaults(), new CaseMeta("WAITING", null, "NORMAL", List.of()));

        reply(ALICE, "We are waiting for you.");

        assertEquals("WAITING", this.store.meta(ID).status());
    }

    @Test
    void aLeakedCaseRefusesPlayerMessagesButNotStaff() {
        givenPunishment();
        this.service.markBanIdLeaked(ID, new CaseConversationDTO.LeakRequest(BanIdLeakReason.SHARED), ALICE);

        assertEquals(CaseErrorKind.CONFLICT, kindOf(this.service.postPlayerMessage(ID, null, "hello", null)));
        assertInstanceOf(CaseConversationDTO.MessageResponse.class, this.service.postMessage(ID, text("still here"), ALICE, null));
        assertTrue(ofType("message.added").stream().noneMatch(event -> event.actorTrust() == ActorTrust.PLAYER), "no player message was stored");
    }

    @Test
    void theSameIdempotencyKeyPostsOnce() {
        givenPunishment();

        CaseConversationDTO first = this.service.postMessage(ID, text("once"), ALICE, "key-1");
        CaseConversationDTO second = this.service.postMessage(ID, text("once"), ALICE, "key-1");

        assertEquals(1, ofType("message.added").size());
        CaseConversationDTO.MessageResponse firstResponse = assertInstanceOf(CaseConversationDTO.MessageResponse.class, first);
        CaseConversationDTO.MessageResponse secondResponse = assertInstanceOf(CaseConversationDTO.MessageResponse.class, second);
        assertEquals(firstResponse.messageId(), secondResponse.messageId());
        assertEquals(firstResponse.seq(), secondResponse.seq());
        assertTrue(secondResponse.repeated());
        assertEquals(1, this.events.eventsOfType("case.message_added").size(), "a repeat publishes nothing");
    }

    @Test
    void anInvalidIdempotencyKeyIsRejected() {
        givenPunishment();

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postMessage(ID, text("x"), ALICE, "bad key with spaces")));
    }

    @Test
    void aTextWithAnIpAUuidOrABanIdIsRejectedAndNothingIsStored() {
        givenPunishment();
        String banId = this.banIds.issueFor(ID);
        this.punishments.given(new PunishmentEntity("other", UUID.randomUUID(), OWNER, "network", false, 1L, null, new HashMap<>()), "other");

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postMessage(ID, text("ip 10.0.0.1"), ALICE, null)));
        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postMessage(ID, text("uuid " + UUID.randomUUID()), ALICE, null)));
        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postMessage(ID, text("my id is #" + banId), ALICE, null)));
        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postMessage(ID, text("  "), ALICE, null)));
        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postMessage(ID, text("x".repeat(2001)), ALICE, null)));
        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.postPlayerMessage(ID, null, "ip 10.0.0.1", null)));
        assertTrue(ofType("message.added").isEmpty());
    }

    @Test
    void aFailureToStoreAMessageFailsTheRequest() {
        givenPunishment();
        this.log.failAppends(new IllegalStateException("database down"));
        CaseChanges failing = new CaseChangesFailingOnMessages();
        CaseConversationService broken = new CaseConversationService(
                this.punishments.repository(), this.store, failing, this.log, this.events, this.clock, this.banIds.service);

        assertThrows(IllegalStateException.class, () -> broken.postMessage(ID, text("lost?"), ALICE, null));
        assertTrue(this.events.events().isEmpty(), "nothing is announced for a message that was not stored");
    }

    @Test
    void anEditAppendsAnEventAndKeepsTheOriginalSoTheCurrentTextIsTheLast() {
        givenPunishment();
        String id = reply(ALICE, "first");

        this.service.editMessage(ID, UUID.fromString(id), text("second"), ALICE);
        this.service.editMessage(ID, UUID.fromString(id), text("third"), ALICE);

        assertEquals(3, this.store.subject(ID, id).size(), "added plus two edits");
        assertEquals("first", assertInstanceOf(CaseEventPayload.MessageAdded.class, this.store.subject(ID, id).getFirst().payload()).text());
        MessageThread.Message message = MessageThread.of(this.store.subject(ID, id)).getFirst();
        assertEquals("third", message.text());
        assertEquals(2, message.edits());
        assertEquals("PLAYER", this.store.subject(ID, id).getLast().visibility(), "an edit keeps the original's visibility");
    }

    @Test
    void onlyTheAuthorOrAnAdminMayEdit() {
        givenPunishment();
        UUID id = UUID.fromString(reply(ALICE, "mine"));

        assertEquals(CaseErrorKind.FORBIDDEN, kindOf(this.service.editMessage(ID, id, text("hijack"), BOB)));
        assertInstanceOf(CaseConversationDTO.MessageResponse.class, this.service.editMessage(ID, id, text("fixed by admin"), ADMIN));
    }

    @Test
    void aPlayersWordsCannotBeEditedOrDeletedByStaff() {
        givenPunishment();
        UUID id = UUID.fromString(assertInstanceOf(CaseConversationDTO.MessageResponse.class,
                this.service.postPlayerMessage(ID, null, "my statement", null)).messageId());

        assertEquals(CaseErrorKind.FORBIDDEN, kindOf(this.service.editMessage(ID, id, text("changed"), ADMIN)));
        assertEquals(CaseErrorKind.FORBIDDEN, kindOf(this.service.deleteMessage(ID, id, ADMIN)));
    }

    @Test
    void aDeletedMessageKeepsItsTextForStaffAndCannotBeEditedAgain() {
        givenPunishment();
        String id = reply(ALICE, "oops");

        assertInstanceOf(CaseConversationDTO.MessageResponse.class, this.service.deleteMessage(ID, UUID.fromString(id), ALICE));

        MessageThread.Message message = MessageThread.of(this.store.subject(ID, id)).getFirst();
        assertTrue(message.deleted());
        assertEquals("oops", message.text());
        assertEquals(CaseErrorKind.CONFLICT, kindOf(this.service.editMessage(ID, UUID.fromString(id), text("again"), ALICE)));
        assertEquals(CaseErrorKind.CONFLICT, kindOf(this.service.deleteMessage(ID, UUID.fromString(id), ALICE)));
    }

    @Test
    void anUnknownMessageIs404() {
        givenPunishment();

        assertEquals(CaseErrorKind.NOT_FOUND, kindOf(this.service.editMessage(ID, UUID.randomUUID(), text("x"), ALICE)));
        assertEquals(CaseErrorKind.NOT_FOUND, kindOf(this.service.redactMessage(ID, UUID.randomUUID(),
                new CaseConversationDTO.RedactRequest(NoteRedactionReason.OTHER), ADMIN)));
    }

    @Test
    void redactingAMessageRemovesTheTextOfEveryVersionAndAppendsOneEvent() {
        givenPunishment();
        String id = reply(ALICE, "private data");
        this.service.editMessage(ID, UUID.fromString(id), text("more private data"), ALICE);

        CaseConversationDTO result = this.service.redactMessage(ID, UUID.fromString(id),
                new CaseConversationDTO.RedactRequest(NoteRedactionReason.PERSONAL_DATA), ADMIN);
        this.service.redactMessage(ID, UUID.fromString(id), new CaseConversationDTO.RedactRequest(NoteRedactionReason.PERSONAL_DATA), ADMIN);

        assertEquals(2, assertInstanceOf(CaseConversationDTO.RedactResponse.class, result).redactedVersions());
        List<CaseEventRecord> versions = this.store.subject(ID, id).stream().filter(row -> !row.type().equals("message.redacted")).toList();
        assertTrue(versions.stream().allMatch(row -> row.redactedAt() != null && !row.body().containsKey("text")), "no text survives");
        assertTrue(versions.stream().allMatch(row -> row.body().containsKey("messageId")), "the structure stays");
        assertEquals(1, ofType("message.redacted").size(), "once per message however often it is asked");
        assertEquals(CaseErrorKind.CONFLICT, kindOf(this.service.editMessage(ID, UUID.fromString(id), text("late"), ALICE)));
    }

    @Test
    void erasingThePlayersSideRedactsPlayerMessagesAndLeavesStaffMessages() {
        givenPunishment();
        reply(ALICE, "staff words");
        this.service.postPlayerMessage(ID, null, "player one", null);
        this.service.postPlayerMessage(ID, null, "player two", null);

        CaseConversationDTO result = this.service.erasePlayerMessages(ID, new CaseConversationDTO.RedactRequest(NoteRedactionReason.ERASURE_REQUEST), ADMIN);

        assertEquals(2, assertInstanceOf(CaseConversationDTO.EraseResponse.class, result).redactedMessages());
        List<MessageThread.Message> thread = MessageThread.of(this.log.timeline(ID));
        assertEquals("staff words", thread.stream().filter(m -> m.authorKind() == MessageAuthorKind.STAFF).findFirst().orElseThrow().text());
        assertTrue(thread.stream().filter(m -> m.authorKind() == MessageAuthorKind.PLAYER).allMatch(m -> m.redacted() && m.text() == null));
        assertEquals(0, assertInstanceOf(CaseConversationDTO.EraseResponse.class,
                this.service.erasePlayerMessages(ID, new CaseConversationDTO.RedactRequest(NoteRedactionReason.ERASURE_REQUEST), ADMIN)).redactedMessages(),
                "a second erase has nothing left");
    }

    @Test
    void markingALeakRecordsItOnceAndNeverTheBanId() {
        givenPunishment();
        String banId = this.banIds.issueFor(ID);

        CaseConversationDTO first = this.service.markBanIdLeaked(ID, new CaseConversationDTO.LeakRequest(BanIdLeakReason.POSTED_PUBLIC), ALICE);
        CaseConversationDTO again = this.service.markBanIdLeaked(ID, new CaseConversationDTO.LeakRequest(BanIdLeakReason.POSTED_PUBLIC), ALICE);

        assertTrue(assertInstanceOf(CaseConversationDTO.LeakResponse.class, first).changed());
        assertFalse(assertInstanceOf(CaseConversationDTO.LeakResponse.class, again).changed());
        assertEquals(1, ofType("ban_id.leak_marked").size(), "marked twice appends nothing");
        assertFalse(String.valueOf(ofType("ban_id.leak_marked").getFirst().body()).contains(banId));
    }

    @Test
    void aReissueRetriesACollisionAndReportsGenerationsOnly() {
        givenPunishment();
        this.banIds.issueFor(ID);
        this.store.collideNext(2);

        CaseConversationDTO result = this.service.reissueBanId(ID, new CaseConversationDTO.ReissueRequest(BanIdReissueReason.NOT_PLAYERS_FAULT), ADMIN);

        assertEquals(2, assertInstanceOf(CaseConversationDTO.ReissueResponse.class, result).generation());
        assertEquals(new CaseEventPayload.BanIdReissued(BanIdReissueReason.NOT_PLAYERS_FAULT, 1, 2), ofType("ban_id.reissued").getFirst().payload());
    }

    @Test
    void aReissueClearsTheLeakState() {
        givenPunishment();
        this.banIds.issueFor(ID);
        this.service.markBanIdLeaked(ID, new CaseConversationDTO.LeakRequest(BanIdLeakReason.FOUND), ALICE);

        this.service.reissueBanId(ID, new CaseConversationDTO.ReissueRequest(BanIdReissueReason.OTHER), ADMIN);

        assertTrue(this.store.find(ID).filter(CaseReader.CaseRow::leaked).isEmpty(), "the new generation is not leaked");
        assertInstanceOf(CaseConversationDTO.MessageResponse.class, this.service.postPlayerMessage(ID, null, "I can write again", null));
    }

    @Test
    void aReissueWithoutABanIdIs409() {
        givenPunishment();
        this.store.givenNoBanId(ID);

        assertEquals(CaseErrorKind.CONFLICT,
                kindOf(this.service.reissueBanId(ID, new CaseConversationDTO.ReissueRequest(BanIdReissueReason.OTHER), ADMIN)));
    }

    @Test
    void theDomainEventsCarryIdsAndEnumsButNeverTheMessageText() {
        givenPunishment();
        String banId = this.banIds.issueFor(ID);
        String id = reply(ALICE, "secret words here");
        this.service.editMessage(ID, UUID.fromString(id), text("edited secret words"), ALICE);
        this.service.deleteMessage(ID, UUID.fromString(id), ALICE);
        this.service.redactMessage(ID, UUID.fromString(id), new CaseConversationDTO.RedactRequest(NoteRedactionReason.OTHER), ADMIN);
        this.service.markBanIdLeaked(ID, new CaseConversationDTO.LeakRequest(BanIdLeakReason.OTHER), ALICE);
        this.service.reissueBanId(ID, new CaseConversationDTO.ReissueRequest(BanIdReissueReason.OTHER), ADMIN);

        assertEquals(List.of("case.message_added", "case.message_edited", "case.message_deleted", "case.message_redacted",
                "case.ban_id_leak_marked", "case.ban_id_reissued"), this.events.events().stream().map(RecordingEventPublisher.Event::type).toList());
        for (RecordingEventPublisher.Event event : this.events.events()) {
            String rendered = event.payload().toString();
            assertFalse(rendered.contains("secret"), "no message text in " + event.type());
            assertFalse(rendered.contains(banId), "no Ban ID in " + event.type());
            assertFalse(event.payload().containsKey("text"), "no text key in " + event.type());
        }
        var added = this.events.eventsOfType("case.message_added").getFirst().payload();
        assertEquals(id, added.get("messageId"));
        assertEquals("STAFF", added.get("authorKind"));
        assertEquals("PANEL", added.get("channel"));
        assertEquals("PLAYER", added.get("visibility"));
    }

    @Test
    void aBrokenBrokerDoesNotFailAStoredMessage() {
        givenPunishment();
        net.onelitefeather.blackhole.backend.events.DomainEventPublisher broken = new net.onelitefeather.blackhole.backend.events.DomainEventPublisher(null) {
            @Override
            public void publish(String eventType, java.util.Map<String, Object> payload) {
                throw new IllegalStateException("broker down");
            }
        };
        CaseConversationService service = new CaseConversationService(
                this.punishments.repository(), this.store, this.store, this.log, broken, this.clock, this.banIds.service);

        assertInstanceOf(CaseConversationDTO.MessageResponse.class, service.postMessage(ID, text("still stored"), ALICE, null));
        assertEquals(1, ofType("message.added").size());
    }

    @Test
    void anExportListsMessagesAndMarkersInOrderWithoutNotesAndIsAudited() {
        givenPunishment();
        String id = reply(ALICE, "visible text");
        this.service.postPlayerMessage(ID, null, "player text", null);
        this.log.append(new CaseEventDraft(ID, new CaseEventPayload.NoteAdded(UUID.randomUUID().toString(), "internal gossip"), 5L,
                CaseEventOrigin.LIVE, CaseActor.of(ALICE.attribution()), null, null));
        this.service.deleteMessage(ID, UUID.fromString(id), ALICE);

        CaseConversationDTO.Transcript json = assertInstanceOf(CaseConversationDTO.Transcript.class,
                this.service.exportTranscript(ID, "json", false, ADMIN));

        assertTrue(json.content().contains("visible text"), "a deleted message keeps its text in the export");
        assertTrue(json.content().contains("player text"));
        assertFalse(json.content().contains("internal gossip"), "notes are excluded by default");
        assertEquals(3, json.entries(), "two messages and the deletion");
        assertEquals(new CaseEventPayload.TranscriptExported("json", 3, false), ofType("transcript.exported").getFirst().payload());
        assertFalse(ofType("transcript.exported").getFirst().body().toString().contains("visible text"), "the audit event holds no content");
        assertTrue(json.fileName().endsWith(".json"));
    }

    @Test
    void anExportWithNotesAndInTextFormat() {
        givenPunishment();
        this.log.append(new CaseEventDraft(ID, new CaseEventPayload.NoteAdded(UUID.randomUUID().toString(), "internal gossip"), 5L,
                CaseEventOrigin.LIVE, CaseActor.of(ALICE.attribution()), null, null));

        CaseConversationDTO.Transcript text = assertInstanceOf(CaseConversationDTO.Transcript.class,
                this.service.exportTranscript(ID, "text", true, ADMIN));

        assertTrue(text.content().contains("internal gossip"));
        assertTrue(text.contentType().startsWith("text/plain"));
        assertEquals(new CaseEventPayload.TranscriptExported("text", 1, true), ofType("transcript.exported").getFirst().payload());
    }

    @Test
    void redactedEntriesExportAsMarkersWithoutText() {
        givenPunishment();
        String id = reply(ALICE, "erase me please");
        this.service.redactMessage(ID, UUID.fromString(id), new CaseConversationDTO.RedactRequest(NoteRedactionReason.PERSONAL_DATA), ADMIN);

        CaseConversationDTO.Transcript json = assertInstanceOf(CaseConversationDTO.Transcript.class, this.service.exportTranscript(ID, "json", false, ADMIN));
        CaseConversationDTO.Transcript text = assertInstanceOf(CaseConversationDTO.Transcript.class, this.service.exportTranscript(ID, "text", false, ADMIN));

        assertFalse(json.content().contains("erase me"));
        assertFalse(text.content().contains("erase me"));
        assertTrue(json.content().contains("\"redacted\" : true"));
        assertTrue(text.content().contains("removed for data protection"));
    }

    @Test
    void anUnknownExportFormatIs400AndWritesNoAudit() {
        givenPunishment();

        assertEquals(CaseErrorKind.VALIDATION, kindOf(this.service.exportTranscript(ID, "pdf", false, ADMIN)));
        assertTrue(ofType("transcript.exported").isEmpty());
    }

    @Test
    void whenTheAuditCannotBeStoredNothingIsExported() {
        givenPunishment();
        this.log.failAppends(new IllegalStateException("database down"));

        assertThrows(IllegalStateException.class, () -> this.service.exportTranscript(ID, "json", false, ADMIN));
    }

    /** A change store whose message append fails, as a database outage would. */
    private final class CaseChangesFailingOnMessages implements CaseChanges {
        @Override
        public void ensureCase(String punishmentIdentifier, long now) {
            store.ensureCase(punishmentIdentifier, now);
        }

        @Override
        public List<Appended> update(String punishmentIdentifier, long expectedVersion, long now, CaseActor actor, CaseMeta before, CaseMeta after) {
            return store.update(punishmentIdentifier, expectedVersion, now, actor, before, after);
        }

        @Override
        public Appended editNote(String punishmentIdentifier, String noteId, String text, long now, CaseActor actor) {
            return store.editNote(punishmentIdentifier, noteId, text, now, actor);
        }

        @Override
        public List<Appended> appendMessage(CaseEventDraft message, boolean promoteWaiting) {
            throw new IllegalStateException("database down");
        }

        @Override
        public Appended editMessage(CaseEventDraft edit) {
            return store.editMessage(edit);
        }

        @Override
        public LeakMarked markBanIdLeaked(String punishmentIdentifier, BanIdLeakReason reason, BanIdLeakSource source, long now, CaseActor actor) {
            return store.markBanIdLeaked(punishmentIdentifier, reason, source, now, actor);
        }

        @Override
        public Reissued reissueBanId(String punishmentIdentifier, byte[] hash, byte[] encrypted, BanIdReissueReason reason, long now, CaseActor actor) {
            return store.reissueBanId(punishmentIdentifier, hash, encrypted, reason, now, actor);
        }

        @Override
        public List<Erased> erasePlayerMessages(String punishmentIdentifier, String reason, long now, UUID redactedByOid, CaseActor actor) {
            return store.erasePlayerMessages(punishmentIdentifier, reason, now, redactedByOid, actor);
        }
    }
}
