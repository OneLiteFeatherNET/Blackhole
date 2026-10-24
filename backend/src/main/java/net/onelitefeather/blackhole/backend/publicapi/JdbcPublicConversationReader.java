package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** PostgreSQL implementation of {@link PublicConversationReader}: one read statement, filtered in SQL. */
@Singleton
public class JdbcPublicConversationReader implements PublicConversationReader {

    /**
     * A row is read only when it is {@code PLAYER} visibility and of an allow-listed type, or of the two
     * lifecycle markers (any visibility). The current text of a message, its original author and whether it
     * was deleted come from the same case and the same, player-visible, events: an edit folds into the text
     * of every row of its message, and another case's message id can never fold in.
     */
    private static final String READ = """
            SELECT e.seq, e.occurred_at, e.type, e.subject_id, e.payload ->> 'afterFiling',
                   cur.text, cur.redacted, orig.author_kind,
                   EXISTS (SELECT 1 FROM case_event d
                           WHERE d.punishment_identifier = e.punishment_identifier AND d.subject_id = e.subject_id
                             AND d.type = 'message.deleted' AND d.visibility = 'PLAYER')
            FROM case_event e
            LEFT JOIN LATERAL (
                SELECT x.payload ->> 'text' AS text, x.redacted_at IS NOT NULL AS redacted
                FROM case_event x
                WHERE x.punishment_identifier = e.punishment_identifier AND x.subject_id = e.subject_id
                  AND x.type IN ('message.added', 'message.edited') AND x.visibility = 'PLAYER'
                ORDER BY x.seq DESC LIMIT 1
            ) cur ON e.type LIKE 'message.%'
            LEFT JOIN LATERAL (
                SELECT x.payload #>> '{author,kind}' AS author_kind
                FROM case_event x
                WHERE x.punishment_identifier = e.punishment_identifier AND x.subject_id = e.subject_id
                  AND x.type = 'message.added' AND x.visibility = 'PLAYER'
                ORDER BY x.seq LIMIT 1
            ) orig ON e.type LIKE 'message.%'
            WHERE e.punishment_identifier = ? AND e.seq > ?
              AND ((e.visibility = 'PLAYER' AND e.type = ANY (?)) OR e.type = ANY (?))
            ORDER BY e.seq
            LIMIT ?
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcPublicConversationReader(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcPublicConversationReader(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Page read(String punishmentIdentifier, @Nullable Long afterSeq, int limit) {
        long after = afterSeq == null ? 0L : afterSeq;
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(READ)) {
            Array visible = connection.createArrayOf("text", CaseEventPayload.PORTAL_VISIBLE_TYPES.toArray());
            Array anyVisibility = connection.createArrayOf("text", CaseEventPayload.PORTAL_ANY_VISIBILITY_TYPES.toArray());
            statement.setString(1, punishmentIdentifier);
            statement.setLong(2, after);
            statement.setArray(3, visible);
            statement.setArray(4, anyVisibility);
            statement.setInt(5, limit);
            List<Entry> entries = new ArrayList<>();
            Long cursor = afterSeq;
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    cursor = rows.getLong(1);
                    Entry entry = entry(rows);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }
            return new Page(entries, cursor);
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the conversation of " + punishmentIdentifier, e);
        }
    }

    /** The entry of the row at the cursor, or null for a type the portal has no entry for (it still moves the cursor). */
    private static @Nullable Entry entry(ResultSet rows) throws SQLException {
        long seq = rows.getLong(1);
        long occurredAt = rows.getLong(2);
        String type = rows.getString(3);
        String messageId = rows.getString(4);
        switch (type) {
            case CaseEventPayload.APPEAL_FILED:
                return new Entry(seq, occurredAt, "APPEAL_FILED", "SYSTEM", null, null, false);
            case CaseEventPayload.APPEAL_DECIDED:
                return new Entry(seq, occurredAt, "APPEAL_DECIDED", "SYSTEM", null, null, false);
            case CaseEventPayload.BAN_ID_LEAK_MARKED:
                return new Entry(seq, occurredAt, "true".equals(rows.getString(5)) ? "FROZEN" : "FORFEITED", "SYSTEM", null, null, false);
            case CaseEventPayload.MESSAGE_ADDED, CaseEventPayload.MESSAGE_EDITED, CaseEventPayload.MESSAGE_DELETED, CaseEventPayload.MESSAGE_REDACTED:
                String text = rows.getString(6);
                boolean removed = !type.equals(CaseEventPayload.MESSAGE_ADDED) && !type.equals(CaseEventPayload.MESSAGE_EDITED)
                        || rows.getBoolean(7) || rows.getBoolean(9) || text == null;
                String author = "PLAYER".equals(rows.getString(8)) ? "PLAYER" : "STAFF";
                return new Entry(seq, occurredAt, "MESSAGE", author, messageId, removed ? null : text, removed);
            default:
                return null;
        }
    }
}
