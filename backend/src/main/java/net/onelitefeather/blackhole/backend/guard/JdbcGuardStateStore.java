package net.onelitefeather.blackhole.backend.guard;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** PostgreSQL implementation of {@link GuardStateStore}: one statement per operation, no transaction. */
@Singleton
public class JdbcGuardStateStore implements GuardStateStore {

    private static final String CURRENT = "SELECT manual_level, automatic_level, effective_level, updated_at FROM join_guard_state WHERE id = 1";
    private static final String COMPARE_AND_SET = """
            UPDATE join_guard_state SET manual_level = ?, automatic_level = ?, effective_level = ?, updated_at = ?
            WHERE id = 1 AND manual_level = ? AND automatic_level = ? AND effective_level = ?
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcGuardStateStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcGuardStateStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Optional<GuardState> current() {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(CURRENT);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return Optional.empty();
            }
            return Optional.of(new GuardState(GuardLevel.valueOf(rows.getString(1)),
                    GuardLevel.valueOf(rows.getString(2)), GuardLevel.valueOf(rows.getString(3)), rows.getLong(4)));
        } catch (SQLException e) {
            throw new IllegalStateException("The join guard state could not be read", e);
        }
    }

    @Override
    public boolean compareAndSet(GuardState expected, GuardState next) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(COMPARE_AND_SET)) {
            statement.setString(1, next.manual().name());
            statement.setString(2, next.automatic().name());
            statement.setString(3, next.effective().name());
            statement.setLong(4, next.updatedAt());
            statement.setString(5, expected.manual().name());
            statement.setString(6, expected.automatic().name());
            statement.setString(7, expected.effective().name());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("The join guard state could not be written", e);
        }
    }
}
