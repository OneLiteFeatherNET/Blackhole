package net.onelitefeather.blackhole.backend.staffprofile;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** {@link StaffAvatarRepository} over plain JDBC. */
@Singleton
public class JdbcStaffAvatarRepository implements StaffAvatarRepository {

    private static final String FIND = "SELECT png FROM staff_avatar WHERE avatar_key = ?";
    private static final String SAVE = """
            INSERT INTO staff_avatar (avatar_key, png, fetched_at) VALUES (?, ?, ?)
            ON CONFLICT (avatar_key) DO UPDATE SET png = EXCLUDED.png, fetched_at = EXCLUDED.fetched_at
            """;
    private static final String DELETE = "DELETE FROM staff_avatar WHERE avatar_key = ?";

    private final DataSource dataSource;

    public JdbcStaffAvatarRepository(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    @Override
    public Optional<byte[]> find(String avatarKey) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND)) {
            statement.setString(1, avatarKey);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(rows.getBytes(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading a staff avatar failed, SQLState " + e.getSQLState(), e);
        }
    }

    @Override
    public void save(String avatarKey, byte[] png, long fetchedAt) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SAVE)) {
            statement.setString(1, avatarKey);
            statement.setBytes(2, png);
            statement.setLong(3, fetchedAt);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("saving a staff avatar failed, SQLState " + e.getSQLState(), e);
        }
    }

    @Override
    public void delete(String avatarKey) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE)) {
            statement.setString(1, avatarKey);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("deleting a staff avatar failed, SQLState " + e.getSQLState(), e);
        }
    }
}
