package net.onelitefeather.blackhole.backend.publicapi;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a player may read of the case, filtered in SQL: the visibility and the type allow-list are in the
 * statement, so a row that is not allowed never leaves the database.
 */
@Tag("integration")
class PublicConversationReaderIntegrationTest extends PostgresIntegrationTest {

    private static final String ID = "conv-1";
    private static final String OTHER = "conv-2";

    private PublicDatabase db;
    private JdbcPublicConversationReader reader;
    private long clockTick;

    @BeforeEach
    void givenAMigratedSchemaWithTwoCases() {
        db = new PublicDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        reader = new JdbcPublicConversationReader(db.dataSource);
        clockTick = 100L;
        for (String id : new String[]{ID, OTHER}) {
            db.execute("INSERT INTO punishments (identifier, source, owner, section_key, permanent, lifted, created_at, short_id, meta_data) "
                    + "VALUES (?, ?, ?, 'network', false, false, 1, ?, '{}'::jsonb)",
                    id, UUID.nameUUIDFromBytes("staff".getBytes()), "ab".repeat(64), id.substring(5).repeat(8));
            db.execute("INSERT INTO punishment_case (punishment_identifier, status, priority, tags, version, created_at, updated_at) "
                    + "VALUES (?, 'OPEN', 'NORMAL', '[]'::jsonb, 0, 1, 1)", id);
        }
    }

