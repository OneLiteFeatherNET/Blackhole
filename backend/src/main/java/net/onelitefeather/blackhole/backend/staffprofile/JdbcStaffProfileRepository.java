package net.onelitefeather.blackhole.backend.staffprofile;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** {@link StaffProfileRepository} over plain JDBC. Parameters are never logged: a folded name is personal data. */
@Singleton
public class JdbcStaffProfileRepository implements StaffProfileRepository {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String FIND = """
            SELECT oid, display_name, display_name_folded, avatar_mode, avatar_key, notice_acknowledged_at, updated_at, anonymized_at
            FROM staff_profile WHERE oid = ?
            """;
    private static final String FIND_BY_NAME = "SELECT oid FROM staff_profile WHERE display_name_folded = ?";
    private static final String SAVE = """
            INSERT INTO staff_profile (oid, display_name, display_name_folded, avatar_mode, avatar_key, notice_acknowledged_at,
                                       updated_at, anonymized_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (oid) DO UPDATE SET display_name = EXCLUDED.display_name,
                                            display_name_folded = EXCLUDED.display_name_folded,
                                            avatar_mode = EXCLUDED.avatar_mode,
                                            avatar_key = EXCLUDED.avatar_key,
                                            notice_acknowledged_at = EXCLUDED.notice_acknowledged_at,
                                            updated_at = EXCLUDED.updated_at,
                                            anonymized_at = EXCLUDED.anonymized_at
            """;

    private final DataSource dataSource;

    public JdbcStaffProfileRepository(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    @Override
    public Optional<StaffProfile> find(UUID oid) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND)) {
            statement.setObject(1, oid);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading a staff profile failed, SQLState " + e.getSQLState(), e);
        }
    }

    @Override
    public Optional<UUID> findOidByFoldedName(String displayNameFolded) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_BY_NAME)) {
            statement.setString(1, displayNameFolded);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("looking up a display name failed, SQLState " + e.getSQLState(), e);
        }
    }

    @Override
    public void save(StaffProfile profile) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SAVE)) {
            statement.setObject(1, profile.oid());
            statement.setString(2, profile.displayName());
            statement.setString(3, profile.displayNameFolded());
            statement.setString(4, profile.avatarMode());
            statement.setString(5, profile.avatarKey());
            statement.setObject(6, profile.noticeAcknowledgedAt(), java.sql.Types.BIGINT);
            statement.setLong(7, profile.updatedAt());
            statement.setObject(8, profile.anonymizedAt(), java.sql.Types.BIGINT);
            statement.executeUpdate();
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new StaffNameTakenException();
            }
            throw new IllegalStateException("saving a staff profile failed, SQLState " + e.getSQLState(), e);
        }
    }

    private static StaffProfile read(ResultSet rows) throws SQLException {
        long noticeAt = rows.getLong("notice_acknowledged_at");
        Long notice = rows.wasNull() ? null : noticeAt;
        long anonymizedAt = rows.getLong("anonymized_at");
        Long anonymized = rows.wasNull() ? null : anonymizedAt;
        return new StaffProfile(
                rows.getObject("oid", UUID.class),
                rows.getString("display_name"),
                rows.getString("display_name_folded"),
                rows.getString("avatar_mode"),
                rows.getString("avatar_key"),
                notice,
                rows.getLong("updated_at"),
                anonymized);
    }
}
