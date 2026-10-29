package net.onelitefeather.blackhole.backend.rollback;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * PostgreSQL implementation of {@link RollbackJobStore}. The job and its items are one unit: they share
 * one connection with auto-commit off, committed together or rolled back together. This is explicit JDBC,
 * not {@code @Transactional}, which is unusable in this codebase.
 */
@Singleton
public class JdbcRollbackJobStore implements RollbackJobStore {

    private static final String INSERT_JOB = """
            INSERT INTO staff_rollback_job (id, state, selector_oid, selector_minecraft_uuid, window_from, window_to, requested_by_oid,
                                            created_at, preview_expires_at)
            VALUES (?, 'PREVIEW', ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_ITEM = """
            INSERT INTO staff_rollback_item (job_id, punishment_identifier, owner, section_key, kind, state)
            VALUES (?, ?, ?, ?, ?, 'PENDING')
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcRollbackJobStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcRollbackJobStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public void insertPreview(PreviewJob job, List<FrozenItem> items) {
        try (Connection connection = this.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(INSERT_JOB)) {
                    statement.setObject(1, job.id());
                    statement.setObject(2, selectorOid(job.selector()));
                    statement.setObject(3, selectorMinecraftUuid(job.selector()));
                    statement.setLong(4, job.windowFrom());
                    statement.setLong(5, job.windowTo());
                    statement.setObject(6, job.requestedByOid());
                    statement.setLong(7, job.createdAt());
                    statement.setLong(8, job.previewExpiresAt());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(INSERT_ITEM)) {
                    for (FrozenItem item : items) {
                        statement.setObject(1, job.id());
                        statement.setString(2, item.identifier());
                        statement.setString(3, item.owner());
                        statement.setString(4, item.sectionKey());
                        statement.setString(5, item.kind());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not store the rollback preview", e);
        }
    }

    private static Object selectorOid(RollbackSelector selector) {
        return selector instanceof RollbackSelector.Staff staff ? staff.oid() : null;
    }

    private static Object selectorMinecraftUuid(RollbackSelector selector) {
        return selector instanceof RollbackSelector.UnlinkedAccount account ? account.minecraftUuid() : null;
    }
}
