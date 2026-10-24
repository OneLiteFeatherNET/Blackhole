package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evidence.service.TranscriptAccess;

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

/**
 * PostgreSQL implementation of {@link ProtocolStore}: one statement per call, in auto-commit mode. Adding a
 * version is a data-modifying CTE that supersedes the previous active version and inserts the new one
 * together, guarded by the unique indexes of {@code V17__appeal_protocol.sql}.
 *
 * <p>It is also the {@link TranscriptAccess} of the evidence routes: a stored protocol is readable only
 * while its index row says so.</p>
 */
@Singleton
public class JdbcProtocolStore implements ProtocolStore, TranscriptAccess {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String COLUMNS = "identifier, punishment_identifier, appeal_id, version, evidence_id, source_digest, entry_count, "
            + "first_seq, last_seq, status, created_at";

    private static final String ACTIVE = "SELECT " + COLUMNS + " FROM appeal_protocol WHERE appeal_id = ? AND status = 'ACTIVE'";
    private static final String NEXT_VERSION = "SELECT COALESCE(MAX(version), 0) + 1 FROM appeal_protocol WHERE appeal_id = ?";
    private static final String VERSIONS_OF = "SELECT " + COLUMNS + " FROM appeal_protocol WHERE punishment_identifier = ? "
            + "ORDER BY created_at DESC, version DESC";
    private static final String BY_EVIDENCE = "SELECT " + COLUMNS + " FROM appeal_protocol WHERE evidence_id = ?";
    private static final String PURGE_PENDING = "SELECT " + COLUMNS + " FROM appeal_protocol WHERE status = 'PURGE_PENDING' "
            + "ORDER BY created_at, version LIMIT ?";
    private static final String MARK_PURGED = "UPDATE appeal_protocol SET status = 'PURGED', purged_at = ?, evidence_id = NULL "
            + "WHERE identifier = ? AND status = 'PURGE_PENDING'";
    private static final String SETTLE_LOST = "UPDATE appeal_protocol SET status = 'PURGED', purged_at = ? "
            + "WHERE status IN ('ACTIVE', 'SUPERSEDED') AND evidence_id IS NULL AND (?::uuid IS NULL OR appeal_id = ?::uuid)";
    private static final String MARK_FOR_ACTOR = "UPDATE appeal_protocol SET status = 'PURGE_PENDING' "
            + "WHERE status IN ('ACTIVE', 'SUPERSEDED') "
            + "AND punishment_identifier IN (SELECT punishment_identifier FROM case_event WHERE actor_oid = ?) "
            + "RETURNING punishment_identifier";
    private static final String READABLE = "SELECT status FROM appeal_protocol WHERE evidence_id = ?";

    /**
     * The new version, and with it the previous active version turned superseded. The insert is produced
     * only after the supersede statement returned a row (or when there is nothing to supersede), which
     * orders the two against the unique index on the active version. A readable version with the same
     * digest makes both a no-op.
     */
    private static final String ADD = """
            WITH dup AS (
                SELECT 1 FROM appeal_protocol WHERE appeal_id = ? AND source_digest = ? AND status IN ('ACTIVE', 'SUPERSEDED')
            ), sup AS (
                UPDATE appeal_protocol SET status = 'SUPERSEDED'
                WHERE appeal_id = ? AND status = 'ACTIVE' AND NOT EXISTS (SELECT 1 FROM dup)
                RETURNING identifier
            )
            INSERT INTO appeal_protocol (identifier, punishment_identifier, appeal_id, version, evidence_id, source_digest, entry_count,
                                         first_seq, last_seq, status, created_at)
            SELECT ?::uuid, ?::varchar, ?::uuid, ?::integer, ?::uuid, ?::varchar, ?::integer, ?::bigint, ?::bigint, 'ACTIVE', ?::bigint
            WHERE NOT EXISTS (SELECT 1 FROM dup)
              AND (NOT EXISTS (SELECT 1 FROM appeal_protocol WHERE appeal_id = ? AND status = 'ACTIVE')
                   OR EXISTS (SELECT 1 FROM sup))
            """;

