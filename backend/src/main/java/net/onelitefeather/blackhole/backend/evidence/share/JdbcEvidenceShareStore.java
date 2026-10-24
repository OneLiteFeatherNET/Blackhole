package net.onelitefeather.blackhole.backend.evidence.share;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventDraft;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayloads;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL implementation of {@link EvidenceShareStore}: one data-modifying CTE per change. */
@Singleton
public class JdbcEvidenceShareStore implements EvidenceShareStore {

    private static final String COLUMNS = "share_id, punishment_identifier, appeal_id, evidence_id, mode, selection, appellant_speaker, "
            + "confirmed_minimised, shared_by_oid, shared_at, revoked_at, revoked_by_oid, revoke_reason";

    private static final String EVENT_COLUMNS = "identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust, "
            + "actor_oid, recorded_by_oid, actor_source, subject_id, visibility, payload, dedupe_key";
    private static final String EVENT_VALUES = "?::uuid, ?::varchar, ?::varchar, ?::smallint, ?::bigint, ?::varchar, ?::varchar, ?::uuid, ?::uuid, "
            + "?::uuid, ?::varchar, ?::varchar, ?::jsonb, ?::varchar";

    /** The share and its event; a conflict with the active share of the same item and selection inserts neither. */
    private static final String CREATE = """
            WITH s AS (
                INSERT INTO evidence_share (share_id, punishment_identifier, appeal_id, evidence_id, mode, selection, appellant_speaker,
                                            confirmed_minimised, shared_by_oid, shared_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (appeal_id, evidence_id, selection) WHERE revoked_at IS NULL DO NOTHING
                RETURNING share_id
            )
            INSERT INTO case_event (%s)
            SELECT %s FROM s
            ON CONFLICT (dedupe_key) DO NOTHING
            RETURNING identifier, seq, type
            """.formatted(EVENT_COLUMNS, EVENT_VALUES);

    private static final String REVOKE = """
            WITH r AS (
                UPDATE evidence_share SET revoked_at = ?, revoked_by_oid = ?, revoke_reason = ?
                WHERE share_id = ? AND punishment_identifier = ? AND revoked_at IS NULL
                RETURNING share_id
            )
            INSERT INTO case_event (%s)
            SELECT %s FROM r
            ON CONFLICT (dedupe_key) DO NOTHING
            RETURNING identifier, seq, type
            """.formatted(EVENT_COLUMNS, EVENT_VALUES);

    private static final String ACTIVE_TWIN = "SELECT " + COLUMNS + " FROM evidence_share "
            + "WHERE appeal_id = ? AND evidence_id = ? AND selection = ? AND revoked_at IS NULL";
    private static final String ONE = "SELECT " + COLUMNS + " FROM evidence_share WHERE share_id = ? AND punishment_identifier = ?";
    private static final String LIST = "SELECT " + COLUMNS + " FROM evidence_share WHERE punishment_identifier = ? ORDER BY shared_at, share_id";
    private static final String LATEST_APPEAL = "SELECT identifier FROM appeals WHERE punishment_identifier = ? AND status <> 6 "
            + "ORDER BY created_at DESC, identifier DESC LIMIT 1";

    private final DataSource dataSource;

