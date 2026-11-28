package net.onelitefeather.blackhole.backend.guard;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/** PostgreSQL implementation of {@link OwnerDirectoryStore}, read from player_sighting and staff_link. */
@Singleton
public class JdbcOwnerDirectoryStore implements OwnerDirectoryStore {

    private static final String KNOWN = """
            SELECT owner_hash FROM player_sighting WHERE ended_at IS NOT NULL
            GROUP BY owner_hash HAVING SUM(ended_at - started_at) >= ?
            """;
    private static final String STAFF = """
            SELECT DISTINCT s.owner_hash FROM player_sighting s JOIN staff_link l ON l.minecraft_uuid = s.player_uuid
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcOwnerDirectoryStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcOwnerDirectoryStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Set<String> knownOwners(long minPlaytimeMillis) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(KNOWN)) {
            statement.setLong(1, minPlaytimeMillis);
            return owners(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("The known owners could not be read", e);
        }
    }

    @Override
    public Set<String> staffOwners() {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(STAFF)) {
            return owners(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("The staff owners could not be read", e);
        }
    }

    private static Set<String> owners(PreparedStatement statement) throws SQLException {
        Set<String> owners = new HashSet<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                owners.add(rows.getString(1));
            }
        }
        return owners;
    }
}
