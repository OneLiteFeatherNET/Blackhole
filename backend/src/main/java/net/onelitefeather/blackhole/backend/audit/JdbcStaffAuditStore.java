package net.onelitefeather.blackhole.backend.audit;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** PostgreSQL implementation of {@link StaffAuditStore}: one insert, so no transaction is needed. */
@Singleton
public class JdbcStaffAuditStore implements StaffAuditStore {

    private static final String INSERT = """
            INSERT INTO staff_audit (identifier, occurred_at, actor_oid, actor_name, surface, action,
                                     target_kind, target_ref, target_name, detail, recorded_by_oid)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcStaffAuditStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcStaffAuditStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public void insert(StaffAuditEntry entry) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setObject(1, entry.auditId());
            statement.setLong(2, entry.occurredAt());
            statement.setObject(3, entry.actorOid());
            statement.setString(4, entry.actorName());
            statement.setString(5, entry.surface().name());
            statement.setString(6, entry.action());
            statement.setString(7, entry.targetKind());
            statement.setString(8, entry.targetRef());
            statement.setObject(9, entry.recordedByOid());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not store the staff audit entry", e);
        }
    }
}
