package net.onelitefeather.blackhole.backend.discord.ingest;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventDraft;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayloads;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL implementation of {@link DiscordIngestStore}: one data-modifying CTE per call (event, link and cursor together). */
@Singleton
public class JdbcDiscordIngestStore implements DiscordIngestStore {

    private static final String DRAFT_COLUMNS = "identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust, "
            + "actor_oid, recorded_by_oid, actor_source, subject_id, visibility, payload, dedupe_key";
    private static final String DRAFT_VALUES = "?::uuid, ?::varchar, ?::varchar, ?::smallint, ?::bigint, ?::varchar, ?::varchar, ?::uuid, ?::uuid, "
            + "?::uuid, ?::varchar, ?::varchar, ?::jsonb, ?::varchar";

    /**
     * The message (only when its Discord id has no link yet), its link, the cursor and, for a player's message, the move of a case that
     * waits for the player to {@code IN_REVIEW} with its system event. The case row exists since the ticket was opened.
     */
    private static final String ADD = """
            WITH msg AS (
                INSERT INTO case_event (%s)
                SELECT %s
                WHERE NOT EXISTS (SELECT 1 FROM discord_message_link WHERE ticket_id = ? AND discord_message_id = ?)
                ON CONFLICT (dedupe_key) DO NOTHING
                RETURNING identifier, seq, type
            ), link AS (
                INSERT INTO discord_message_link (ticket_id, discord_message_id, message_id, direction, text_hash, created_at)
                VALUES (?, ?, ?::uuid, 'INBOUND', ?, ?)
                ON CONFLICT (ticket_id, discord_message_id) DO NOTHING
                RETURNING 1
            ), cur AS (
                UPDATE discord_ticket SET ingested_cursor = GREATEST(ingested_cursor, ?), updated_at = ? WHERE id = ?
                RETURNING 1
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

    private static final String EDIT = """
            WITH l AS (
                UPDATE discord_message_link SET text_hash = ?
                WHERE ticket_id = ? AND discord_message_id = ? AND direction = 'INBOUND' AND NOT deleted AND text_hash IS DISTINCT FROM ?
                RETURNING message_id
            ), ev AS (
                INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust, actor_oid,
                                        recorded_by_oid, actor_source, subject_id, visibility, payload, dedupe_key)
                SELECT gen_random_uuid(), o.punishment_identifier, 'message.edited', 1, ?::bigint, 'LIVE', o.actor_trust, o.actor_oid,
                       o.recorded_by_oid, o.actor_source, o.subject_id, o.visibility,
                       jsonb_build_object('messageId', o.subject_id, 'text', ?::text), ?::varchar
                FROM l JOIN case_event o ON o.punishment_identifier = ? AND o.subject_id = l.message_id::text AND o.type = 'message.added'
                WHERE o.redacted_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM case_event d WHERE d.punishment_identifier = ? AND d.subject_id = l.message_id::text
                                  AND d.type IN ('message.deleted', 'message.redacted'))
                ON CONFLICT (dedupe_key) DO NOTHING
                RETURNING identifier, seq, type
            )
            SELECT identifier, seq, type FROM ev
            """;

    private static final String DELETE = """
            WITH l AS (
                UPDATE discord_message_link SET deleted = true
                WHERE ticket_id = ? AND discord_message_id = ? AND direction = 'INBOUND' AND NOT deleted
                RETURNING message_id
            ), ev AS (
                INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust, actor_oid,
                                        recorded_by_oid, actor_source, subject_id, visibility, payload, dedupe_key)
                SELECT gen_random_uuid(), o.punishment_identifier, 'message.deleted', 1, ?::bigint, 'LIVE', o.actor_trust, o.actor_oid,
                       o.recorded_by_oid, o.actor_source, o.subject_id, o.visibility,
                       jsonb_strip_nulls(jsonb_build_object('messageId', o.subject_id, 'deletedBy', o.payload -> 'author' ->> 'kind',
                                         'detectedAtCatchup', CASE WHEN ?::boolean THEN true END)), ?::varchar
                FROM l JOIN case_event o ON o.punishment_identifier = ? AND o.subject_id = l.message_id::text AND o.type = 'message.added'
                ON CONFLICT (dedupe_key) DO NOTHING
                RETURNING identifier, seq, type
            )
            SELECT identifier, seq, type FROM ev
            """;

    private static final String DELETE_UNKNOWN = """
            WITH l AS (
                INSERT INTO discord_message_link (ticket_id, discord_message_id, message_id, direction, deleted, created_at)
                VALUES (?, ?, ?::uuid, 'INBOUND', true, ?)
                ON CONFLICT (ticket_id, discord_message_id) DO NOTHING
                RETURNING message_id
            ), ev AS (
                INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                                        recorded_by_oid, subject_id, visibility, payload, dedupe_key)
                SELECT gen_random_uuid(), ?::varchar, 'message.deleted', 1, ?::bigint, 'LIVE', 'SYSTEM', ?::uuid, l.message_id::text, 'STAFF',
                       jsonb_build_object('messageId', l.message_id::text, 'deletedBy', 'EXTERNAL', 'contentUnavailable', true), ?::varchar
                FROM l
                ON CONFLICT (dedupe_key) DO NOTHING
                RETURNING identifier, seq, type
            )
            SELECT identifier, seq, type FROM ev
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcDiscordIngestStore(DataSource dataSource, DataSourceResolver resolver) {
        // The injected source is transaction-aware and refuses connections outside a transaction.
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcDiscordIngestStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Optional<Link> link(UUID ticketId, long discordMessageId) {
        String sql = "SELECT message_id, discord_message_id, direction, text_hash, deleted FROM discord_message_link "
                + "WHERE ticket_id = ? AND discord_message_id = ?";
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, ticketId);
            statement.setLong(2, discordMessageId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? Optional.of(new Link(rows.getObject(1, UUID.class), rows.getLong(2), rows.getString(3), rows.getString(4), rows.getBoolean(5)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read a message link of ticket " + ticketId, e);
        }
    }

    @Override
    public List<CaseChanges.Appended> add(UUID ticketId, long discordMessageId, String textHash, CaseEventDraft message, boolean promoteWaiting,
                                          long now) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(ADD)) {
            int index = bindDraft(statement, 0, message);
            statement.setObject(++index, ticketId);
            statement.setLong(++index, discordMessageId);
            statement.setObject(++index, ticketId);
            statement.setLong(++index, discordMessageId);
            statement.setString(++index, message.subjectId());
            statement.setString(++index, textHash);
            statement.setLong(++index, now);
            statement.setLong(++index, discordMessageId);
            statement.setLong(++index, now);
            statement.setObject(++index, ticketId);
            statement.setLong(++index, message.occurredAt());
            statement.setString(++index, message.punishmentIdentifier());
            statement.setBoolean(++index, promoteWaiting);
            statement.setObject(++index, UUID.randomUUID());
            statement.setLong(++index, message.occurredAt());
            return appended(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("could not record a Discord message of ticket " + ticketId, e);
        }
    }

    @Override
    public Optional<CaseChanges.Appended> edit(UUID ticketId, String punishmentIdentifier, long discordMessageId, String textHash, String text,
                                               long occurredAt, String dedupeKey) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(EDIT)) {
            int index = 0;
            statement.setString(++index, textHash);
            statement.setObject(++index, ticketId);
            statement.setLong(++index, discordMessageId);
            statement.setString(++index, textHash);
            statement.setLong(++index, occurredAt);
            statement.setString(++index, text);
            statement.setString(++index, dedupeKey);
            statement.setString(++index, punishmentIdentifier);
            statement.setString(++index, punishmentIdentifier);
            return first(appended(statement));
        } catch (SQLException e) {
            throw new IllegalStateException("could not record an edit of a Discord message of ticket " + ticketId, e);
        }
    }

    @Override
    public Optional<CaseChanges.Appended> delete(UUID ticketId, String punishmentIdentifier, long discordMessageId, long occurredAt,
                                                 boolean detectedAtCatchup, String dedupeKey) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(DELETE)) {
            int index = 0;
            statement.setObject(++index, ticketId);
            statement.setLong(++index, discordMessageId);
            statement.setLong(++index, occurredAt);
            statement.setBoolean(++index, detectedAtCatchup);
            statement.setString(++index, dedupeKey);
            statement.setString(++index, punishmentIdentifier);
            return first(appended(statement));
        } catch (SQLException e) {
            throw new IllegalStateException("could not record the deletion of a Discord message of ticket " + ticketId, e);
        }
    }

    @Override
    public Optional<CaseChanges.Appended> deleteUnknown(UUID ticketId, String punishmentIdentifier, long discordMessageId, UUID messageId,
                                                        long occurredAt, String dedupeKey, UUID recordedByOid) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(DELETE_UNKNOWN)) {
            int index = 0;
            statement.setObject(++index, ticketId);
            statement.setLong(++index, discordMessageId);
            statement.setObject(++index, messageId);
            statement.setLong(++index, occurredAt);
            statement.setString(++index, punishmentIdentifier);
            statement.setLong(++index, occurredAt);
            statement.setObject(++index, recordedByOid);
            statement.setString(++index, dedupeKey);
            return first(appended(statement));
        } catch (SQLException e) {
            throw new IllegalStateException("could not record the deletion of an unknown Discord message of ticket " + ticketId, e);
        }
    }

    @Override
    public List<Long> missing(UUID ticketId, long[] present) {
        String sql = "SELECT discord_message_id FROM discord_message_link WHERE ticket_id = ? AND direction = 'INBOUND' AND NOT deleted "
                + "AND NOT (discord_message_id = ANY(?)) ORDER BY discord_message_id";
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            Long[] boxed = java.util.Arrays.stream(present).boxed().toArray(Long[]::new);
            statement.setObject(1, ticketId);
            statement.setArray(2, connection.createArrayOf("bigint", boxed));
            try (ResultSet rows = statement.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rows.next()) {
                    ids.add(rows.getLong(1));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not look for messages missing from the channel of ticket " + ticketId, e);
        }
    }

    private static Optional<CaseChanges.Appended> first(List<CaseChanges.Appended> appended) {
        return appended.isEmpty() ? Optional.empty() : Optional.of(appended.getFirst());
    }

    /** Binds the fourteen columns of {@link #DRAFT_COLUMNS} from a draft; returns the last index used. */
    private static int bindDraft(PreparedStatement statement, int last, CaseEventDraft draft) throws SQLException {
        int index = last;
        CaseActor actor = draft.actor();
        statement.setObject(++index, UUID.randomUUID());
        statement.setString(++index, draft.punishmentIdentifier());
        statement.setString(++index, draft.type());
        statement.setShort(++index, (short) draft.payload().version());
        statement.setLong(++index, draft.occurredAt());
        statement.setString(++index, draft.origin().name());
        statement.setString(++index, actor.trust().name());
        setUuid(statement, ++index, actor.actorOid());
        setUuid(statement, ++index, actor.recordedByOid());
        setUuid(statement, ++index, actor.actorSource());
        statement.setString(++index, draft.subjectId());
        statement.setString(++index, draft.visibility());
        statement.setString(++index, CaseEventPayloads.toJson(draft.payload()));
        statement.setString(++index, draft.dedupeKey());
        return index;
    }

    private static void setUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.OTHER);
        } else {
            statement.setObject(index, value);
        }
    }

    private static List<CaseChanges.Appended> appended(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            List<CaseChanges.Appended> appended = new ArrayList<>();
            while (rows.next()) {
                appended.add(new CaseChanges.Appended(rows.getObject(1, UUID.class), rows.getLong(2), rows.getString(3)));
            }
            return appended;
        }
    }
}
