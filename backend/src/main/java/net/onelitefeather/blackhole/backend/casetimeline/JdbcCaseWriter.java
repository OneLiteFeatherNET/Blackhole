package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** PostgreSQL implementation of {@link CaseWriter} and {@link CaseChanges}: one data-modifying CTE per change. */
@Singleton
public class JdbcCaseWriter implements CaseWriter, CaseChanges {

    private static final String REOPEN = """
            WITH upd AS (
                UPDATE punishment_case SET status = 'OPEN', version = version + 1, updated_at = ?
                WHERE punishment_identifier = ? AND status = 'CLOSED'
                RETURNING punishment_identifier
            )
            INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                                    visibility, payload)
            SELECT ?, upd.punishment_identifier, 'case.status_changed', 1, ?, 'LIVE', 'SYSTEM', 'STAFF',
                   '{"from":"CLOSED","to":"OPEN"}'::jsonb
            FROM upd
            """;

    private static final String ENSURE = """
            INSERT INTO punishment_case (punishment_identifier, status, priority, tags, version, created_at, updated_at)
            VALUES (?, 'OPEN', 'NORMAL', '[]'::jsonb, 0, ?, ?)
            ON CONFLICT (punishment_identifier) DO NOTHING
            """;
    private static final String UPDATE_HEAD = """
            WITH upd AS (
                UPDATE punishment_case SET status = ?, assignee_oid = ?, priority = ?, tags = ?::jsonb,
                                           version = version + 1, updated_at = ?
                WHERE punishment_identifier = ? AND version = ?
                RETURNING punishment_identifier
            )
            INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                                    actor_oid, recorded_by_oid, actor_source, visibility, payload)
            """;
    private static final String UPDATE_EVENT = """
            SELECT ?::uuid, upd.punishment_identifier, ?::varchar, 1::smallint, ?::bigint, 'LIVE', ?::varchar,
                   ?::uuid, ?::uuid, ?::uuid, 'STAFF', ?::jsonb FROM upd
            """;
    private static final String UPDATE_TAIL = " RETURNING identifier, seq, type";
    private static final String EDIT_NOTE = """
            INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                                    actor_oid, recorded_by_oid, actor_source, subject_id, visibility, payload)
            SELECT ?, ?, 'note.edited', 1, ?, 'LIVE', ?, ?, ?, ?, ?, 'STAFF', ?::jsonb
            WHERE NOT EXISTS (SELECT 1 FROM case_event WHERE punishment_identifier = ? AND subject_id = ? AND redacted_at IS NOT NULL)
            RETURNING identifier, seq, type
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcCaseWriter(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcCaseWriter(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public boolean reopenIfClosed(String punishmentIdentifier, long now) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(REOPEN)) {
            statement.setLong(1, now);
            statement.setString(2, punishmentIdentifier);
            statement.setObject(3, UUID.randomUUID());
            statement.setLong(4, now);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("could not reopen the case of " + punishmentIdentifier, e);
        }
    }

    @Override
    public void ensureCase(String punishmentIdentifier, long now) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(ENSURE)) {
            statement.setString(1, punishmentIdentifier);
            statement.setLong(2, now);
            statement.setLong(3, now);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not create the case of " + punishmentIdentifier, e);
        }
    }

    @Override
    public List<Appended> update(String punishmentIdentifier, long expectedVersion, long now, CaseActor actor, CaseMeta before, CaseMeta after) {
        List<CaseEventPayload> payloads = after.diffFrom(before);
        if (payloads.isEmpty()) {
            throw new IllegalArgumentException("nothing changes");
        }
        String sql = UPDATE_HEAD + String.join(" UNION ALL ", payloads.stream().map(payload -> UPDATE_EVENT).toList()) + UPDATE_TAIL;
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 0;
            statement.setString(++index, after.status());
            JdbcCaseEventRepository.setUuid(statement, ++index, after.assignee());
            statement.setString(++index, after.priority());
            statement.setString(++index, tagsJson(after.tags()));
            statement.setLong(++index, now);
            statement.setString(++index, punishmentIdentifier);
            statement.setLong(++index, expectedVersion);
            for (CaseEventPayload payload : payloads) {
                statement.setObject(++index, UUID.randomUUID());
                statement.setString(++index, payload.type());
                statement.setLong(++index, now);
                statement.setString(++index, actor.trust().name());
                JdbcCaseEventRepository.setUuid(statement, ++index, actor.actorOid());
                JdbcCaseEventRepository.setUuid(statement, ++index, actor.recordedByOid());
                JdbcCaseEventRepository.setUuid(statement, ++index, actor.actorSource());
                statement.setString(++index, CaseEventPayloads.toJson(payload));
            }
            return appended(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("could not update the case of " + punishmentIdentifier, e);
        }
    }

    @Override
    public Appended editNote(String punishmentIdentifier, String noteId, String text, long now, CaseActor actor) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(EDIT_NOTE)) {
            int index = 0;
            statement.setObject(++index, UUID.randomUUID());
            statement.setString(++index, punishmentIdentifier);
            statement.setLong(++index, now);
            statement.setString(++index, actor.trust().name());
            JdbcCaseEventRepository.setUuid(statement, ++index, actor.actorOid());
            JdbcCaseEventRepository.setUuid(statement, ++index, actor.recordedByOid());
            JdbcCaseEventRepository.setUuid(statement, ++index, actor.actorSource());
            statement.setString(++index, noteId);
            statement.setString(++index, CaseEventPayloads.toJson(new CaseEventPayload.NoteEdited(noteId, text)));
            statement.setString(++index, punishmentIdentifier);
            statement.setString(++index, noteId);
            List<Appended> appended = appended(statement);
            return appended.isEmpty() ? null : appended.getFirst();
        } catch (SQLException e) {
            throw new IllegalStateException("could not edit a note of " + punishmentIdentifier, e);
        }
    }

    private static List<Appended> appended(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            List<Appended> appended = new ArrayList<>();
            while (rows.next()) {
                appended.add(new Appended(rows.getObject(1, UUID.class), rows.getLong(2), rows.getString(3)));
            }
            return appended;
        }
    }

    private static String tagsJson(List<String> tags) {
        if (tags.stream().anyMatch(tag -> !CaseTags.VALID.matcher(tag).matches())) {
            throw new IllegalArgumentException("a tag is not a slug");
        }
        // Slugs need no JSON escaping.
        return tags.stream().map(tag -> "\"" + tag + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
