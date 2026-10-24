package net.onelitefeather.blackhole.backend.appealconditions;

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
import java.util.UUID;

/** PostgreSQL implementation of {@link AppealConditionsStore}. The next number is taken in the insert itself; the primary key settles a race. */
@Singleton
public class JdbcAppealConditionsStore implements AppealConditionsStore {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String COLUMNS = "version, body, created_at, recorded_by_oid";

    private static final String APPEND = """
            INSERT INTO appeal_conditions (version, body, created_at, recorded_by_oid)
            SELECT coalesce(max(version), 0) + 1, ?, ?, ? FROM appeal_conditions
            RETURNING version
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcAppealConditionsStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcAppealConditionsStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Optional<Version> current() {
        return query("SELECT " + COLUMNS + " FROM appeal_conditions ORDER BY version DESC LIMIT 1").stream().findFirst();
    }

    @Override
    public List<Version> list() {
        return query("SELECT " + COLUMNS + " FROM appeal_conditions ORDER BY version DESC");
    }

    @Override
    public Optional<Version> append(String text, UUID recordedByOid, long now) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(APPEND)) {
            statement.setString(1, text);
            statement.setLong(2, now);
            statement.setObject(3, recordedByOid);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return Optional.of(new Version(rows.getInt(1), text, now, recordedByOid));
            }
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                return Optional.empty();
            }
            throw new IllegalStateException("could not save the appeal conditions", e);
        }
    }

    private List<Version> query(String sql) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<Version> versions = new ArrayList<>();
            while (rows.next()) {
                versions.add(new Version(rows.getInt(1), rows.getString(2), rows.getLong(3), rows.getObject(4, UUID.class)));
            }
            return versions;
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the appeal conditions", e);
        }
    }
}
