package net.onelitefeather.blackhole.backend.discord.ticket;

import io.micronaut.core.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChangeNotifier;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventSignals;
import net.onelitefeather.blackhole.backend.casetimeline.MessageAuthorKind;
import net.onelitefeather.blackhole.backend.casetimeline.MessageChannel;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link DiscordTicketStore}: every call is one statement in auto-commit mode, and a change that is
 * itself case history (opening, completing, archiving, deleting) is a data-modifying CTE that appends its event with it.
 */
@Singleton
public class JdbcDiscordTicketStore implements DiscordTicketStore {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String TICKET_COLUMNS = "id, ticket_no, punishment_identifier, appeal_id, owner_participant_id, state, channel_id, "
            + "guild_id, frozen, ingested_cursor, relayed_seq, transcript_count, transcript_last_message_id, transcript_complete_at, "
            + "created_at, updated_at, freeze_source";
    private static final String PARTICIPANT_COLUMNS = "id, punishment_identifier, kind, discord_user_id, display_name, bot, created_at, erased_at";

    private static final String ENSURE_PARTICIPANT = """
            INSERT INTO case_participant (id, punishment_identifier, kind, discord_user_id, display_name, bot, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (punishment_identifier, discord_user_id) DO UPDATE
                SET display_name = COALESCE(EXCLUDED.display_name, case_participant.display_name),
                    kind = CASE WHEN EXCLUDED.kind = 'PLAYER' THEN 'PLAYER' ELSE case_participant.kind END
            RETURNING %s
            """.formatted(PARTICIPANT_COLUMNS);

    /** A participant without a Discord id (a nameless bot, say) never conflicts, so it is a plain insert. */
    private static final String INSERT_ANONYMOUS_PARTICIPANT = """
            INSERT INTO case_participant (id, punishment_identifier, kind, discord_user_id, display_name, bot, created_at)
            VALUES (?, ?, ?, NULL, ?, ?, ?)
            RETURNING %s
            """.formatted(PARTICIPANT_COLUMNS);

    private static final String OPEN = """
            WITH t AS (
                INSERT INTO discord_ticket (id, punishment_identifier, appeal_id, owner_participant_id, state, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING_CHANNEL', ?, ?)
                RETURNING %1$s
            ), ev AS (
                INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                                        recorded_by_oid, subject_id, visibility, payload, dedupe_key)
                SELECT gen_random_uuid(), t.punishment_identifier, 'ticket.opened', 1, t.created_at, 'LIVE', 'PLAYER', ?::uuid,
                       t.id::text, 'PLAYER',
                       jsonb_build_object('ticketId', t.id::text, 'appealId', t.appeal_id::text, 'ticketNo', t.ticket_no),
                       'ticket.opened:' || t.id::text
                FROM t
                RETURNING 1
            )
            SELECT %1$s FROM t
            """.formatted(TICKET_COLUMNS);

    private static final String CHANNEL_READY = """
            UPDATE discord_ticket SET state = 'OPEN', channel_id = ?, guild_id = COALESCE(?, guild_id), updated_at = ?
            WHERE id = ? AND state = 'PENDING_CHANNEL'
            RETURNING %s
            """.formatted(TICKET_COLUMNS);

    private static final String ARCHIVE = """
            WITH upd AS (
                UPDATE discord_ticket SET state = 'ARCHIVED', archived_at = ?, updated_at = ?
                WHERE id = ? AND state = 'CLOSED'
                RETURNING %1$s
            ), ev AS (
                INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                                        recorded_by_oid, subject_id, visibility, payload, dedupe_key)
                SELECT gen_random_uuid(), upd.punishment_identifier, 'ticket.archived', 1, ?, 'LIVE', 'SYSTEM', ?::uuid,
                       upd.id::text, 'STAFF',
                       jsonb_build_object('ticketId', upd.id::text, 'appealId', upd.appeal_id::text, 'protocolVersion', ?::int),
                       'ticket.archived:' || upd.id::text
                FROM upd
                ON CONFLICT (dedupe_key) DO NOTHING
                RETURNING 1
            )
            SELECT %1$s FROM upd
            """.formatted(TICKET_COLUMNS);

