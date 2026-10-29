package net.onelitefeather.blackhole.backend.guard;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/** PostgreSQL implementation of {@link GuardChangeStore}: one insert per change. */
@Singleton
public class JdbcGuardChangeStore implements GuardChangeStore {

    private static final String INSERT = """
            INSERT INTO join_guard_change (changed_at, previous_level, new_level, source, actor_oid) VALUES (?, ?, ?, ?, ?)
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcGuardChangeStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcGuardChangeStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public void append(GuardChange change) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setLong(1, change.changedAt());
            statement.setString(2, change.previous().name());
            statement.setString(3, change.next().name());
            statement.setString(4, change.source().name());
            if (change.actorOid() == null) {
                statement.setNull(5, Types.VARCHAR);
            } else {
                statement.setString(5, change.actorOid());
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("The join guard change could not be recorded", e);
        }
    }
}
