package net.onelitefeather.blackhole.backend.rollback;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.micronaut.core.annotation.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    private static final String FIND_JOB = """
            SELECT id, state, requested_by_oid, selector_oid, reason, idempotency_key, preview_expires_at
            FROM staff_rollback_job WHERE id = ?
            """;
    private static final String MARK_RUNNING = """
            UPDATE staff_rollback_job SET state = 'RUNNING', reason = ?, idempotency_key = ?, started_at = ?
            WHERE id = ? AND state = 'PREVIEW' AND preview_expires_at > ?
            """;
    // One RUNNING job at a time per runner: the row lock skips a job another runner is claiming right now.
    private static final String CLAIM_JOB = """
            UPDATE staff_rollback_job SET locked_by = ?, locked_until = ?
            WHERE id = (SELECT id FROM staff_rollback_job
                        WHERE state = 'RUNNING' AND (locked_until IS NULL OR locked_until < ? OR locked_by = ?)
                        ORDER BY started_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
            RETURNING id
            """;
    private static final String RELEASE_LEASE = """
            UPDATE staff_rollback_job SET locked_by = NULL, locked_until = NULL WHERE id = ? AND locked_by = ?
            """;
    private static final String NEXT_ITEMS = """
            SELECT punishment_identifier, owner, section_key, state FROM staff_rollback_item
            WHERE job_id = ? AND state = 'PENDING' ORDER BY punishment_identifier LIMIT ?
            """;
    private static final String MARK_ITEM = """
            UPDATE staff_rollback_item SET state = ?, reason_code = ?, processed_at = ? WHERE job_id = ? AND punishment_identifier = ?
            """;
    private static final String COUNT_OPEN = """
            SELECT count(*) FROM staff_rollback_item WHERE job_id = ? AND state = 'PENDING'
            """;
    private static final String COUNT_BY_STATE = """
            SELECT state, count(*) AS total FROM staff_rollback_item WHERE job_id = ? GROUP BY state
            """;
    private static final String MARK_COMPLETED = """
            UPDATE staff_rollback_job SET state = 'COMPLETED', finished_at = ?, locked_by = NULL, locked_until = NULL
            WHERE id = ? AND state = 'RUNNING'
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

    @Override
    public Optional<Job> find(UUID id) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_JOB)) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Job(rows.getObject("id", UUID.class), rows.getString("state"),
                        rows.getObject("requested_by_oid", UUID.class), rows.getObject("selector_oid", UUID.class),
                        rows.getString("reason"), rows.getString("idempotency_key"), rows.getLong("preview_expires_at")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the rollback job", e);
        }
    }

    @Override
    public boolean markRunning(UUID id, String reason, String idempotencyKey, long now) {
        return update(MARK_RUNNING, reason, idempotencyKey, now, id, now) == 1;
    }

    @Override
    public Optional<UUID> claimRunningJob(String holder, long now, long leaseUntil) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(CLAIM_JOB)) {
            statement.setString(1, holder);
            statement.setLong(2, leaseUntil);
            statement.setLong(3, now);
            statement.setString(4, holder);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getObject("id", UUID.class)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not claim a rollback job", e);
        }
    }

    @Override
    public void releaseLease(UUID id, String holder) {
        update(RELEASE_LEASE, id, holder);
    }

    @Override
    public List<Item> nextItems(UUID id, int limit) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(NEXT_ITEMS)) {
            statement.setObject(1, id);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<Item> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new Item(rows.getString("punishment_identifier"), rows.getString("owner"), rows.getString("section_key"),
                            rows.getString("state")));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the next rollback items", e);
        }
    }

    @Override
    public void markItem(UUID id, String identifier, String state, @Nullable String reasonCode, long now) {
        update(MARK_ITEM, state, reasonCode, now, id, identifier);
    }

    @Override
    public long countOpen(UUID id) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(COUNT_OPEN)) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not count the open rollback items", e);
        }
    }

    @Override
    public Map<String, Long> countByState(UUID id) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(COUNT_BY_STATE)) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                Map<String, Long> counts = new HashMap<>();
                while (rows.next()) {
                    counts.put(rows.getString("state"), rows.getLong("total"));
                }
                return counts;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not count the rollback items", e);
        }
    }

    @Override
    public boolean markCompleted(UUID id, long now) {
        return update(MARK_COMPLETED, now, id) == 1;
    }

    /** Runs one single-statement update and returns the row count. */
    private int update(String sql, Object... parameters) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not update the rollback tables", e);
        }
    }

    private static Object selectorOid(RollbackSelector selector) {
        return selector instanceof RollbackSelector.Staff staff ? staff.oid() : null;
    }

    private static Object selectorMinecraftUuid(RollbackSelector selector) {
        return selector instanceof RollbackSelector.UnlinkedAccount account ? account.minecraftUuid() : null;
    }
}