    private static final String DELETE = """
            WITH prior AS (
                SELECT id, transcript_complete_at FROM discord_ticket WHERE id = ? AND state <> 'DELETED'
            ), upd AS (
                UPDATE discord_ticket SET state = 'DELETED', deleted_at = ?, updated_at = ?
                WHERE id IN (SELECT id FROM prior)
                RETURNING %1$s
            ), last_message AS (
                SELECT max(e.occurred_at) AS at FROM case_event e
                JOIN discord_message_link l ON l.message_id::text = e.subject_id AND l.ticket_id = ?
                WHERE e.type = 'message.added'
            ), ev AS (
                INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                                        recorded_by_oid, subject_id, visibility, payload, dedupe_key)
                SELECT gen_random_uuid(), upd.punishment_identifier, 'ticket.channel_deleted', 1, ?, 'LIVE', 'SYSTEM', ?::uuid,
                       upd.id::text, 'STAFF',
                       jsonb_strip_nulls(jsonb_build_object('ticketId', upd.id::text, 'appealId', upd.appeal_id::text,
                                         'transcriptComplete', prior.transcript_complete_at IS NOT NULL,
                                         'lastRecordedMessageAt', CASE WHEN prior.transcript_complete_at IS NULL THEN last_message.at END)),
                       'ticket.channel_deleted:' || upd.id::text
                FROM upd JOIN prior ON prior.id = upd.id, last_message
                ON CONFLICT (dedupe_key) DO NOTHING
                RETURNING 1
            )
            SELECT %1$s FROM upd
            """.formatted(TICKET_COLUMNS);

    private static final String COMPLETE = """
            WITH tally AS (
                SELECT count(*) FILTER (WHERE NOT l.deleted)::int AS cnt,
                       COALESCE(max(l.discord_message_id) FILTER (WHERE NOT l.deleted), 0) AS last_id,
                       count(*) FILTER (WHERE l.deleted)::int AS dels
                FROM discord_message_link l WHERE l.ticket_id = ? AND l.direction = 'INBOUND'
            ), edits AS (
                SELECT count(*)::int AS n FROM case_event e
                JOIN discord_message_link l ON l.message_id::text = e.subject_id
                WHERE l.ticket_id = ? AND l.direction = 'INBOUND' AND e.type = 'message.edited'
                  AND e.punishment_identifier = (SELECT punishment_identifier FROM discord_ticket WHERE id = ?)
            ), upd AS (
                UPDATE discord_ticket t SET transcript_count = tally.cnt, transcript_last_message_id = NULLIF(tally.last_id, 0),
                    transcript_complete_at = ?, state = CASE WHEN t.state IN ('OPEN', 'CLOSING') THEN 'CLOSED' ELSE t.state END,
                    closed_at = COALESCE(t.closed_at, ?), updated_at = ?
                FROM tally
                WHERE t.id = ? AND t.transcript_complete_at IS NULL AND t.state IN ('OPEN', 'CLOSING', 'CLOSED')
                  AND tally.cnt = ? AND tally.last_id = ?
                RETURNING t.id, t.appeal_id, t.punishment_identifier
            ), ev AS (
                INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                                        recorded_by_oid, subject_id, visibility, payload, dedupe_key)
                SELECT gen_random_uuid(), upd.punishment_identifier, 'ticket.transcript_complete', 1, ?, 'LIVE', 'SYSTEM', ?::uuid,
                       upd.id::text, 'PLAYER',
                       jsonb_strip_nulls(jsonb_build_object('ticketId', upd.id::text, 'appealId', upd.appeal_id::text, 'count', tally.cnt,
                                         'lastMessageId', CASE WHEN tally.last_id > 0 THEN tally.last_id::text END,
                                         'edits', edits.n, 'deletes', tally.dels)),
                       'ticket.transcript_complete:' || upd.id::text
                FROM upd, tally, edits
                ON CONFLICT (dedupe_key) DO NOTHING
                RETURNING 1
            )
            SELECT count(*) FROM upd
            """;

    private static final String TALLY = """
            SELECT count(*) FILTER (WHERE NOT l.deleted)::int, COALESCE(max(l.discord_message_id) FILTER (WHERE NOT l.deleted), 0),
                   count(*) FILTER (WHERE l.deleted)::int,
                   (SELECT count(*)::int FROM case_event e JOIN discord_message_link x ON x.message_id::text = e.subject_id
                    WHERE x.ticket_id = ? AND x.direction = 'INBOUND' AND e.type = 'message.edited'
                      AND e.punishment_identifier = (SELECT punishment_identifier FROM discord_ticket WHERE id = ?))
            FROM discord_message_link l WHERE l.ticket_id = ? AND l.direction = 'INBOUND'
            """;