    @Inject
    public JdbcEvidenceShareStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcEvidenceShareStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Created create(Share share, CaseEventDraft event) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(CREATE)) {
            int index = 0;
            statement.setObject(++index, share.shareId());
            statement.setString(++index, share.punishmentIdentifier());
            statement.setObject(++index, share.appealId());
            statement.setObject(++index, share.evidenceId());
            statement.setString(++index, share.mode());
            statement.setArray(++index, selection(connection, share.selection()));
            statement.setString(++index, share.appellantSpeaker());
            statement.setBoolean(++index, share.confirmedMinimised());
            statement.setObject(++index, share.sharedByOid());
            statement.setLong(++index, share.sharedAt());
            bindEvent(statement, index, event);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return new Created(share, new CaseChanges.Appended(rows.getObject(1, UUID.class), rows.getLong(2), rows.getString(3)), false);
                }
            }
            try (PreparedStatement twin = connection.prepareStatement(ACTIVE_TWIN)) {
                twin.setObject(1, share.appealId());
                twin.setObject(2, share.evidenceId());
                twin.setArray(3, selection(connection, share.selection()));
                try (ResultSet rows = twin.executeQuery()) {
                    if (rows.next()) {
                        return new Created(read(rows), null, true);
                    }
                }
            }
            throw new IllegalStateException("the share " + share.shareId() + " was neither stored nor found");
        } catch (SQLException e) {
            throw new IllegalStateException("could not store a share for " + share.punishmentIdentifier(), e);
        }
    }

    @Override
    public Optional<CaseChanges.Appended> revoke(UUID shareId, String punishmentIdentifier, long now, UUID revokedByOid, String reason,
                                                 CaseEventDraft event) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(REVOKE)) {
            int index = 0;
            statement.setLong(++index, now);
            statement.setObject(++index, revokedByOid);
            statement.setString(++index, reason);
            statement.setObject(++index, shareId);
            statement.setString(++index, punishmentIdentifier);
            bindEvent(statement, index, event);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? Optional.of(new CaseChanges.Appended(rows.getObject(1, UUID.class), rows.getLong(2), rows.getString(3)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not revoke the share " + shareId, e);
        }
    }

    @Override
    public Optional<Share> find(UUID shareId, String punishmentIdentifier) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(ONE)) {
            statement.setObject(1, shareId);
            statement.setString(2, punishmentIdentifier);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the share " + shareId, e);
        }
    }

    @Override
    public List<Share> list(String punishmentIdentifier) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LIST)) {
            statement.setString(1, punishmentIdentifier);
            try (ResultSet rows = statement.executeQuery()) {
                List<Share> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(read(rows));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not list the shares of " + punishmentIdentifier, e);
        }
    }

    @Override
    public Optional<UUID> latestAppeal(String punishmentIdentifier) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LATEST_APPEAL)) {
            statement.setString(1, punishmentIdentifier);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the appeals of " + punishmentIdentifier, e);
        }
    }

    private static Array selection(Connection connection, List<Integer> selection) throws SQLException {
        return connection.createArrayOf("int4", selection.toArray());
    }

    private static Share read(ResultSet rows) throws SQLException {
        List<Integer> selection = new ArrayList<>();
        Array array = rows.getArray(6);
        if (array != null) {
            for (Object value : (Object[]) array.getArray()) {
                selection.add(((Number) value).intValue());
            }
        }
        long revokedAt = rows.getLong(11);
        boolean revoked = !rows.wasNull();
        return new Share(rows.getObject(1, UUID.class), rows.getString(2), rows.getObject(3, UUID.class), rows.getObject(4, UUID.class),
                rows.getString(5), List.copyOf(selection), rows.getString(7), rows.getBoolean(8), rows.getObject(9, UUID.class), rows.getLong(10),
                revoked ? revokedAt : null, rows.getObject(12, UUID.class), rows.getString(13));
    }

    private static void bindEvent(PreparedStatement statement, int last, CaseEventDraft draft) throws SQLException {
        int index = last;
        statement.setObject(++index, UUID.randomUUID());
        statement.setString(++index, draft.punishmentIdentifier());
        statement.setString(++index, draft.type());
        statement.setShort(++index, (short) draft.payload().version());
        statement.setLong(++index, draft.occurredAt());
        statement.setString(++index, draft.origin().name());
        statement.setString(++index, draft.actor().trust().name());
        statement.setObject(++index, draft.actor().actorOid());
        statement.setObject(++index, draft.actor().recordedByOid());
        statement.setObject(++index, draft.actor().actorSource());
        statement.setString(++index, draft.subjectId());
        statement.setString(++index, draft.visibility());
        statement.setString(++index, CaseEventPayloads.toJson(draft.payload()));
        statement.setString(++index, draft.dedupeKey());
    }
}
