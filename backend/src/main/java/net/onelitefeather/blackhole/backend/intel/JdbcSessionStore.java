package net.onelitefeather.blackhole.backend.intel;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL implementation of {@link SessionStore}, one statement per operation. */
@Singleton
public class JdbcSessionStore implements SessionStore {

    private static final String COLUMNS = """
            session_id, owner_hash, player_uuid, player_name, ip, ip_token, asn, as_org, country_code,
            client_brand, client_version, protocol_version, virtual_host, outcome, started_at, ended_at
            """;
    private static final String INSERT = """
            INSERT INTO player_sighting (session_id, owner_hash, player_uuid, player_name, ip, ip_token, asn, as_org,
                country_code, client_brand, client_version, protocol_version, virtual_host, outcome, started_at, ended_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (session_id) DO NOTHING
            """;
    private static final String FIND = "SELECT " + COLUMNS + " FROM player_sighting WHERE session_id = ?";
    private static final String UPDATE_CLIENT = """
            UPDATE player_sighting SET client_brand = COALESCE(?, client_brand), client_version = COALESCE(?, client_version)
            WHERE session_id = ?
            """;
    private static final String MARK_ENDED = "UPDATE player_sighting SET ended_at = ? WHERE session_id = ? AND ended_at IS NULL";
    private static final String BY_OWNER = "SELECT " + COLUMNS
            + " FROM player_sighting WHERE owner_hash = ? ORDER BY started_at DESC, session_id LIMIT ? OFFSET ?";
    private static final String DELETE_BEFORE = "DELETE FROM player_sighting WHERE started_at < ?";
    private static final String TOKENS_OF_OWNER = """
            SELECT DISTINCT ip_token FROM player_sighting WHERE owner_hash = ? AND started_at >= ? AND ip_token IS NOT NULL
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcSessionStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcSessionStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public boolean insert(SessionRecord r) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setObject(1, r.sessionId());
            statement.setString(2, r.ownerHash());
            statement.setObject(3, r.playerUuid());
            statement.setString(4, r.playerName());
            statement.setString(5, r.ip());
            statement.setObject(6, r.ipToken(), Types.CHAR);
            statement.setObject(7, r.asn(), Types.BIGINT);
            statement.setObject(8, r.asOrg(), Types.VARCHAR);
            statement.setObject(9, r.countryCode(), Types.CHAR);
            statement.setObject(10, r.clientBrand(), Types.VARCHAR);
            statement.setObject(11, r.clientVersion(), Types.VARCHAR);
            statement.setObject(12, r.protocolVersion(), Types.INTEGER);
            statement.setObject(13, r.virtualHost(), Types.VARCHAR);
            statement.setString(14, r.outcome().name());
            statement.setLong(15, r.startedAt());
            statement.setObject(16, r.endedAt(), Types.BIGINT);
            statement.setLong(17, r.startedAt());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("could not store the player session", e);
        }
    }

    @Override
    public Optional<SessionRecord> find(UUID sessionId) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND)) {
            statement.setObject(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the player session", e);
        }
    }

    @Override
    public void updateClient(UUID sessionId, String clientBrand, String clientVersion) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_CLIENT)) {
            statement.setObject(1, clientBrand, Types.VARCHAR);
            statement.setObject(2, clientVersion, Types.VARCHAR);
            statement.setObject(3, sessionId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not update the client of the player session", e);
        }
    }

    @Override
    public void markEnded(UUID sessionId, long endedAt) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(MARK_ENDED)) {
            statement.setLong(1, endedAt);
            statement.setObject(2, sessionId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not end the player session", e);
        }
    }

    @Override
    public List<SessionRecord> findByOwner(String ownerHash, int limit, int offset) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(BY_OWNER)) {
            statement.setString(1, ownerHash);
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet rows = statement.executeQuery()) {
                List<SessionRecord> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(read(rows));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the player sessions", e);
        }
    }

    @Override
    public int deleteStartedBefore(long cutoff) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_BEFORE)) {
            statement.setLong(1, cutoff);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not delete the expired player sessions", e);
        }
    }

    @Override
    public Set<String> tokensOfOwner(String ownerHash, long since) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(TOKENS_OF_OWNER)) {
            statement.setString(1, ownerHash);
            statement.setLong(2, since);
            try (ResultSet rows = statement.executeQuery()) {
                Set<String> tokens = new HashSet<>();
                while (rows.next()) {
                    tokens.add(rows.getString(1));
                }
                return tokens;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the session tokens of an owner", e);
        }
    }

    @Override
    public List<TokenSighting> sightingsOfTokens(Collection<String> tokens, long since) {
        if (tokens.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(tokens.size(), "?"));
        String sql = "SELECT ip_token, owner_hash, started_at FROM player_sighting WHERE started_at >= ? AND ip_token IN ("
                + placeholders + ")";
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, since);
            int index = 2;
            for (String token : tokens) {
                statement.setString(index++, token);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<TokenSighting> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new TokenSighting(rows.getString(1), rows.getString(2), rows.getLong(3)));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the sessions of the tokens", e);
        }
    }

    private static SessionRecord read(ResultSet rows) throws SQLException {
        return new SessionRecord(
                rows.getObject("session_id", UUID.class),
                rows.getString("owner_hash"),
                rows.getObject("player_uuid", UUID.class),
                rows.getString("player_name"),
                rows.getString("ip"),
                rows.getString("ip_token"),
                nullableLong(rows, "asn"),
                rows.getString("as_org"),
                rows.getString("country_code"),
                rows.getString("client_brand"),
                rows.getString("client_version"),
                nullableInt(rows, "protocol_version"),
                rows.getString("virtual_host"),
                SessionOutcome.valueOf(rows.getString("outcome")),
                rows.getLong("started_at"),
                nullableLong(rows, "ended_at"));
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }
}
