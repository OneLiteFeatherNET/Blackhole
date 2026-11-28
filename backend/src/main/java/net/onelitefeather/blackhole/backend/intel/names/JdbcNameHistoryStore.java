package net.onelitefeather.blackhole.backend.intel.names;

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
import java.util.Locale;
import java.util.UUID;

/** PostgreSQL implementation of {@link NameHistoryStore}, one statement per operation. */
@Singleton
public class JdbcNameHistoryStore implements NameHistoryStore {

    private static final String UPSERT = """
            INSERT INTO player_name_history (owner_hash, player_uuid, name_lower, name, first_seen, last_seen)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (owner_hash, name_lower) DO UPDATE SET
                name = CASE WHEN EXCLUDED.last_seen >= player_name_history.last_seen THEN EXCLUDED.name ELSE player_name_history.name END,
                first_seen = LEAST(player_name_history.first_seen, EXCLUDED.first_seen),
                last_seen = GREATEST(player_name_history.last_seen, EXCLUDED.last_seen)
            """;
    private static final String BY_OWNER = """
            SELECT owner_hash, player_uuid, name, first_seen, last_seen FROM player_name_history
            WHERE owner_hash = ? ORDER BY last_seen DESC, name_lower LIMIT ? OFFSET ?
            """;
    /** The newest name of each owner comes from a lateral lookup, so the matched name keeps its own first and last seen. */
    private static final String OWNERS_OF_NAME = """
            SELECT m.owner_hash, m.player_uuid, current.name, m.first_seen, m.last_seen
            FROM player_name_history m
            CROSS JOIN LATERAL (
                SELECT c.name FROM player_name_history c
                WHERE c.owner_hash = m.owner_hash
                ORDER BY c.last_seen DESC, c.name_lower
                LIMIT 1
            ) current
            WHERE m.name_lower = ?
            ORDER BY m.last_seen DESC, m.owner_hash
            LIMIT ?
            """;
    private static final String DELETE_BEFORE = "DELETE FROM player_name_history WHERE last_seen < ?";
    private static final String DELETE_BY_OWNER = "DELETE FROM player_name_history WHERE owner_hash = ?";

    private final DataSource dataSource;

    @Inject
    public JdbcNameHistoryStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcNameHistoryStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public void upsert(String ownerHash, UUID playerUuid, String playerName, long seenAt) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, ownerHash);
            statement.setObject(2, playerUuid);
            statement.setString(3, playerName.toLowerCase(Locale.ROOT));
            statement.setString(4, playerName);
            statement.setLong(5, seenAt);
            statement.setLong(6, seenAt);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not record the name of the player", e);
        }
    }

    @Override
    public List<NameRecord> findByOwner(String ownerHash, int limit, int offset) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(BY_OWNER)) {
            statement.setString(1, ownerHash);
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet rows = statement.executeQuery()) {
                List<NameRecord> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new NameRecord(rows.getString(1), rows.getObject(2, UUID.class), rows.getString(3),
                            rows.getLong(4), rows.getLong(5)));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the names of the player", e);
        }
    }

    @Override
    public List<OwnerRecord> findOwnersOfName(String name, int limit) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(OWNERS_OF_NAME)) {
            statement.setString(1, name.toLowerCase(Locale.ROOT));
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<OwnerRecord> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new OwnerRecord(rows.getString(1), rows.getObject(2, UUID.class), rows.getString(3),
                            rows.getLong(4), rows.getLong(5)));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the owners of the name", e);
        }
    }

    @Override
    public int deleteLastSeenBefore(long cutoff) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_BEFORE)) {
            statement.setLong(1, cutoff);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not delete the expired player names", e);
        }
    }

    @Override
    public int deleteByOwner(String ownerHash) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_BY_OWNER)) {
            statement.setString(1, ownerHash);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not delete the names of the player", e);
        }
    }
}
