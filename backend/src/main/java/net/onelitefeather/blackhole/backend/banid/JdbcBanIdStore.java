package net.onelitefeather.blackhole.backend.banid;

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
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link BanIdStore}: single statements, no transaction needed. Error
 * messages carry the SQLState only, because a statement parameter can be a Ban ID hash.
 */
@Singleton
public class JdbcBanIdStore implements BanIdStore {

    private static final String UNIQUE_VIOLATION = "23505";

    private final DataSource dataSource;

    @Inject
    public JdbcBanIdStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcBanIdStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Assignment assign(String punishmentIdentifier, byte[] hash, byte[] encrypted) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE punishments SET ban_id_hash = ?, ban_id_enc = ?, ban_id_gen = 1 WHERE identifier = ? AND ban_id_hash IS NULL")) {
            statement.setBytes(1, hash);
            statement.setBytes(2, encrypted);
            statement.setString(3, punishmentIdentifier);
            return statement.executeUpdate() == 1 ? Assignment.ASSIGNED : Assignment.UNCHANGED;
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                return Assignment.COLLISION;
            }
            throw failure(e);
        }
    }

    @Override
    public Optional<String> identifierByHash(byte[] hash) {
        return queryOne("SELECT identifier FROM punishments WHERE ban_id_hash = ?",
                statement -> statement.setBytes(1, hash), row -> row.getString(1));
    }

    @Override
    public Optional<byte[]> encrypted(String punishmentIdentifier) {
        return queryOne("SELECT ban_id_enc FROM punishments WHERE identifier = ? AND ban_id_enc IS NOT NULL",
                statement -> statement.setString(1, punishmentIdentifier), row -> row.getBytes(1));
    }

    @Override
    public boolean legacyShortIdExists(String shortIdKey) {
        return queryOne("SELECT 1 FROM punishments WHERE short_id = ? LIMIT 1",
                statement -> statement.setString(1, shortIdKey), row -> true).isPresent();
    }

    @Override
    public List<String> withoutBanId(int limit) {
        List<String> identifiers = new ArrayList<>();
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT identifier FROM punishments WHERE ban_id_hash IS NULL ORDER BY identifier LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    identifiers.add(rows.getString(1));
                }
            }
            return identifiers;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public List<Held> heldAfter(String after, int limit) {
        List<Held> held = new ArrayList<>();
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT identifier, ban_id_enc, ban_id_gen FROM punishments WHERE ban_id_hash IS NOT NULL AND ban_id_enc IS NOT NULL "
                             + "AND identifier > ? ORDER BY identifier LIMIT ?")) {
            statement.setString(1, after == null ? "" : after);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    held.add(new Held(rows.getString(1), rows.getBytes(2), rows.getInt(3)));
                }
            }
            return held;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public long countWithoutBanId() {
        return queryOne("SELECT count(*) FROM punishments WHERE ban_id_hash IS NULL", statement -> { }, row -> row.getLong(1)).orElse(0L);
    }

    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private interface Mapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    private <T> Optional<T> queryOne(String sql, Binder binder, Mapper<T> mapper) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(mapper.map(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private static IllegalStateException failure(SQLException e) {
        return new IllegalStateException("cannot access Ban IDs (SQLState " + e.getSQLState() + ")");
    }
}