    /**
     * What to relay. A message that came from Discord is never relayed back. An add waits for its text to be current (the latest
     * edit) and is skipped when the message was deleted or redacted before it was relayed. An edit, delete or redaction is only
     * relayed for a message that has a {@code RELAYED} link, because that is the only Discord copy there is to change.
     */
    private static final ObjectMapper FILES = new ObjectMapper();

    private static final String OUTBOX = """
            SELECT e.seq, e.type, e.subject_id, e.occurred_at, e.payload -> 'author' ->> 'kind', e.payload ->> 'channel',
                   COALESCE(ed.text, e.payload ->> 'text'),
                   l.discord_message_id,
                   (SELECT COALESCE(jsonb_agg(jsonb_build_object('attachmentId', a ->> 'attachmentId', 'name', a ->> 'name',
                                                                 'contentType', a ->> 'contentType', 'size', (a ->> 'size')::bigint)), '[]'::jsonb)
                    FROM jsonb_array_elements(CASE WHEN jsonb_typeof(e.payload -> 'attachments') = 'array'
                                                   THEN e.payload -> 'attachments' ELSE '[]'::jsonb END) a
                    WHERE a ->> 'kind' = 'file' AND a ->> 'attachmentId' IS NOT NULL AND a ->> 'rejected' IS NULL)::text
            FROM discord_ticket t
            JOIN case_event e ON e.punishment_identifier = t.punishment_identifier
            LEFT JOIN discord_message_link l ON l.ticket_id = t.id AND l.message_id::text = e.subject_id AND l.direction = 'RELAYED'
            LEFT JOIN LATERAL (
                SELECT x.payload ->> 'text' AS text FROM case_event x
                WHERE x.punishment_identifier = e.punishment_identifier AND x.subject_id = e.subject_id
                  AND x.type = 'message.edited' AND x.redacted_at IS NULL
                ORDER BY x.seq DESC LIMIT 1
            ) ed ON e.type IN ('message.added', 'message.edited')
            WHERE t.id = ? AND e.seq > ? AND e.visibility = 'PLAYER'
              AND (
                (e.type = 'message.added' AND e.redacted_at IS NULL AND l.discord_message_id IS NULL
                 AND e.payload ->> 'channel' <> 'DISCORD'
                 AND (e.payload ->> 'appealId' IS NULL OR e.payload ->> 'appealId' = t.appeal_id::text)
                 AND NOT EXISTS (SELECT 1 FROM case_event d WHERE d.punishment_identifier = e.punishment_identifier
                                 AND d.subject_id = e.subject_id AND d.type IN ('message.deleted', 'message.redacted')))
                OR (e.type = 'message.edited' AND e.redacted_at IS NULL AND l.discord_message_id IS NOT NULL)
                OR (e.type IN ('message.deleted', 'message.redacted') AND l.discord_message_id IS NOT NULL)
                OR (e.type = 'evidence.shared' AND l.discord_message_id IS NULL
                    AND EXISTS (SELECT 1 FROM evidence_share s WHERE s.share_id::text = e.subject_id AND s.appeal_id = t.appeal_id)
                    AND NOT EXISTS (SELECT 1 FROM case_event r WHERE r.punishment_identifier = e.punishment_identifier
                                    AND r.subject_id = e.subject_id AND r.type = 'evidence.share_revoked'))
                OR (e.type = 'evidence.share_revoked' AND l.discord_message_id IS NOT NULL)
              )
            ORDER BY e.seq
            LIMIT ?
            """;

    private static final String ACKNOWLEDGE = """
            UPDATE discord_ticket SET relayed_seq = GREATEST(relayed_seq, ?), updated_at = ? WHERE id = ? RETURNING relayed_seq
            """;

    private static final String ACKNOWLEDGE_WITH_LINK = """
            WITH link AS (
                INSERT INTO discord_message_link (ticket_id, discord_message_id, message_id, direction, created_at)
                VALUES (?, ?, ?, 'RELAYED', ?)
                ON CONFLICT (ticket_id, discord_message_id) DO NOTHING
                RETURNING 1
            )
            UPDATE discord_ticket SET relayed_seq = GREATEST(relayed_seq, ?), updated_at = ? WHERE id = ? RETURNING relayed_seq
            """;

    private final DataSource dataSource;
    private final CaseChangeNotifier notifier;

    @Inject
    public JdbcDiscordTicketStore(DataSource dataSource, DataSourceResolver resolver, CaseChangeNotifier notifier) {
        // The injected source is transaction-aware and refuses connections outside a transaction.
        this.dataSource = resolver.resolve(dataSource);
        this.notifier = notifier;
    }

