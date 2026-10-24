package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link CaseEventRepository}: every call is one statement in
 * auto-commit mode. The append creates the case row in the same statement (a data-modifying CTE), so
 * the first event of a punishment and its case appear together.
 */
@Singleton
public class JdbcCaseEventRepository implements CaseEventRepository {

    private static final String APPEND = """
            WITH new_case AS (
                INSERT INTO punishment_case (punishment_identifier, status, priority, tags, version, created_at, updated_at)
                VALUES (?, 'OPEN', 'NORMAL', '[]'::jsonb, 0, ?, ?)
                ON CONFLICT (punishment_identifier) DO NOTHING
            )
            INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                                    actor_oid, recorded_by_oid, actor_source, subject_id, visibility, payload, dedupe_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (dedupe_key) DO NOTHING
            """;
    private static final String REDACT = """
            UPDATE case_event SET payload = CASE WHEN type LIKE 'note.%' THEN NULL ELSE payload - 'text' - 'attachments' - 'embeds' END,
                                  redacted_at = ?, redacted_by_oid = ?
            WHERE punishment_identifier = ? AND subject_id = ?
              AND type IN ('note.added', 'note.edited', 'message.added', 'message.edited') AND redacted_at IS NULL
            """;
    private static final String DELETE_EVENTS = "DELETE FROM case_event WHERE punishment_identifier = ?";
    private static final String DELETE_CASE = "DELETE FROM punishment_case WHERE punishment_identifier = ?";

    private final DataSource dataSource;

    @Inject
    public JdbcCaseEventRepository(DataSource dataSource, DataSourceResolver resolver) {
        // The injected source is transaction-aware and refuses connections outside a transaction.
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcCaseEventRepository(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public boolean append(CaseEventDraft draft) {
        CaseActor actor = draft.actor();
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(APPEND)) {
            statement.setString(1, draft.punishmentIdentifier());
            statement.setLong(2, draft.occurredAt());
            statement.setLong(3, draft.occurredAt());
            statement.setObject(4, UUID.randomUUID());
            statement.setString(5, draft.punishmentIdentifier());
            statement.setString(6, draft.type());
            statement.setShort(7, (short) draft.payload().version());
            statement.setLong(8, draft.occurredAt());
            statement.setString(9, draft.origin().name());
            statement.setString(10, actor.trust().name());
            setUuid(statement, 11, actor.actorOid());
            setUuid(statement, 12, actor.recordedByOid());
            setUuid(statement, 13, actor.actorSource());
            statement.setString(14, draft.subjectId());
            statement.setString(15, draft.visibility());
            statement.setString(16, CaseEventPayloads.toJson(draft.payload()));
            statement.setString(17, draft.dedupeKey());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("could not append the case event " + draft.type(), e);
        }
    }

    @Override
    public int redact(String punishmentIdentifier, String subjectId, long redactedAt, UUID redactedByOid) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(REDACT)) {
            statement.setLong(1, redactedAt);
            statement.setObject(2, redactedByOid);
            statement.setString(3, punishmentIdentifier);
            statement.setString(4, subjectId);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not redact a case note", e);
        }
    }

    @Override
    public int deleteForPunishment(String punishmentIdentifier) {
        try (Connection connection = this.dataSource.getConnection()) {
            int deleted;
            try (PreparedStatement statement = connection.prepareStatement(DELETE_EVENTS)) {
                statement.setString(1, punishmentIdentifier);
                deleted = statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(DELETE_CASE)) {
                statement.setString(1, punishmentIdentifier);
                statement.executeUpdate();
            }
            return deleted;
        } catch (SQLException e) {
            throw new IllegalStateException("could not delete the case of " + punishmentIdentifier, e);
        }
    }

    static void setUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.OTHER);
        } else {
            statement.setObject(index, value);
        }
    }
}
