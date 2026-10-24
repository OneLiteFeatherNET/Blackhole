package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** PostgreSQL implementation of {@link PlayerSessionStore}: one statement per operation. */
@Singleton
public class JdbcPlayerSessionStore implements PlayerSessionStore {

    private static final String INSERT = """
            INSERT INTO player_session (token_hash, punishment_identifier, scope, csrf, created_at, last_seen_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    /** Validates and slides in one statement; a leak mark of the Ban ID is read live, so the scope is never stale. */
    private static final String TOUCH = """
            UPDATE player_session s SET last_seen_at = ?
            WHERE s.token_hash = ? AND s.expires_at > ? AND s.last_seen_at + ? > ?
            RETURNING s.punishment_identifier, s.scope, s.csrf, s.expires_at,
                      EXISTS (SELECT 1 FROM punishment_case c
                              WHERE c.punishment_identifier = s.punishment_identifier AND c.ban_id_leaked_at IS NOT NULL)
            """;

    /** Reads only: the same liveness test as {@link #TOUCH}, without moving the idle timer. */
    private static final String ALIVE = "SELECT 1 FROM player_session WHERE token_hash = ? AND expires_at > ? AND last_seen_at + ? > ?";

    private static final String DELETE = "DELETE FROM player_session WHERE token_hash = ?";
    private static final String SWEEP = "DELETE FROM player_session WHERE expires_at <= ? OR last_seen_at + ? <= ?";

    private final DataSource dataSource;

    @Inject
    public JdbcPlayerSessionStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcPlayerSessionStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public void insert(String tokenHash, String punishmentIdentifier, PlayerScope scope, String csrf, long now, long expiresAt) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, tokenHash);
            statement.setString(2, punishmentIdentifier);
            statement.setString(3, scope.name());
            statement.setString(4, csrf);
            statement.setLong(5, now);
            statement.setLong(6, now);
            statement.setLong(7, expiresAt);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not store a player session", e);
        }
    }

    @Override
    public Optional<Live> touch(String tokenHash, long now, long idleMillis) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(TOUCH)) {
            statement.setLong(1, now);
            statement.setString(2, tokenHash);
            statement.setLong(3, now);
            statement.setLong(4, idleMillis);
            statement.setLong(5, now);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                PlayerScope scope = rows.getBoolean(5) ? PlayerScope.LIMITED : PlayerScope.valueOf(rows.getString(2));
                return Optional.of(new Live(rows.getString(1), scope, rows.getString(3), rows.getLong(4)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read a player session", e);
        }
    }

    @Override
    public boolean alive(String tokenHash, long now, long idleMillis) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(ALIVE)) {
            statement.setString(1, tokenHash);
            statement.setLong(2, now);
            statement.setLong(3, idleMillis);
            statement.setLong(4, now);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read a player session", e);
        }
    }

    @Override
    public boolean delete(String tokenHash) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE)) {
            statement.setString(1, tokenHash);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("could not delete a player session", e);
        }
    }

    @Override
    public int sweep(long now, long idleMillis) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SWEEP)) {
            statement.setLong(1, now);
            statement.setLong(2, idleMillis);
            statement.setLong(3, now);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not sweep player sessions", e);
        }
    }
}