    /** For a plain data source, such as a test's. */
    public JdbcDiscordTicketStore(DataSource plainDataSource) {
        this(plainDataSource, CaseChangeNotifier.NONE);
    }

    /** For a plain data source and a notifier, such as a test's. */
    public JdbcDiscordTicketStore(DataSource plainDataSource, CaseChangeNotifier notifier) {
        this.dataSource = plainDataSource;
        this.notifier = notifier;
    }

    // ---- participants ----

    @Override
    public Participant ensureParticipant(String punishmentIdentifier, ParticipantKind kind, String discordUserId, String displayName,
                                         boolean bot, long now) {
        boolean anonymous = discordUserId == null;
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(anonymous ? INSERT_ANONYMOUS_PARTICIPANT : ENSURE_PARTICIPANT)) {
            int index = 0;
            statement.setObject(++index, UUID.randomUUID());
            statement.setString(++index, punishmentIdentifier);
            statement.setString(++index, kind.name());
            if (!anonymous) {
                statement.setString(++index, discordUserId);
            }
            statement.setString(++index, displayName);
            statement.setBoolean(++index, bot);
            statement.setLong(++index, now);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return participant(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not record a participant of " + punishmentIdentifier, e);
        }
    }

    @Override
    public Optional<Participant> participant(UUID id) {
        return one("SELECT " + PARTICIPANT_COLUMNS + " FROM case_participant WHERE id = ?", statement -> statement.setObject(1, id),
                JdbcDiscordTicketStore::participant);
    }

    @Override
    public Optional<Participant> participantByDiscordUser(String punishmentIdentifier, String discordUserId) {
        return one("SELECT " + PARTICIPANT_COLUMNS + " FROM case_participant WHERE punishment_identifier = ? AND discord_user_id = ?",
                statement -> {
                    statement.setString(1, punishmentIdentifier);
                    statement.setString(2, discordUserId);
                }, JdbcDiscordTicketStore::participant);
    }

    @Override
    public boolean eraseParticipant(String punishmentIdentifier, UUID participantId, long now) {
        return update("UPDATE case_participant SET discord_user_id = NULL, display_name = NULL, erased_at = ? "
                + "WHERE id = ? AND punishment_identifier = ? AND erased_at IS NULL", statement -> {
            statement.setLong(1, now);
            statement.setObject(2, participantId);
            statement.setString(3, punishmentIdentifier);
        }) == 1;
    }

    @Override
    public int erasePlayerParticipants(String punishmentIdentifier, long now) {
        return update("UPDATE case_participant SET discord_user_id = NULL, display_name = NULL, erased_at = ? "
                + "WHERE punishment_identifier = ? AND kind = 'PLAYER' AND erased_at IS NULL", statement -> {
            statement.setLong(1, now);
            statement.setString(2, punishmentIdentifier);
        });
    }

    // ---- tickets ----

    @Override
    public Optional<DiscordTicket> ticket(UUID id) {
        return one("SELECT " + TICKET_COLUMNS + " FROM discord_ticket WHERE id = ?", statement -> statement.setObject(1, id),
                JdbcDiscordTicketStore::ticket);
    }

    @Override
    public List<DiscordTicket> tickets(Collection<TicketState> states, int limit) {
        String[] names = states.stream().map(Enum::name).toArray(String[]::new);
        return many("SELECT " + TICKET_COLUMNS + " FROM discord_ticket WHERE state = ANY(?) ORDER BY ticket_no LIMIT ?", statement -> {
            Array array = statement.getConnection().createArrayOf("varchar", names);
            statement.setArray(1, array);
            statement.setInt(2, limit);
        }, JdbcDiscordTicketStore::ticket);
    }

    @Override
    public List<DiscordTicket> liveTicketsOf(String punishmentIdentifier) {
        return many("SELECT " + TICKET_COLUMNS + " FROM discord_ticket WHERE punishment_identifier = ? "
                + "AND state IN ('PENDING_CHANNEL', 'OPEN', 'CLOSING') ORDER BY ticket_no", statement -> statement.setString(1, punishmentIdentifier),
                JdbcDiscordTicketStore::ticket);
    }

