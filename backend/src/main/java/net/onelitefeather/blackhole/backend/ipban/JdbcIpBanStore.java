package net.onelitefeather.blackhole.backend.ipban;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.intel.IntelPrefix;

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

/** PostgreSQL implementation of {@link IpBanStore}, one statement per operation. */
@Singleton
public class JdbcIpBanStore implements IpBanStore {

    private static final String COLUMNS = """
            id, network, reason, template_key, source, punishment_id, case_id, actor_oid, created_at, expires_at,
            lifted_at, lifted_by_oid, lift_reason
            """;
    private static final String FIND = "SELECT " + COLUMNS + " FROM ip_bans WHERE id = ?";
    private static final String ACTIVE = "SELECT " + COLUMNS
            + " FROM ip_bans WHERE lifted_at IS NULL AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at, id";
    private static final String MARKER = "SELECT count(*), coalesce(max(created_at), 0), coalesce(max(lifted_at), 0) FROM ip_bans";
    private static final String NEWEST = "SELECT " + COLUMNS + " FROM ip_bans ORDER BY created_at DESC, id LIMIT ?";
    private static final String INSERT = """
            INSERT INTO ip_bans (id, network, family, prefix_length, reason, template_key, source, punishment_id, case_id,
                actor_oid, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
            """;
    private static final String LIFT = """
            UPDATE ip_bans SET lifted_at = ?, lifted_by_oid = ?, lift_reason = ? WHERE id = ? AND lifted_at IS NULL
            """;
    private static final String LIFT_EXPIRED = """
            UPDATE ip_bans SET lifted_at = ?, lift_reason = 'EXPIRED' WHERE network = ? AND lifted_at IS NULL AND expires_at <= ?
            """;
    private static final String DELETE_CLOSED = """
            DELETE FROM ip_bans WHERE (lifted_at IS NOT NULL AND lifted_at < ?) OR (lifted_at IS NULL AND expires_at IS NOT NULL AND expires_at < ?)
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcIpBanStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcIpBanStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Optional<IpBanRecord> find(UUID id) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND)) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the IP ban", e);
        }
    }

    @Override
    public List<IpBanRecord> active(long now) {
        return query(ACTIVE, statement -> statement.setLong(1, now));
    }

    @Override
    public Marker marker() {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(MARKER);
             ResultSet row = statement.executeQuery()) {
            row.next();
            return new Marker(row.getLong(1), row.getLong(2), row.getLong(3));
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the IP ban marker", e);
        }
    }

    @Override
    public List<IpBanRecord> newest(int limit) {
        return query(NEWEST, statement -> statement.setInt(1, limit));
    }

    @Override
    public boolean insert(IpBanRecord record) {
        IntelPrefix network = record.network();
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setObject(1, record.id());
            statement.setString(2, network.toString());
            statement.setInt(3, network.ipv4() ? 4 : 6);
            statement.setInt(4, network.length());
            statement.setString(5, record.reason());
            statement.setObject(6, record.templateKey(), Types.VARCHAR);
            statement.setString(7, record.source().name());
            statement.setObject(8, record.punishmentId(), Types.OTHER);
            statement.setObject(9, record.caseId(), Types.OTHER);
            statement.setObject(10, record.actorOid(), Types.OTHER);
            statement.setLong(11, record.createdAt());
            statement.setObject(12, record.expiresAt(), Types.BIGINT);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("could not store the IP ban", e);
        }
    }

    @Override
    public boolean lift(UUID id, UUID liftedBy, String reason, long at) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LIFT)) {
            statement.setLong(1, at);
            statement.setObject(2, liftedBy, Types.OTHER);
            statement.setString(3, reason);
            statement.setObject(4, id);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("could not lift the IP ban", e);
        }
    }

    @Override
    public int liftExpired(String range, long now) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LIFT_EXPIRED)) {
            statement.setLong(1, now);
            statement.setString(2, range);
            statement.setLong(3, now);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not close the expired IP bans", e);
        }
    }

    @Override
    public int deleteClosedBefore(long cutoff) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_CLOSED)) {
            statement.setLong(1, cutoff);
            statement.setLong(2, cutoff);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not delete the closed IP bans", e);
        }
    }

    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private List<IpBanRecord> query(String sql, Binder binder) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                List<IpBanRecord> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(read(rows));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the IP bans", e);
        }
    }

    private static IpBanRecord read(ResultSet rows) throws SQLException {
        return new IpBanRecord(
                rows.getObject("id", UUID.class),
                IntelPrefix.parseCidr(rows.getString("network")).orElseThrow(),
                rows.getString("reason"),
                rows.getString("template_key"),
                IpBanSource.valueOf(rows.getString("source")),
                rows.getObject("punishment_id", UUID.class),
                rows.getObject("case_id", UUID.class),
                rows.getObject("actor_oid", UUID.class),
                rows.getLong("created_at"),
                rows.getObject("expires_at", Long.class),
                rows.getObject("lifted_at", Long.class),
                rows.getObject("lifted_by_oid", UUID.class),
                rows.getString("lift_reason")
        );
    }
}