    private void event(String punishment, String type, String visibility, String subject, String trust, String payload) {
        db.execute("INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust, "
                + "visibility, subject_id, payload) VALUES (?, ?, ?, 1, ?, 'LIVE', ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), punishment, type, clockTick++, trust, visibility, subject, payload);
    }

    private void message(String punishment, String visibility, String messageId, String authorKind, String text) {
        String trust = authorKind.equals("PLAYER") ? "PLAYER" : "VERIFIED";
        event(punishment, "message.added", visibility, messageId, trust,
                "{\"messageId\":\"" + messageId + "\",\"author\":{\"kind\":\"" + authorKind + "\"},\"channel\":\"PORTAL\",\"text\":\"" + text + "\"}");
    }

    private void edit(String messageId, String text) {
        event(ID, "message.edited", "PLAYER", messageId, "VERIFIED", "{\"messageId\":\"" + messageId + "\",\"text\":\"" + text + "\"}");
    }

    private JdbcPublicConversationReader.Page read(Long after, int limit) {
        return reader.read(ID, after, limit);
    }

    @Test
    void onlyPlayerVisibleAllowListedEntriesAreRead() {
        message(ID, "PLAYER", "m-1", "PLAYER", "hello team");
        message(ID, "STAFF", "m-2", "BOT", "internal bot noise");
        event(ID, "note.added", "STAFF", "n-1", "VERIFIED", "{\"noteId\":\"n-1\",\"text\":\"secret staff note\"}");
        event(ID, "note.added", "PLAYER", "n-2", "VERIFIED", "{\"noteId\":\"n-2\",\"text\":\"a note wrongly marked player\"}");
        event(ID, "case.status_changed", "STAFF", null, "SYSTEM", "{\"from\":\"OPEN\",\"to\":\"WAITING\"}");
        event(ID, "transcript.exported", "STAFF", null, "VERIFIED", "{\"format\":\"text\",\"entries\":3}");
        event(ID, "punishment.applied", "PLAYER", null, "VERIFIED", "{\"section\":\"network\"}");
        message(ID, "PLAYER", "m-3", "STAFF", "we are looking into it");

        List<JdbcPublicConversationReader.Entry> entries = read(null, 50).entries();

        assertEquals(List.of("hello team", "we are looking into it"), entries.stream().map(JdbcPublicConversationReader.Entry::text).toList());
        assertEquals(List.of("PLAYER", "STAFF"), entries.stream().map(JdbcPublicConversationReader.Entry::author).toList());
    }

    @Test
    void aStaffVisibilityMessageAndItsEditNeverReachThePlayer() {
        message(ID, "STAFF", "m-1", "STAFF", "staff only");
        event(ID, "message.edited", "STAFF", "m-1", "VERIFIED", "{\"messageId\":\"m-1\",\"text\":\"staff only, edited\"}");
        event(ID, "message.deleted", "STAFF", "m-1", "VERIFIED", "{\"messageId\":\"m-1\"}");

        assertTrue(read(null, 50).entries().isEmpty());
    }

    @Test
    void theFiledAndDecidedMarkersShowAlthoughTheirEventsAreStaffVisibility() {
        event(ID, "appeal.filed", "STAFF", "a-1", "UNVERIFIED", "{\"appealId\":\"a-1\",\"eligible\":true}");
        event(ID, "appeal.decided", "STAFF", "a-1", "VERIFIED", "{\"appealId\":\"a-1\",\"outcome\":\"DENIED\"}");

        List<JdbcPublicConversationReader.Entry> entries = read(null, 50).entries();

        assertEquals(List.of("APPEAL_FILED", "APPEAL_DECIDED"), entries.stream().map(JdbcPublicConversationReader.Entry::kind).toList());
        for (JdbcPublicConversationReader.Entry marker : entries) {
            assertEquals("SYSTEM", marker.author());
            assertNull(marker.messageId());
            assertNull(marker.text(), "a marker carries no content");
            assertFalse(marker.removed());
        }
    }

    @Test
    void anEditFoldsIntoTheCurrentTextAndReappearsWithTheSameMessageId() {
        message(ID, "PLAYER", "m-1", "STAFF", "first words");
        edit("m-1", "corrected words");

        List<JdbcPublicConversationReader.Entry> entries = read(null, 50).entries();

        assertEquals(2, entries.size());
        assertEquals("m-1", entries.get(0).messageId());
        assertEquals("m-1", entries.get(1).messageId());
        assertEquals("corrected words", entries.get(0).text(), "the original row shows the current text");
        assertEquals("corrected words", entries.get(1).text());
        assertTrue(entries.get(0).seq() < entries.get(1).seq());
        assertEquals("MESSAGE", entries.get(1).kind());
        assertEquals("STAFF", entries.get(1).author(), "the author of the edit is the author of the message");
    }

    @Test
    void aDeletedMessageIsRemovedWithoutTextOnEveryRow() {
        message(ID, "PLAYER", "m-1", "STAFF", "oops");
        event(ID, "message.deleted", "PLAYER", "m-1", "VERIFIED", "{\"messageId\":\"m-1\",\"deletedBy\":\"STAFF\"}");

        List<JdbcPublicConversationReader.Entry> entries = read(null, 50).entries();

        assertEquals(2, entries.size());
        for (JdbcPublicConversationReader.Entry entry : entries) {
            assertTrue(entry.removed());
            assertNull(entry.text());
            assertEquals("m-1", entry.messageId());
        }
    }

    @Test
    void aRedactedMessageIsRemovedWithoutText() {
        message(ID, "PLAYER", "m-1", "PLAYER", "personal details");
        db.execute("UPDATE case_event SET payload = payload - 'text', redacted_at = 500 WHERE subject_id = 'm-1'");
        event(ID, "message.redacted", "PLAYER", "m-1", "VERIFIED", "{\"messageId\":\"m-1\",\"reason\":\"ERASURE\"}");

        List<JdbcPublicConversationReader.Entry> entries = read(null, 50).entries();

        assertEquals(2, entries.size());
        for (JdbcPublicConversationReader.Entry entry : entries) {
            assertTrue(entry.removed());
            assertNull(entry.text());
            assertEquals("PLAYER", entry.author());
        }
    }

    @Test
    void anotherAuthorKindThanPlayerIsShownAsTheTeam() {
        message(ID, "PLAYER", "m-1", "BOT", "ticket relay");
        message(ID, "PLAYER", "m-2", "EXTERNAL", "from discord");

        assertEquals(List.of("STAFF", "STAFF"), read(null, 50).entries().stream().map(JdbcPublicConversationReader.Entry::author).toList());
    }

    @Test
    void theLeakMarkerIsForfeitedBeforeFilingAndFrozenAfterIt() {
        event(ID, "ban_id.leak_marked", "PLAYER", null, "VERIFIED", "{\"reason\":\"SHARED\",\"source\":\"STAFF\",\"afterFiling\":false}");
        event(ID, "ban_id.leak_marked", "PLAYER", null, "VERIFIED", "{\"reason\":\"FOUND\",\"source\":\"STAFF\",\"afterFiling\":true}");

        List<JdbcPublicConversationReader.Entry> entries = read(null, 50).entries();

        assertEquals(List.of("FORFEITED", "FROZEN"), entries.stream().map(JdbcPublicConversationReader.Entry::kind).toList());
        assertEquals(List.of("SYSTEM", "SYSTEM"), entries.stream().map(JdbcPublicConversationReader.Entry::author).toList());
    }

    @Test
    void aReissueIsNotAnEntryButTheCursorMovesPastIt() {
        message(ID, "PLAYER", "m-1", "STAFF", "hello");
        event(ID, "ban_id.reissued", "PLAYER", null, "VERIFIED", "{\"reason\":\"OTHER\",\"fromGeneration\":1,\"toGeneration\":2}");

        JdbcPublicConversationReader.Page page = read(null, 50);

        assertEquals(1, page.entries().size());
        long last = db.count("SELECT max(seq) FROM case_event WHERE punishment_identifier = ?", ID);
        assertEquals(last, page.cursor(), "the next poll does not read the reissue again");
    }

    @Test
    void thePagesFollowSeqAndTheCursorIsTheLastSeqRead() {
        for (int i = 1; i <= 5; i++) {
            message(ID, "PLAYER", "m-" + i, "STAFF", "text " + i);
        }

        JdbcPublicConversationReader.Page first = read(null, 2);
        JdbcPublicConversationReader.Page second = read(first.cursor(), 2);
        JdbcPublicConversationReader.Page third = read(second.cursor(), 2);
        JdbcPublicConversationReader.Page fourth = read(third.cursor(), 2);

        assertEquals(List.of("text 1", "text 2"), first.entries().stream().map(JdbcPublicConversationReader.Entry::text).toList());
        assertEquals(List.of("text 3", "text 4"), second.entries().stream().map(JdbcPublicConversationReader.Entry::text).toList());
        assertEquals(List.of("text 5"), third.entries().stream().map(JdbcPublicConversationReader.Entry::text).toList());
        assertTrue(fourth.entries().isEmpty());
        assertEquals(third.cursor(), fourth.cursor(), "nothing new: the same cursor comes back");
    }

    @Test
    void anEmptyConversationHasNoCursorYet() {
        JdbcPublicConversationReader.Page page = read(null, 50);

        assertTrue(page.entries().isEmpty());
        assertNull(page.cursor());
    }

    @Test
    void theEntriesOfAnotherCaseNeverMixIn() {
        message(OTHER, "PLAYER", "x-1", "STAFF", "someone else's ticket");
        message(ID, "PLAYER", "m-1", "STAFF", "mine");

        assertEquals(List.of("mine"), read(null, 50).entries().stream().map(JdbcPublicConversationReader.Entry::text).toList());
    }

    @Test
    void aMessageIdOfAnotherCaseDoesNotFoldIntoThisOnesText() {
        message(ID, "PLAYER", "same-id", "STAFF", "mine");
        event(OTHER, "message.edited", "PLAYER", "same-id", "VERIFIED", "{\"messageId\":\"same-id\",\"text\":\"theirs\"}");

        assertEquals("mine", read(null, 50).entries().getFirst().text());
    }
}
