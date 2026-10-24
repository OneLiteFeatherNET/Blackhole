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

    private static final String DRAFT_COLUMNS = "identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust, "
            + "actor_oid, recorded_by_oid, actor_source, subject_id, visibility, payload, dedupe_key";
    private static final String DRAFT_VALUES = "?::uuid, ?::varchar, ?::varchar, ?::smallint, ?::bigint, ?::varchar, ?::varchar, ?::uuid, ?::uuid, "
            + "?::uuid, ?::varchar, ?::varchar, ?::jsonb, ?::varchar";

    /**
     * The message, and when the case waits for the player and a player wrote, the move to IN_REVIEW with
     * its system event. The promotion reads the message's RETURNING rows, so it runs after the message.
     */
    private static final String APPEND_MESSAGE = """
            WITH new_case AS (
                INSERT INTO punishment_case (punishment_identifier, status, priority, tags, version, created_at, updated_at)
                VALUES (?, 'OPEN', 'NORMAL', '[]'::jsonb, 0, ?, ?)
                ON CONFLICT (punishment_identifier) DO NOTHING
            ), msg AS (
                INSERT INTO case_event (%s)
                VALUES (%s)
                ON CONFLICT (dedupe_key) DO NOTHING
                RETURNING identifier, seq, type
            ), promoted AS (
                UPDATE punishment_case SET status = 'IN_REVIEW', version = version + 1, updated_at = ?
                WHERE punishment_identifier = ? AND status = 'WAITING' AND ?::boolean AND EXISTS (SELECT 1 FROM msg)
                RETURNING punishment_identifier
            ), status_event AS (
                INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                                        visibility, payload)
                SELECT ?::uuid, promoted.punishment_identifier, 'case.status_changed', 1, ?::bigint, 'LIVE', 'SYSTEM', 'STAFF',
                       '{"from":"WAITING","to":"IN_REVIEW"}'::jsonb
                FROM promoted
                RETURNING identifier, seq, type
            )
            SELECT identifier, seq, type, 0 AS ord FROM msg
            UNION ALL
            SELECT identifier, seq, type, 1 AS ord FROM status_event
            ORDER BY ord
            """.formatted(DRAFT_COLUMNS, DRAFT_VALUES);

    private static final String EDIT_MESSAGE = """
            INSERT INTO case_event (%s)
            SELECT %s
            WHERE NOT EXISTS (SELECT 1 FROM case_event
                              WHERE punishment_identifier = ? AND subject_id = ? AND (redacted_at IS NOT NULL OR type = 'message.deleted'))
            ON CONFLICT (dedupe_key) DO NOTHING
            RETURNING identifier, seq, type
            """.formatted(DRAFT_COLUMNS, DRAFT_VALUES);

    private static final String MARK_LEAKED = """
            WITH upd AS (
                UPDATE punishment_case SET ban_id_leaked_at = ?, ban_id_leak_reason = ?, updated_at = ?
                WHERE punishment_identifier = ? AND ban_id_leaked_at IS NULL
                RETURNING punishment_identifier
            ), ended AS (
                DELETE FROM player_session WHERE punishment_identifier = ? AND EXISTS (SELECT 1 FROM upd)
            )
            INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                                    actor_oid, recorded_by_oid, actor_source, visibility, payload)
            SELECT ?::uuid, upd.punishment_identifier, 'ban_id.leak_marked', 1, ?::bigint, 'LIVE', ?::varchar, ?::uuid, ?::uuid, ?::uuid, 'PLAYER',
                   jsonb_build_object('reason', ?::text, 'source', ?::text,
                                      'afterFiling', EXISTS (SELECT 1 FROM appeals a
                                                             WHERE a.punishment_identifier = upd.punishment_identifier AND a.status <> 6))
            FROM upd
            RETURNING identifier, seq, type, (payload ->> 'afterFiling')::boolean
            """;

    private static final String REISSUE = """
            WITH upd AS (
                UPDATE punishments SET ban_id_hash = ?, ban_id_enc = ?, ban_id_gen = ban_id_gen + 1
                WHERE identifier = ? AND ban_id_hash IS NOT NULL
                RETURNING identifier, ban_id_gen
            ), cleared AS (
                UPDATE punishment_case SET ban_id_leaked_at = NULL, ban_id_leak_reason = NULL, updated_at = ?
                WHERE punishment_identifier = ? AND EXISTS (SELECT 1 FROM upd)
                RETURNING punishment_identifier
            ), ended AS (
                DELETE FROM player_session WHERE punishment_identifier = ? AND EXISTS (SELECT 1 FROM upd)
            )
            INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                                    actor_oid, recorded_by_oid, actor_source, visibility, payload)
            SELECT ?::uuid, upd.identifier, 'ban_id.reissued', 1, ?::bigint, 'LIVE', ?::varchar, ?::uuid, ?::uuid, ?::uuid, 'PLAYER',
                   jsonb_build_object('reason', ?::text, 'fromGeneration', upd.ban_id_gen - 1, 'toGeneration', upd.ban_id_gen)
            FROM upd
            RETURNING identifier, seq, type, (payload ->> 'fromGeneration')::int, (payload ->> 'toGeneration')::int
            """;

    private static final String ERASE_PLAYER = """
            WITH player_messages AS (
                SELECT subject_id, visibility FROM case_event
                WHERE punishment_identifier = ? AND type = 'message.added' AND actor_trust = 'PLAYER' AND subject_id IS NOT NULL
            ), red AS (
                UPDATE case_event SET payload = payload - 'text' - 'attachments' - 'embeds', redacted_at = ?, redacted_by_oid = ?
                WHERE punishment_identifier = ? AND type IN ('message.added', 'message.edited') AND redacted_at IS NULL
                  AND subject_id IN (SELECT subject_id FROM player_messages)
                RETURNING subject_id
            ), done AS (
                INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                                        actor_oid, recorded_by_oid, actor_source, subject_id, visibility, payload, dedupe_key)
                SELECT gen_random_uuid(), ?::varchar, 'message.redacted', 1, ?::bigint, 'LIVE', ?::varchar, ?::uuid, ?::uuid, ?::uuid,
                       pm.subject_id, pm.visibility, jsonb_build_object('messageId', pm.subject_id, 'reason', ?::text),
                       'message.redacted:' || pm.subject_id
                FROM player_messages pm
                WHERE pm.subject_id IN (SELECT subject_id FROM red)
                ON CONFLICT (dedupe_key) DO NOTHING
                RETURNING identifier, seq, subject_id, visibility
            )
            SELECT identifier, seq, subject_id, visibility FROM done
            """;

    private static final String UNIQUE_VIOLATION = "23505";

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

    @Override
    public List<Appended> appendMessage(CaseEventDraft message, boolean promoteWaiting) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(APPEND_MESSAGE)) {
            int index = 0;
            statement.setString(++index, message.punishmentIdentifier());
            statement.setLong(++index, message.occurredAt());
            statement.setLong(++index, message.occurredAt());
            index = bindDraft(statement, index, message);
            statement.setLong(++index, message.occurredAt());
            statement.setString(++index, message.punishmentIdentifier());
            statement.setBoolean(++index, promoteWaiting);
            statement.setObject(++index, UUID.randomUUID());
            statement.setLong(++index, message.occurredAt());
            return appended(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("could not append a message to the case of " + message.punishmentIdentifier(), e);
        }
    }

    @Override
    public Appended editMessage(CaseEventDraft edit) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(EDIT_MESSAGE)) {
            int index = bindDraft(statement, 0, edit);
            statement.setString(++index, edit.punishmentIdentifier());
            statement.setString(++index, edit.subjectId());
            List<Appended> appended = appended(statement);
            return appended.isEmpty() ? null : appended.getFirst();
        } catch (SQLException e) {
            throw new IllegalStateException("could not edit a message of " + edit.punishmentIdentifier(), e);
        }
    }

    @Override
    public LeakMarked markBanIdLeaked(String punishmentIdentifier, BanIdLeakReason reason, BanIdLeakSource source, long now, CaseActor actor) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(MARK_LEAKED)) {
            int index = 0;
            statement.setLong(++index, now);
            statement.setString(++index, reason.name());
            statement.setLong(++index, now);
            statement.setString(++index, punishmentIdentifier);
            statement.setString(++index, punishmentIdentifier);
            statement.setObject(++index, UUID.randomUUID());
            statement.setLong(++index, now);
            index = bindActor(statement, index, actor);
            statement.setString(++index, reason.name());
            statement.setString(++index, source.name());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? new LeakMarked(new Appended(rows.getObject(1, UUID.class), rows.getLong(2), rows.getString(3)), rows.getBoolean(4))
                        : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not mark the Ban ID of " + punishmentIdentifier + " leaked", e);
        }
    }

    @Override
    public Reissued reissueBanId(String punishmentIdentifier, byte[] hash, byte[] encrypted, BanIdReissueReason reason, long now, CaseActor actor) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(REISSUE)) {
            int index = 0;
            statement.setBytes(++index, hash);
            statement.setBytes(++index, encrypted);
            statement.setString(++index, punishmentIdentifier);
            statement.setLong(++index, now);
            statement.setString(++index, punishmentIdentifier);
            statement.setString(++index, punishmentIdentifier);
            statement.setObject(++index, UUID.randomUUID());
            statement.setLong(++index, now);
            index = bindActor(statement, index, actor);
            statement.setString(++index, reason.name());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? new Reissued(new Appended(rows.getObject(1, UUID.class), rows.getLong(2), rows.getString(3)), rows.getInt(4), rows.getInt(5))
                        : null;
            }
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new BanIdCollisionException("a new Ban ID hash for " + punishmentIdentifier + " is already taken", e);
            }
            throw new IllegalStateException("could not reissue the Ban ID of " + punishmentIdentifier, e);
        }
    }

    @Override
    public List<Erased> erasePlayerMessages(String punishmentIdentifier, String reason, long now, UUID redactedByOid, CaseActor actor) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(ERASE_PLAYER)) {
            int index = 0;
            statement.setString(++index, punishmentIdentifier);
            statement.setLong(++index, now);
            statement.setObject(++index, redactedByOid);
            statement.setString(++index, punishmentIdentifier);
            statement.setString(++index, punishmentIdentifier);
            statement.setLong(++index, now);
            index = bindActor(statement, index, actor);
            statement.setString(++index, reason);
            try (ResultSet rows = statement.executeQuery()) {
                List<Erased> erased = new ArrayList<>();
                while (rows.next()) {
                    erased.add(new Erased(rows.getString(3),
                            new Appended(rows.getObject(1, UUID.class), rows.getLong(2), CaseEventPayload.MESSAGE_REDACTED), rows.getString(4)));
                }
                return erased;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not erase the player messages of " + punishmentIdentifier, e);
        }
    }

    /** Binds the fourteen columns of {@link #DRAFT_COLUMNS} from a draft; returns the last index used. */
    private static int bindDraft(PreparedStatement statement, int last, CaseEventDraft draft) throws SQLException {
        int index = last;
        statement.setObject(++index, UUID.randomUUID());
        statement.setString(++index, draft.punishmentIdentifier());
        statement.setString(++index, draft.type());
        statement.setShort(++index, (short) draft.payload().version());
        statement.setLong(++index, draft.occurredAt());
        statement.setString(++index, draft.origin().name());
        index = bindActor(statement, index, draft.actor());
        statement.setString(++index, draft.subjectId());
        statement.setString(++index, draft.visibility());
        statement.setString(++index, CaseEventPayloads.toJson(draft.payload()));
        statement.setString(++index, draft.dedupeKey());
        return index;
    }

    /** Binds trust, oid, recorded-by and source of an actor; returns the last index used. */
    private static int bindActor(PreparedStatement statement, int last, CaseActor actor) throws SQLException {
        int index = last;
        statement.setString(++index, actor.trust().name());
        JdbcCaseEventRepository.setUuid(statement, ++index, actor.actorOid());
        JdbcCaseEventRepository.setUuid(statement, ++index, actor.recordedByOid());
        JdbcCaseEventRepository.setUuid(statement, ++index, actor.actorSource());
        return index;
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
