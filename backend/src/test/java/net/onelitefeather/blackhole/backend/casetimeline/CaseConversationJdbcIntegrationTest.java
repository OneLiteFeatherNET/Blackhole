package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The conversation statements against a real PostgreSQL schema: one-statement atomicity, dedupe, redaction and the leak columns of V13. */
@Tag("integration")
class CaseConversationJdbcIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "ab".repeat(64);
    private static final String ID = "conv-1";
    private static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());
    private static final UUID ADMIN = UUID.nameUUIDFromBytes("admin".getBytes());
    private static final CaseActor STAFF_ACTOR = new CaseActor(ActorTrust.VERIFIED, STAFF, STAFF, null);
    private static final CaseActor ADMIN_ACTOR = new CaseActor(ActorTrust.VERIFIED, ADMIN, ADMIN, null);

    private CaseDatabase db;
    private JdbcCaseWriter writer;
    private JdbcCaseReader reader;
    private JdbcCaseEventRepository events;

    @BeforeEach
    void givenAMigratedSchema() {
        db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        writer = new JdbcCaseWriter(db.dataSource);
        reader = new JdbcCaseReader(db.dataSource);
        events = new JdbcCaseEventRepository(db.dataSource);
    }

    private void givenPunishment(String id, String shortId) {
        db.execute("INSERT INTO punishments (identifier, source, recorded_by_oid, owner, section_key, permanent, lifted, created_at, short_id, meta_data, "
                + "ban_id_hash, ban_id_enc) VALUES (?, ?, ?, ?, 'network', false, false, 1, ?, '{}'::jsonb, ?, ?)",
                id, STAFF, STAFF, OWNER, shortId, id.getBytes(), new byte[]{1});
    }

    private void givenCase() {
        givenPunishment(ID, "AAAAAAA1");
        writer.ensureCase(ID, 10L);
    }

    private void givenCaseStatus(String status) {
        db.execute("UPDATE punishment_case SET status = ? WHERE punishment_identifier = ?", status, ID);
    }

    private String playerMessage(String text, long at) {
        String messageId = UUID.randomUUID().toString();
        writer.appendMessage(CaseDrafts.playerMessage(ID, messageId, null, text, at), true);
        return messageId;
    }

    private String staffMessage(String text, long at) {
        String messageId = UUID.randomUUID().toString();
        writer.appendMessage(CaseDrafts.staffMessage(ID, messageId, null, text, MessageChannel.PANEL, STAFF_ACTOR, CaseEventOrigin.LIVE, at), false);
        return messageId;
    }

    @Test
    void aMessageIsStoredWithItsVisibilityTrustAndPayloadInOneStatement() {
        givenCase();
        String messageId = UUID.randomUUID().toString();

        List<CaseChanges.Appended> appended = writer.appendMessage(CaseDrafts.playerMessage(ID, messageId, "appeal-1", "hello", 20L), true);

        assertEquals(1, appended.size());
        CaseEventRecord stored = reader.subject(ID, messageId).getFirst();
        assertEquals("PLAYER", stored.visibility());
        assertEquals(ActorTrust.PLAYER, stored.actorTrust());
        assertNull(stored.actorOid());
        assertEquals(new CaseEventPayload.MessageAdded(messageId, "appeal-1", MessageAuthorKind.PLAYER, null, MessageChannel.PORTAL, "hello"),
                stored.payload());
        assertEquals("message:" + messageId, stored.dedupeKey());
    }

    @Test
    void theSameMessageRecordedTwiceInsertsOnce() {
        givenCase();
        CaseEventDraft draft = CaseDrafts.playerMessage(ID, "m-1", null, "hello", 20L);

        writer.appendMessage(draft, true);
        List<CaseChanges.Appended> second = writer.appendMessage(draft, true);

        assertTrue(second.isEmpty());
        assertEquals(1, db.count("SELECT count(*) FROM case_event WHERE type = 'message.added'"));
    }

    @Test
    void aMessageOnAMissingCaseCreatesTheCaseWithIt() {
        givenPunishment(ID, "AAAAAAA1");

        writer.appendMessage(CaseDrafts.playerMessage(ID, "m-1", null, "first ever", 20L), true);

        assertTrue(reader.find(ID).isPresent());
    }

    @Test
    void aPlayerMessageOnAWaitingCaseMovesItToInReviewWithASystemEvent() {
        givenCase();
        givenCaseStatus("WAITING");

        List<CaseChanges.Appended> appended = writer.appendMessage(CaseDrafts.playerMessage(ID, "m-1", null, "answer", 20L), true);

        assertEquals(List.of("message.added", "case.status_changed"), appended.stream().map(CaseChanges.Appended::type).toList());
        assertTrue(appended.get(0).seq() < appended.get(1).seq(), "the status event follows the message");
        CaseReader.CaseRow row = reader.find(ID).orElseThrow();
        assertEquals("IN_REVIEW", row.meta().status());
        assertEquals(1, row.version(), "the version moved once");
        CaseEventRecord status = lastOfType("case.status_changed");
        assertEquals(new CaseEventPayload.StatusChanged("WAITING", "IN_REVIEW"), status.payload());
        assertEquals(ActorTrust.SYSTEM, status.actorTrust());
    }

    @Test
    void aStaffMessageOrAMessageOnAnOpenCaseLeavesTheStatusAlone() {
        givenCase();
        givenCaseStatus("WAITING");
        staffMessage("question", 20L);
        assertEquals("WAITING", reader.find(ID).orElseThrow().meta().status());

        givenCaseStatus("OPEN");
        playerMessage("hello", 30L);
        assertEquals("OPEN", reader.find(ID).orElseThrow().meta().status());
        assertEquals(0, db.count("SELECT count(*) FROM case_event WHERE type = 'case.status_changed'"));
    }

    @Test
    void aRepeatedPlayerMessageDoesNotPromoteTwice() {
        givenCase();
        givenCaseStatus("WAITING");
        CaseEventDraft draft = CaseDrafts.playerMessage(ID, "m-1", null, "answer", 20L);

        writer.appendMessage(draft, true);
        givenCaseStatus("WAITING");
        writer.appendMessage(draft, true);

        assertEquals("WAITING", reader.find(ID).orElseThrow().meta().status(), "a replay is a no-op, even for the status");
        assertEquals(1, db.count("SELECT count(*) FROM case_event WHERE type = 'case.status_changed'"));
    }

    @Test
    void anEditNeverChangesTheOriginalAndTheSameEditNumberIsTakenOnce() {
        givenCase();
        String id = staffMessage("one", 20L);

        CaseChanges.Appended first = writer.editMessage(CaseDrafts.messageEdited(ID, id, 1, "two", "PLAYER", STAFF_ACTOR, 30L));
        CaseChanges.Appended racing = writer.editMessage(CaseDrafts.messageEdited(ID, id, 1, "other", "PLAYER", STAFF_ACTOR, 31L));

        assertNotNull(first);
        assertNull(racing, "the second writer of edit 1 loses");
        List<CaseEventRecord> versions = reader.subject(ID, id);
        assertEquals(2, versions.size());
        assertEquals("one", assertInstanceOf(CaseEventPayload.MessageAdded.class, versions.getFirst().payload()).text());
        assertEquals("two", MessageThread.of(versions).getFirst().text());
        assertEquals("PLAYER", versions.getLast().visibility());
    }

    @Test
    void aRedactedOrDeletedMessageCannotBeEdited() {
        givenCase();
        String redacted = staffMessage("a", 20L);
        events.redact(ID, redacted, 25L, ADMIN);
        String deleted = staffMessage("b", 21L);
        events.append(CaseDrafts.messageDeleted(ID, deleted, MessageAuthorKind.STAFF, "PLAYER", STAFF_ACTOR, 26L));

        assertNull(writer.editMessage(CaseDrafts.messageEdited(ID, redacted, 1, "x", "PLAYER", STAFF_ACTOR, 30L)));
        assertNull(writer.editMessage(CaseDrafts.messageEdited(ID, deleted, 1, "x", "PLAYER", STAFF_ACTOR, 30L)));
    }

    @Test
    void redactingAMessageKeepsStructureAndRemovesTheTextOfEveryVersion() {
        givenCase();
        String id = staffMessage("secret one", 20L);
        writer.editMessage(CaseDrafts.messageEdited(ID, id, 1, "secret two", "PLAYER", STAFF_ACTOR, 30L));

        int redacted = events.redact(ID, id, 40L, ADMIN);
        events.append(CaseDrafts.messageRedacted(ID, id, "PERSONAL_DATA", "PLAYER", ADMIN_ACTOR, 40L));

        assertEquals(2, redacted);
        for (CaseEventRecord version : reader.subject(ID, id).stream().filter(row -> !row.type().equals("message.redacted")).toList()) {
            assertEquals(40L, version.redactedAt());
            assertFalse(version.body().containsKey("text"), "the text is gone from " + version.type());
            assertEquals(id, version.body().get("messageId"), "the structure stays");
            assertEquals("PLAYER", version.visibility());
        }
        assertEquals(0, db.count("SELECT count(*) FROM case_event WHERE payload::text LIKE '%secret%'"), "no text anywhere in the table");
        assertEquals(ActorTrust.VERIFIED, reader.subject(ID, id).getFirst().actorTrust());
    }

    @Test
    void erasingThePlayersSideRedactsPlayerMessagesOnlyAndAppendsOneEventEach() {
        givenCase();
        String staff = staffMessage("staff words", 20L);
        String one = playerMessage("player one secret", 21L);
        String two = playerMessage("player two secret", 22L);
        String other = "other-case";
        givenPunishment(other, "BBBBBBB2");
        writer.ensureCase(other, 10L);
        writer.appendMessage(CaseDrafts.playerMessage(other, "foreign", null, "other case secret", 23L), false);

        List<CaseChanges.Erased> erased = writer.erasePlayerMessages(ID, "ERASURE_REQUEST", 50L, ADMIN, ADMIN_ACTOR);
        List<CaseChanges.Erased> again = writer.erasePlayerMessages(ID, "ERASURE_REQUEST", 51L, ADMIN, ADMIN_ACTOR);

        assertEquals(java.util.Set.of(one, two), erased.stream().map(CaseChanges.Erased::messageId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(again.isEmpty(), "nothing left to erase");
        assertEquals("staff words", MessageThread.of(reader.subject(ID, staff)).getFirst().text(), "staff messages remain");
        assertTrue(MessageThread.of(reader.subject(ID, one)).getFirst().redacted());
        assertEquals(2, db.count("SELECT count(*) FROM case_event WHERE punishment_identifier = ? AND type = 'message.redacted'", ID));
        assertEquals("PLAYER", erased.getFirst().visibility());
        assertEquals(0, db.count("SELECT count(*) FROM case_event WHERE punishment_identifier = ? AND payload::text LIKE '%secret%'", ID));
        assertEquals(1, db.count("SELECT count(*) FROM case_event WHERE punishment_identifier = ? AND payload::text LIKE '%other case secret%'", other),
                "another case is untouched");
    }

    @Test
    void markingALeakSetsTheCaseStateAndAppendsOnceWithTheFilingFlag() {
        givenCase();

        CaseChanges.LeakMarked first = writer.markBanIdLeaked(ID, BanIdLeakReason.POSTED_PUBLIC, BanIdLeakSource.STAFF, 60L, STAFF_ACTOR);
        CaseChanges.LeakMarked second = writer.markBanIdLeaked(ID, BanIdLeakReason.SHARED, BanIdLeakSource.STAFF, 61L, STAFF_ACTOR);

        assertNotNull(first);
        assertFalse(first.afterFiling());
        assertNull(second, "marking twice appends nothing");
        CaseReader.CaseRow row = reader.find(ID).orElseThrow();
        assertEquals(60L, row.banIdLeakedAt());
        assertEquals("POSTED_PUBLIC", row.banIdLeakReason(), "the first reason stays");
        assertEquals(1, db.count("SELECT count(*) FROM case_event WHERE type = 'ban_id.leak_marked'"));
        assertEquals(new CaseEventPayload.BanIdLeakMarked(BanIdLeakReason.POSTED_PUBLIC, BanIdLeakSource.STAFF, false), lastOfType("ban_id.leak_marked").payload());
    }

    @Test
    void aLeakAfterAnAppealWasFiledSaysSo() {
        givenCase();
        db.execute("INSERT INTO appeals (identifier, created_at, updated_at, status, punishment_identifier) VALUES (?, 1, 1, 1, ?)",
                UUID.randomUUID(), ID);

        CaseChanges.LeakMarked marked = writer.markBanIdLeaked(ID, BanIdLeakReason.FOUND, BanIdLeakSource.DISCORD_BOT, 60L, STAFF_ACTOR);

        assertTrue(marked.afterFiling());
        assertEquals(new CaseEventPayload.BanIdLeakMarked(BanIdLeakReason.FOUND, BanIdLeakSource.DISCORD_BOT, true), lastOfType("ban_id.leak_marked").payload());
    }

    @Test
    void anIneligibleAppealDoesNotCountAsFiled() {
        givenCase();
        db.execute("INSERT INTO appeals (identifier, created_at, updated_at, status, punishment_identifier) VALUES (?, 1, 1, 6, ?)",
                UUID.randomUUID(), ID);

        assertFalse(writer.markBanIdLeaked(ID, BanIdLeakReason.OTHER, BanIdLeakSource.STAFF, 60L, STAFF_ACTOR).afterFiling());
    }

    @Test
    void aReissueReplacesTheBanIdRaisesTheGenerationAndClearsTheLeak() {
        givenCase();
        writer.markBanIdLeaked(ID, BanIdLeakReason.SHARED, BanIdLeakSource.STAFF, 60L, STAFF_ACTOR);
        byte[] hash = {9, 9, 9};
        byte[] enc = {8, 8};

        CaseChanges.Reissued reissued = writer.reissueBanId(ID, hash, enc, BanIdReissueReason.NOT_PLAYERS_FAULT, 70L, ADMIN_ACTOR);

        assertEquals(1, reissued.fromGeneration());
        assertEquals(2, reissued.toGeneration());
        assertEquals(2L, db.count("SELECT ban_id_gen FROM punishments WHERE identifier = ?", ID));
        assertEquals(1L, db.count("SELECT count(*) FROM punishments WHERE identifier = ? AND ban_id_hash = ? AND ban_id_enc = ?", ID, hash, enc));
        CaseReader.CaseRow row = reader.find(ID).orElseThrow();
        assertNull(row.banIdLeakedAt(), "the leak belonged to the old generation");
        assertNull(row.banIdLeakReason());
        CaseEventRecord event = lastOfType("ban_id.reissued");
        assertEquals(new CaseEventPayload.BanIdReissued(BanIdReissueReason.NOT_PLAYERS_FAULT, 1, 2), event.payload());
        assertEquals(ADMIN, event.actorOid());
    }

    @Test
    void aReissueOfAPunishmentWithoutABanIdChangesNothing() {
        givenPunishment(ID, "AAAAAAA1");
        db.execute("UPDATE punishments SET ban_id_hash = NULL, ban_id_enc = NULL WHERE identifier = ?", ID);
        writer.ensureCase(ID, 10L);

        assertNull(writer.reissueBanId(ID, new byte[]{1}, new byte[]{2}, BanIdReissueReason.OTHER, 70L, ADMIN_ACTOR));

        assertEquals(1L, db.count("SELECT ban_id_gen FROM punishments WHERE identifier = ?", ID));
        assertEquals(0, db.count("SELECT count(*) FROM case_event WHERE type = 'ban_id.reissued'"));
    }

    @Test
    void aHashThatBelongsToAnotherPunishmentIsACollisionAndChangesNothing() {
        givenCase();
        givenPunishment("conv-2", "BBBBBBB2");
        byte[] taken = "conv-2".getBytes();

        assertThrows(BanIdCollisionException.class,
                () -> writer.reissueBanId(ID, taken, new byte[]{2}, BanIdReissueReason.OTHER, 70L, ADMIN_ACTOR));

        assertEquals(1L, db.count("SELECT ban_id_gen FROM punishments WHERE identifier = ?", ID), "the statement rolled back as a whole");
        assertEquals(0, db.count("SELECT count(*) FROM case_event WHERE type = 'ban_id.reissued'"));
    }

    @Test
    void aTranscriptExportEventHoldsNoContent() {
        givenCase();
        events.append(CaseDrafts.transcriptExported(ID, "json", 4, false, ADMIN_ACTOR, 80L));

        CaseEventRecord stored = lastOfType("transcript.exported");

        assertEquals(new CaseEventPayload.TranscriptExported("json", 4, false), stored.payload());
        assertEquals("STAFF", stored.visibility());
    }

    private CaseEventRecord lastOfType(String type) {
        return reader.timeline(new CaseReader.TimelineQuery(ID, null, null, List.of(), null, null, null, true, 1000)).stream()
                .map(CaseReader.Entry::event).filter(event -> event.type().equals(type)).reduce((a, b) -> b).orElseThrow();
    }
}