    @Override
    public DiscordTicket open(NewTicket ticket) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(OPEN)) {
            int index = 0;
            statement.setObject(++index, ticket.id());
            statement.setString(++index, ticket.punishmentIdentifier());
            statement.setObject(++index, ticket.appealId());
            statement.setObject(++index, ticket.ownerParticipantId());
            statement.setLong(++index, ticket.now());
            statement.setLong(++index, ticket.now());
            statement.setObject(++index, ticket.recordedByOid());
            DiscordTicket opened;
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                opened = ticket(rows);
            }
            CaseEventSignals.byDedupeKey(this.dataSource, this.notifier, "ticket.opened:" + opened.id());
            return opened;
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new DuplicateTicketException(e);
            }
            throw new IllegalStateException("could not open a ticket for the case of " + ticket.punishmentIdentifier(), e);
        }
    }

    @Override
    public Optional<DiscordTicket> channelReady(UUID id, String channelId, String guildId, long now) {
        try {
            return one(CHANNEL_READY, statement -> {
                statement.setString(1, channelId);
                statement.setString(2, guildId);
                statement.setLong(3, now);
                statement.setObject(4, id);
            }, JdbcDiscordTicketStore::ticket);
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                throw new ChannelTakenException(sql);
            }
            throw e;
        }
    }

    @Override
    public Optional<DiscordTicket> transition(UUID id, Set<TicketState> from, TicketState to, long now) {
        String[] names = from.stream().map(Enum::name).toArray(String[]::new);
        String stamp = switch (to) {
            case CLOSED -> ", closed_at = COALESCE(closed_at, ?)";
            default -> "";
        };
        return one("UPDATE discord_ticket SET state = ?, updated_at = ?" + stamp + " WHERE id = ? AND state = ANY(?) RETURNING " + TICKET_COLUMNS,
                statement -> {
                    int index = 0;
                    statement.setString(++index, to.name());
                    statement.setLong(++index, now);
                    if (!stamp.isEmpty()) {
                        statement.setLong(++index, now);
                    }
                    statement.setObject(++index, id);
                    statement.setArray(++index, statement.getConnection().createArrayOf("varchar", names));
                }, JdbcDiscordTicketStore::ticket);
    }

    @Override
    public Optional<DiscordTicket> setFrozen(UUID id, boolean frozen, @Nullable String freezeSource, long now) {
        return one("UPDATE discord_ticket SET frozen = ?, freeze_source = ?, updated_at = ? WHERE id = ? RETURNING " + TICKET_COLUMNS, statement -> {
            statement.setBoolean(1, frozen);
            statement.setString(2, freezeSource);
            statement.setLong(3, now);
            statement.setObject(4, id);
        }, JdbcDiscordTicketStore::ticket);
    }

    @Override
    public Optional<DiscordTicket> archive(UUID id, int protocolVersion, long now, UUID recordedByOid) {
        Optional<DiscordTicket> archived = one(ARCHIVE, statement -> {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setObject(3, id);
            statement.setLong(4, now);
            statement.setObject(5, recordedByOid);
            statement.setInt(6, protocolVersion);
        }, JdbcDiscordTicketStore::ticket);
        archived.ifPresent(ticket -> CaseEventSignals.byDedupeKey(this.dataSource, this.notifier, "ticket.archived:" + ticket.id()));
        return archived;
    }

    @Override
    public Optional<DiscordTicket> deleted(UUID id, long now, UUID recordedByOid) {
        Optional<DiscordTicket> deleted = one(DELETE, statement -> {
            statement.setObject(1, id);
            statement.setLong(2, now);
            statement.setLong(3, now);
            statement.setObject(4, id);
            statement.setLong(5, now);
            statement.setObject(6, recordedByOid);
        }, JdbcDiscordTicketStore::ticket);
        deleted.ifPresent(ticket -> CaseEventSignals.byDedupeKey(this.dataSource, this.notifier, "ticket.channel_deleted:" + ticket.id()));
        return deleted;
    }

    @Override
    public boolean complete(UUID id, int count, long lastMessageId, long now, UUID recordedByOid) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(COMPLETE)) {
            int index = 0;
            statement.setObject(++index, id);
            statement.setObject(++index, id);
            statement.setObject(++index, id);
            statement.setLong(++index, now);
            statement.setLong(++index, now);
            statement.setLong(++index, now);
            statement.setObject(++index, id);
            statement.setInt(++index, count);
            statement.setLong(++index, lastMessageId);
            statement.setLong(++index, now);
            statement.setObject(++index, recordedByOid);
            boolean completed;
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                completed = rows.getLong(1) == 1;
            }
            if (completed) {
                CaseEventSignals.byDedupeKey(this.dataSource, this.notifier, "ticket.transcript_complete:" + id);
            }
            return completed;
        } catch (SQLException e) {
            throw new IllegalStateException("could not record the transcript of ticket " + id + " as complete", e);
        }
    }

    @Override
    public Tally tally(UUID ticketId) {
        return one(TALLY, statement -> {
            statement.setObject(1, ticketId);
            statement.setObject(2, ticketId);
            statement.setObject(3, ticketId);
        }, rows -> new Tally(rows.getInt(1), rows.getLong(2), rows.getInt(4), rows.getInt(3))).orElseThrow();
    }

    // ---- relay ----

    @Override
    public List<OutboxItem> outbox(UUID ticketId, long afterSeq, int limit) {
        return many(OUTBOX, statement -> {
            statement.setObject(1, ticketId);
            statement.setLong(2, afterSeq);
            statement.setInt(3, limit);
        }, rows -> {
            String type = rows.getString(2);
            OutboxItem.Kind kind = switch (type) {
                case "message.added" -> OutboxItem.Kind.ADD;
                case "message.edited" -> OutboxItem.Kind.EDIT;
                case "message.deleted" -> OutboxItem.Kind.DELETE;
                case "evidence.shared" -> OutboxItem.Kind.SHARE;
                case "evidence.share_revoked" -> OutboxItem.Kind.SHARE_REVOKED;
                default -> OutboxItem.Kind.REDACT;
            };
            String authorKind = rows.getString(5);
            String channel = rows.getString(6);
            Long discordMessageId = rows.getObject(8, Long.class);
            return new OutboxItem(rows.getLong(1), kind, rows.getString(3), authorKind == null ? null : MessageAuthorKind.valueOf(authorKind),
                    channel == null ? null : MessageChannel.valueOf(channel), rows.getString(7), discordMessageId, rows.getLong(4),
                    files(rows.getString(9)));
        });
    }

    @Override
    public Optional<Long> acknowledge(UUID ticketId, long seq, UUID messageId, Long discordMessageId, long now) {
        boolean link = messageId != null && discordMessageId != null;
        return one(link ? ACKNOWLEDGE_WITH_LINK : ACKNOWLEDGE, statement -> {
            int index = 0;
            if (link) {
                statement.setObject(++index, ticketId);
                statement.setLong(++index, discordMessageId);
                statement.setObject(++index, messageId);
                statement.setLong(++index, now);
            }
            statement.setLong(++index, seq);
            statement.setLong(++index, now);
            statement.setObject(++index, ticketId);
        }, rows -> rows.getLong(1));
    }

    /** The files of an outbox row, read from the JSON array the query built; a row without one has none. */
    private static List<OutboxItem.File> files(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            List<OutboxItem.File> files = new ArrayList<>();
            for (JsonNode file : FILES.readTree(json)) {
                files.add(new OutboxItem.File(file.path("attachmentId").asText(), file.path("name").asText(), file.path("contentType").asText(),
                        file.path("size").asLong()));
            }
            return List.copyOf(files);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not read the files of an outbox row", e);
        }
    }

    // ---- plumbing ----

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface Mapper<T> {
        T map(ResultSet rows) throws SQLException;
    }

    private <T> Optional<T> one(String sql, Binder binder, Mapper<T> mapper) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(mapper.map(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not run a ticket query", e);
        }
    }

    private <T> List<T> many(String sql, Binder binder, Mapper<T> mapper) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                List<T> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(mapper.map(rows));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not run a ticket query", e);
        }
    }

    private int update(String sql, Binder binder) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not run a ticket update", e);
        }
    }

    private static DiscordTicket ticket(ResultSet rows) throws SQLException {
        return new DiscordTicket(rows.getObject(1, UUID.class), rows.getLong(2), rows.getString(3), rows.getObject(4, UUID.class),
                rows.getObject(5, UUID.class), TicketState.valueOf(rows.getString(6)), rows.getString(7), rows.getString(8), rows.getBoolean(9),
                rows.getLong(10), rows.getLong(11), rows.getObject(12, Integer.class), rows.getObject(13, Long.class),
                rows.getObject(14, Long.class), rows.getLong(15), rows.getLong(16), rows.getString(17));
    }

    private static Participant participant(ResultSet rows) throws SQLException {
        return new Participant(rows.getObject(1, UUID.class), rows.getString(2), ParticipantKind.valueOf(rows.getString(3)), rows.getString(4),
                rows.getString(5), rows.getBoolean(6), rows.getLong(7), rows.getObject(8, Long.class));
    }
}
