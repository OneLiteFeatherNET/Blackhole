package net.onelitefeather.blackhole.backend.integration;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** PostgreSQL implementation of {@link IntegrationStore}. The scope and kinds are {@code text[]} columns. */
@Singleton
public class JdbcIntegrationStore implements IntegrationStore {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String COLUMNS = "SELECT id, source_system, entra_client_id, display_name, enabled, allowed_kinds, "
            + "allowed_tasks, allowed_static_servers, created_at, updated_at FROM integration";
    private static final String INSERT = "INSERT INTO integration (source_system, entra_client_id, display_name, enabled, "
            + "allowed_kinds, allowed_tasks, allowed_static_servers, created_at, updated_at, created_by_oid, updated_by_oid) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
    private static final String UPDATE = "UPDATE integration SET display_name = ?, enabled = ?, allowed_kinds = ?, "
            + "allowed_tasks = ?, allowed_static_servers = ?, updated_at = ?, updated_by_oid = ? WHERE id = ?";

    private final DataSource dataSource;

    public JdbcIntegrationStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    @Override
    public Optional<Stored> find(long id) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(COLUMNS + " WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("integration registry could not be read", e);
        }
    }

    @Override
    public Optional<Stored> findByClientId(String entraClientId) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(COLUMNS + " WHERE entra_client_id = ? AND enabled")) {
            statement.setString(1, entraClientId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("integration registry could not be read", e);
        }
    }

    @Override
    public List<Stored> list() {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(COLUMNS + " ORDER BY source_system");
             ResultSet rows = statement.executeQuery()) {
            List<Stored> all = new java.util.ArrayList<>();
            while (rows.next()) {
                all.add(read(rows));
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("integration registry could not be read", e);
        }
    }

    @Override
    public OptionalLong insert(IntegrationSpec spec, UUID actorOid, long now) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, spec.sourceSystem());
            statement.setString(2, spec.entraClientId());
            statement.setString(3, spec.displayName());
            statement.setBoolean(4, spec.enabled());
            statement.setArray(5, array(connection, spec.allowedKinds()));
            statement.setArray(6, array(connection, spec.allowedTasks()));
            statement.setArray(7, array(connection, spec.allowedStaticServers()));
            statement.setLong(8, now);
            statement.setLong(9, now);
            statement.setObject(10, actorOid);
            statement.setObject(11, actorOid);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return OptionalLong.of(rows.getLong(1));
            }
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                return OptionalLong.empty();
            }
            throw new IllegalStateException("integration registration could not be written", e);
        }
    }

    @Override
    public boolean update(long id, IntegrationSpec spec, UUID actorOid, long now) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE)) {
            statement.setString(1, spec.displayName());
            statement.setBoolean(2, spec.enabled());
            statement.setArray(3, array(connection, spec.allowedKinds()));
            statement.setArray(4, array(connection, spec.allowedTasks()));
            statement.setArray(5, array(connection, spec.allowedStaticServers()));
            statement.setLong(6, now);
            statement.setObject(7, actorOid);
            statement.setLong(8, id);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("integration registration could not be updated", e);
        }
    }

    private static Stored read(ResultSet rows) throws SQLException {
        IntegrationSpec spec = new IntegrationSpec(
                rows.getString("source_system"),
                rows.getString("display_name"),
                rows.getString("entra_client_id"),
                rows.getBoolean("enabled"),
                strings(rows.getArray("allowed_kinds")),
                strings(rows.getArray("allowed_tasks")),
                strings(rows.getArray("allowed_static_servers")));
        return new Stored(rows.getLong("id"), spec, rows.getLong("created_at"), rows.getLong("updated_at"));
    }

    private static Array array(Connection connection, List<String> values) throws SQLException {
        return connection.createArrayOf("text", values.toArray(String[]::new));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
