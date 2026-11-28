package net.onelitefeather.blackhole.backend.rollback;

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
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link RollbackSelectionReader}. One statement per call, so none needs a
 * transaction ({@code @Transactional} is unusable in this codebase).
 */
@Singleton
public class JdbcRollbackSelectionReader implements RollbackSelectionReader {

    // A human's rows: a linked in-game write carries actor_oid, a web write only recorded_by_oid.
    private static final String SELECT_BY_STAFF = """
            SELECT p.identifier, p.owner, p.section_key, p.kind, (ap.punishment_identifier IS NOT NULL) AS revocable
            FROM punishments p
            LEFT JOIN active_punishments ap ON ap.punishment_identifier = p.identifier
            WHERE COALESCE(p.actor_oid, p.recorded_by_oid) = ? AND p.created_at >= ? AND p.created_at <= ?
            ORDER BY p.created_at, p.identifier
            """;
    // An unlinked in-game account: the Minecraft UUID as source, and no link at write time.
    private static final String SELECT_BY_UNLINKED = """
            SELECT p.identifier, p.owner, p.section_key, p.kind, (ap.punishment_identifier IS NOT NULL) AS revocable
            FROM punishments p
            LEFT JOIN active_punishments ap ON ap.punishment_identifier = p.identifier
            WHERE p.source = ? AND p.actor_oid IS NULL AND p.created_at >= ? AND p.created_at <= ?
            ORDER BY p.created_at, p.identifier
            """;
    private static final String STAFF_KIND = "SELECT kind FROM staff_actor WHERE oid = ?";

    private final DataSource dataSource;

    @Inject
    public JdbcRollbackSelectionReader(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcRollbackSelectionReader(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public List<Candidate> select(RollbackSelector selector, long from, long to) {
        String sql = switch (selector) {
            case RollbackSelector.Staff staff -> SELECT_BY_STAFF;
            case RollbackSelector.UnlinkedAccount account -> SELECT_BY_UNLINKED;
        };
        UUID key = switch (selector) {
            case RollbackSelector.Staff staff -> staff.oid();
            case RollbackSelector.UnlinkedAccount account -> account.minecraftUuid();
        };
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, key);
            statement.setLong(2, from);
            statement.setLong(3, to);
            try (ResultSet rows = statement.executeQuery()) {
                List<Candidate> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new Candidate(rows.getString("identifier"), rows.getString("owner"), rows.getString("section_key"),
                            rows.getString("kind"), rows.getBoolean("revocable")));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the rollback selection", e);
        }
    }

    @Override
    public boolean isHumanStaffActor(UUID oid) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(STAFF_KIND)) {
            statement.setObject(1, oid);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && "HUMAN".equals(rows.getString("kind"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the staff actor kind", e);
        }
    }
}
