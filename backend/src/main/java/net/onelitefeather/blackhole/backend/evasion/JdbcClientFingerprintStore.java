package net.onelitefeather.blackhole.backend.evasion;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** PostgreSQL implementation of {@link ClientFingerprintStore}; a sighting is one upsert statement. */
@Singleton
public class JdbcClientFingerprintStore implements ClientFingerprintStore {

    private static final String RECORD = """
            INSERT INTO client_fingerprint (fingerprint, scheme, owner_hash, first_seen, last_seen, occurrence_count)
            VALUES (?, ?, ?, ?, ?, 1)
            ON CONFLICT (fingerprint, owner_hash) DO UPDATE
                SET last_seen = EXCLUDED.last_seen, occurrence_count = client_fingerprint.occurrence_count + 1
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcClientFingerprintStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcClientFingerprintStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public void record(String fingerprint, String scheme, String owner, long now) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(RECORD)) {
            statement.setString(1, fingerprint);
            statement.setString(2, scheme);
            statement.setString(3, owner);
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not record the client fingerprint", e);
        }
    }

    @Override
    public List<Sighting> ofFingerprint(String fingerprint, long since) {
        return ofFingerprints(List.of(fingerprint), since);
    }

    @Override
    public List<String> fingerprintsOf(String owner, long since) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT fingerprint FROM client_fingerprint WHERE owner_hash = ? AND last_seen >= ?")) {
            statement.setString(1, owner);
            statement.setLong(2, since);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(rows.getString(1));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the client fingerprints", e);
        }
    }

    @Override
    public List<Sighting> ofFingerprints(Collection<String> fingerprints, long since) {
        if (fingerprints.isEmpty()) {
            return List.of();
        }
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT fingerprint, owner_hash, first_seen, last_seen FROM client_fingerprint WHERE fingerprint = ANY (?) AND last_seen >= ?")) {
            statement.setArray(1, connection.createArrayOf("text", fingerprints.toArray()));
            statement.setLong(2, since);
            try (ResultSet rows = statement.executeQuery()) {
                List<Sighting> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new Sighting(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getLong(4)));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the client fingerprints", e);
        }
    }

    @Override
    public int deleteBefore(long cutoff) {
        return update("DELETE FROM client_fingerprint WHERE last_seen < ?", cutoff);
    }

    @Override
    public int deleteByOwner(String owner) {
        return update("DELETE FROM client_fingerprint WHERE owner_hash = ?", owner);
    }

    private int update(String sql, Object parameter) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not delete client fingerprints", e);
        }
    }
}
