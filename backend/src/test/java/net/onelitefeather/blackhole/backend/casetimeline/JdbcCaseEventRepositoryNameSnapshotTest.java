package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SQL that clears the staff name snapshots of one principal's messages: only that principal's {@code message.added}
 * rows change, the text and the payload version stay, and nothing else in the payload is touched.
 */
@Tag("integration")
class JdbcCaseEventRepositoryNameSnapshotTest extends PostgresIntegrationTest {

    private static final String PUNISHMENT = "snap-case";
    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BERT = UUID.nameUUIDFromBytes("bert".getBytes());

    private CaseDatabase db;
    private JdbcCaseEventRepository repository;
    private long clock;

    @BeforeEach
    void givenAMigratedSchemaAndTwoNamedStaffMembers() {
        db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        repository = new JdbcCaseEventRepository(db.dataSource);
        clock = 1L;
        db.execute("INSERT INTO punishments (identifier, source, owner, section_key, permanent, lifted, created_at, short_id, meta_data) "
                + "VALUES (?, ?, ?, 'network', false, false, 1, 'snapcas1', '{}'::jsonb)", PUNISHMENT, ANNA, "cd".repeat(64));
        db.execute("INSERT INTO punishment_case (punishment_identifier, status, priority, tags, version, created_at, updated_at) "
                + "VALUES (?, 'OPEN', 'NORMAL', '[]'::jsonb, 0, 1, 1)", PUNISHMENT);
    }

    private void staffMessage(String messageId, UUID actor, String snapshot) {
        insert(messageId, "message.added", snapshot == null ? 1 : 2, actor, "STAFF",
                "{\"messageId\":\"" + messageId + "\",\"author\":{\"kind\":\"STAFF\"" + (snapshot == null ? "" : ",\"staffDisplayName\":\"" + snapshot + "\"")
                        + "},\"channel\":\"PANEL\",\"text\":\"text of " + messageId + "\"}");
    }

    private void insert(String messageId, String type, int version, UUID actor, String visibility, String payload) {
        db.execute("INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust, "
                + "actor_oid, visibility, subject_id, payload) VALUES (?, ?, ?, ?, ?, 'LIVE', 'VERIFIED', ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), PUNISHMENT, type, version, clock++, actor, visibility, messageId, payload);
    }

    /** The payload of a message as stored, and its version. */
    private String[] stored(String messageId) {
        try (Connection connection = db.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT payload::text, payload_version FROM case_event WHERE subject_id = ? AND type = 'message.added'")) {
            statement.setString(1, messageId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "the message " + messageId + " is stored");
                return new String[]{rows.getString(1), rows.getString(2)};
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String authorName(String payload) {
        return payload.contains("staffDisplayName") ? payload.replaceAll(".*\"staffDisplayName\": ?\"([^\"]*)\".*", "$1") : null;
    }

    @Test
    void clearingAStaffActorRemovesTheSnapshotOfEveryOneOfItsMessages() {
        staffMessage("m-1", ANNA, "Anna K.");
        staffMessage("m-2", ANNA, "Anna K.");

        repository.clearStaffNameSnapshots(ANNA);

        assertNull(authorName(stored("m-1")[0]), "the first message no longer names her");
        assertNull(authorName(stored("m-2")[0]), "the second message no longer names her");
    }

    @Test
    void clearingKeepsTheTextTheKindAndTheVersionOfTheMessage() {
        staffMessage("m-1", ANNA, "Anna K.");

        repository.clearStaffNameSnapshots(ANNA);

        String[] row = stored("m-1");
        assertTrue(row[0].contains("text of m-1"), "the text is not touched");
        assertTrue(row[0].contains("\"kind\": \"STAFF\""), "the author kind stays");
        assertEquals("2", row[1], "the payload version stays, so the row keeps its layout");
    }

    @Test
    void clearingLeavesEveryOtherStaffActorsSnapshotAlone() {
        staffMessage("m-1", ANNA, "Anna K.");
        staffMessage("m-2", BERT, "Bert L.");

        repository.clearStaffNameSnapshots(ANNA);

        assertEquals("Bert L.", authorName(stored("m-2")[0]), "another person's name is not erased");
    }

    @Test
    void aLegacyMessageWithoutASnapshotIsUnchanged() {
        staffMessage("m-1", ANNA, null);
        String before = stored("m-1")[0];

        repository.clearStaffNameSnapshots(ANNA);

        assertEquals(before, stored("m-1")[0]);
    }
}
