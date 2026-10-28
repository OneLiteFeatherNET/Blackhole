package net.onelitefeather.blackhole.backend.audit;

import io.micronaut.core.annotation.Nullable;
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
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link StaffAuditStore}. Each operation is one statement, so none needs a transaction.
 */
@Singleton
public class JdbcStaffAuditStore implements StaffAuditStore {

    private static final String INSERT = """
            INSERT INTO staff_audit (identifier, occurred_at, actor_oid, actor_name, surface, action,
                                     target_kind, target_ref, target_name, detail, recorded_by_oid)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)
            """;
    private static final String PAGE = """
            SELECT seq, identifier, occurred_at, actor_oid, actor_name, surface, action, target_kind, target_ref, recorded_by_oid
            FROM staff_audit
            WHERE seq < ?
              AND (CAST(? AS VARCHAR) IS NULL OR action = CAST(? AS VARCHAR))
              AND (CAST(? AS UUID) IS NULL OR actor_oid = CAST(? AS UUID))
              AND (CAST(? AS VARCHAR) IS NULL OR target_ref = CAST(? AS VARCHAR))
            ORDER BY seq DESC
            LIMIT ?
            """;
    private static final String DELETE_OLDER = """
            DELETE FROM staff_audit WHERE seq IN (SELECT seq FROM staff_audit WHERE occurred_at < ? ORDER BY seq LIMIT ?)
            """;
    private static final String CLEAR_NAME = "UPDATE staff_audit SET actor_name = NULL WHERE actor_oid = ?";

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

    @Override
    public List<Row> page(long beforeSeq, int limit, @Nullable String action, @Nullable UUID actorOid, @Nullable String targetRef) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(PAGE)) {
            statement.setLong(1, beforeSeq);
            statement.setString(2, action);
            statement.setString(3, action);
            statement.setObject(4, actorOid, Types.OTHER);
            statement.setObject(5, actorOid, Types.OTHER);
            statement.setString(6, targetRef);
            statement.setString(7, targetRef);
            statement.setInt(8, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<Row> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new Row(rows.getLong("seq"), new StaffAuditEntry(rows.getObject("identifier", UUID.class),
                            rows.getLong("occurred_at"), rows.getObject("actor_oid", UUID.class), rows.getString("actor_name"),
                            Surface.valueOf(rows.getString("surface")), rows.getString("action"), rows.getString("target_kind"),
                            rows.getString("target_ref"), rows.getObject("recorded_by_oid", UUID.class))));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the staff audit entries", e);
        }
    }

    @Override
    public int deleteOccurredBefore(long cutoff, int limit) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_OLDER)) {
            statement.setLong(1, cutoff);
            statement.setInt(2, limit);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not delete the expired staff audit entries", e);
        }
    }

    @Override
    public void clearActorName(UUID actorOid) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(CLEAR_NAME)) {
            statement.setObject(1, actorOid);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not clear the staff names of the audit entries", e);
        }
    }
}
