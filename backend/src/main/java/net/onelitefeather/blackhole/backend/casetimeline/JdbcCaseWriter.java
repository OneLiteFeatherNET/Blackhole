package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/** PostgreSQL implementation of {@link CaseWriter}: one data-modifying CTE per change. */
@Singleton
public class JdbcCaseWriter implements CaseWriter {

    private static final String REOPEN = """
            WITH upd AS (
                UPDATE punishment_case SET status = 'OPEN', version = version + 1, updated_at = ?
                WHERE punishment_identifier = ? AND status = 'CLOSED'
                RETURNING punishment_identifier
            )
            INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust,
                                    visibility, payload)
            SELECT ?, upd.punishment_identifier, 'case.status_changed', 1, ?, 'LIVE', 'SYSTEM', 'STAFF',
                   '{"from":"CLOSED","to":"OPEN"}'::jsonb
            FROM upd
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcCaseWriter(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcCaseWriter(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public boolean reopenIfClosed(String punishmentIdentifier, long now) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(REOPEN)) {
            statement.setLong(1, now);
            statement.setString(2, punishmentIdentifier);
            statement.setObject(3, UUID.randomUUID());
            statement.setLong(4, now);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("could not reopen the case of " + punishmentIdentifier, e);
        }
    }
}
