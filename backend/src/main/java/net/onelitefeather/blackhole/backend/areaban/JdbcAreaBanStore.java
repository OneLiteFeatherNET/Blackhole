package net.onelitefeather.blackhole.backend.areaban;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL implementation of {@link AreaBanStore}, one statement per operation. */
@Singleton
public class JdbcAreaBanStore implements AreaBanStore {

    private static final String COLUMNS = """
            id, kind, value, reason, source, actor_oid, created_at, expires_at, lifted_at, lift_reason, lifted_by_oid
            """;
    private static final String INSERT = """
            INSERT INTO area_ban (id, kind, value, reason, source, actor_oid, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String FIND_BY_ID = "SELECT " + COLUMNS + " FROM area_ban WHERE id = ?";
    private static final String LIST_UNLIFTED = "SELECT " + COLUMNS + " FROM area_ban WHERE lifted_at IS NULL";
    private static final String FIND_UNLIFTED ="SELECT " + COLUMNS + " FROM area_ban WHERE kind = ? AND value = ? AND lifted_at IS NULL";
    private static final String LIFT = """
            UPDATE area_ban SET lifted_at = ?, lift_reason = ?, lifted_by_oid = ? WHERE id = ? AND lifted_at IS NULL
            """;
    private static final String UNIQUE_VIOLATION = "23505";

    private final DataSource dataSource;

    @Inject
    public JdbcAreaBanStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcAreaBanStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public boolean insert(AreaBan ban) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setObject(1, ban.id());
            statement.setString(2, ban.kind().name());
            statement.setString(3, ban.value());
            statement.setString(4, ban.reason());
            statement.setString(5, ban.source().name());
            setNullable(statement, 6, ban.actorOid());
            statement.setLong(7, ban.createdAt());
            if (ban.expiresAt() == null) {
                statement.setNull(8, Types.BIGINT);
            } else {
                statement.setLong(8, ban.expiresAt());
            }
            statement.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                return false;
            }
            throw new IllegalStateException("The area ban could not be stored", e);
        }
    }

    @Override
    public Optional<AreaBan> findById(UUID id) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_BY_ID)) {
            statement.setObject(1, id);
            return readOne(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("The area ban could not be read", e);
        }
    }

    @Override
    public java.util.List<AreaBan> listUnlifted() {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LIST_UNLIFTED);
             ResultSet rows = statement.executeQuery()) {
            java.util.List<AreaBan> bans = new java.util.ArrayList<>();
            while (rows.next()) {
                bans.add(readRow(rows));
            }
            return bans;
        } catch (SQLException e) {
            throw new IllegalStateException("The area bans could not be read", e);
        }
    }

    @Override
    public Optional<AreaBan> findUnlifted(AreaBan.Kind kind, String value) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_UNLIFTED)) {
            statement.setString(1, kind.name());
            statement.setString(2, value);
            return readOne(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("The area ban could not be read", e);
        }
    }

    @Override
    public boolean lift(AreaBan lifted) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LIFT)) {
            statement.setLong(1, lifted.liftedAt());
            statement.setString(2, lifted.liftReason());
            setNullable(statement, 3, lifted.liftedByOid());
            statement.setObject(4, lifted.id());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("The area ban could not be lifted", e);
        }
    }

    private static Optional<AreaBan> readOne(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            return rows.next() ? Optional.of(readRow(rows)) : Optional.empty();
        }
    }

    private static AreaBan readRow(ResultSet rows) throws SQLException {
        Long expires = nullableLong(rows, 8);
        Long lifted = nullableLong(rows, 9);
        return new AreaBan(
                rows.getObject(1, UUID.class),
                AreaBan.Kind.valueOf(rows.getString(2)),
                rows.getString(3),
                rows.getString(4),
                AreaBan.Source.valueOf(rows.getString(5)),
                rows.getString(6),
                rows.getLong(7),
                expires,
                lifted,
                rows.getString(10),
                rows.getString(11));
    }

    private static Long nullableLong(ResultSet rows, int column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static void setNullable(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