    private static final String CANDIDATES = """
            SELECT a.identifier FROM appeals a
            LEFT JOIN appeal_protocol p ON p.appeal_id = a.identifier AND p.status = 'ACTIVE'
            WHERE a.status IN (3, 4, 5)
              AND (p.identifier IS NULL
                   OR EXISTS (SELECT 1 FROM appeal_protocol q WHERE q.appeal_id = a.identifier AND q.status = 'PURGE_PENDING')
                   OR EXISTS (SELECT 1 FROM case_event e
                              WHERE e.punishment_identifier = a.punishment_identifier AND e.seq > COALESCE(p.last_seq, 0)
                                AND (e.type LIKE 'message.%' OR e.type LIKE 'appeal.%' OR e.type LIKE 'ban_id.%' OR e.type LIKE 'ticket.%')))
            ORDER BY a.updated_at DESC, a.identifier
            LIMIT ?
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcProtocolStore(DataSource dataSource, DataSourceResolver resolver) {
        // The injected source is transaction-aware and refuses connections outside a transaction.
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcProtocolStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Optional<ProtocolVersion> active(UUID appealId) {
        return versions(ACTIVE, appealId).stream().findFirst();
    }

    @Override
    public int nextVersion(UUID appealId) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(NEXT_VERSION)) {
            statement.setObject(1, appealId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the protocol versions of appeal " + appealId, e);
        }
    }

    @Override
    public boolean add(NewVersion version) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(ADD)) {
            int index = 0;
            statement.setObject(++index, version.appealId());
            statement.setString(++index, version.sourceDigest());
            statement.setObject(++index, version.appealId());
            statement.setObject(++index, version.identifier());
            statement.setString(++index, version.punishmentIdentifier());
            statement.setObject(++index, version.appealId());
            statement.setInt(++index, version.version());
            statement.setObject(++index, version.evidenceId());
            statement.setString(++index, version.sourceDigest());
            statement.setInt(++index, version.entryCount());
            setLong(statement, ++index, version.firstSeq());
            setLong(statement, ++index, version.lastSeq());
            statement.setLong(++index, version.createdAt());
            statement.setObject(++index, version.appealId());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                // A concurrent run took the version number or stored the first version at the same time.
                return false;
            }
            throw new IllegalStateException("could not add a protocol version of appeal " + version.appealId(), e);
        }
    }

    @Override
    public List<ProtocolVersion> versionsOf(String punishmentIdentifier) {
        return versions(VERSIONS_OF, punishmentIdentifier);
    }

    @Override
    public Optional<ProtocolVersion> byEvidence(UUID evidenceId) {
        return versions(BY_EVIDENCE, evidenceId).stream().findFirst();
    }

    @Override
    public List<ProtocolVersion> purgePending(int limit) {
        return versions(PURGE_PENDING, limit);
    }

    @Override
    public boolean markPurged(UUID identifier, long now) {
        return update(MARK_PURGED, now, identifier) == 1;
    }

    @Override
    public int settleLostEvidence(UUID appealId, long now) {
        return update(SETTLE_LOST, now, appealId, appealId);
    }

    @Override
    public int markPurgePending(String punishmentIdentifier) {
        return update(MARK_PURGE_PENDING, punishmentIdentifier);
    }

    @Override
    public List<String> markPurgePendingForActor(UUID actorOid) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(MARK_FOR_ACTOR)) {
            statement.setObject(1, actorOid);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> affected = new ArrayList<>();
                while (rows.next()) {
                    String punishment = rows.getString(1);
                    if (!affected.contains(punishment)) {
                        affected.add(punishment);
                    }
                }
                return affected;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not mark the protocols of a staff actor for purging", e);
        }
    }

    @Override
    public List<UUID> candidates(int limit) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(CANDIDATES)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<UUID> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(rows.getObject(1, UUID.class));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not find the appeals that need a protocol", e);
        }
    }

    @Override
    public boolean readable(UUID evidenceId) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(READABLE)) {
            statement.setObject(1, evidenceId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && ProtocolVersion.Status.valueOf(rows.getString(1)).readable();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not check whether protocol evidence " + evidenceId + " is readable", e);
        }
    }

    private List<ProtocolVersion> versions(String sql, Object... parameters) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<ProtocolVersion> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new ProtocolVersion(rows.getObject(1, UUID.class), rows.getString(2), rows.getObject(3, UUID.class),
                            rows.getInt(4), rows.getObject(5, UUID.class), rows.getString(6), rows.getInt(7),
                            rows.getObject(8, Long.class), rows.getObject(9, Long.class), ProtocolVersion.Status.valueOf(rows.getString(10)),
                            rows.getLong(11)));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read protocol versions", e);
        }
    }

    private int update(String sql, Object... parameters) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i] == null) {
                    statement.setNull(i + 1, Types.OTHER);
                } else {
                    statement.setObject(i + 1, parameters[i]);
                }
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not change protocol versions", e);
        }
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}
