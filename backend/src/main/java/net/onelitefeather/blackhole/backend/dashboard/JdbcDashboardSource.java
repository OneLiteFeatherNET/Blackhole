package net.onelitefeather.blackhole.backend.dashboard;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.report.ReportStatus;
import net.onelitefeather.phoca.metadata.Expirable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL implementation of {@link DashboardSource}. Plain read-only aggregates, each on its own
 * short connection; enum columns are stored as ordinals, so the status sets are bound from the enums.
 */
@Singleton
public class JdbcDashboardSource implements DashboardSource {

    private static final String ACTIVE_BY_SECTION = """
            SELECT a.section_key, count(*)
            FROM active_punishments a JOIN punishments p ON p.identifier = a.punishment_identifier
            WHERE p.meta_data ->> ? IS NULL OR (p.meta_data ->> ?)::bigint >= ?
            GROUP BY a.section_key
            """;
    private static final String OPEN_REPORTS = "SELECT count(*) FROM reports WHERE status IN (?, ?)";
    private static final String PENDING_APPEALS = "SELECT count(*) FROM appeals WHERE status IN (?, ?, ?)";
    private static final String ESCALATIONS_SINCE = """
            SELECT count(*) FROM punishments
            WHERE permanent AND template_identifier IS NULL AND created_at >= ?
            """;
    private static final String LATEST_ESCALATIONS = """
            SELECT identifier, owner, section_key, created_at, lifted FROM punishments
            WHERE permanent AND template_identifier IS NULL
            ORDER BY created_at DESC LIMIT ?
            """;

    private final DataSource dataSource;

    public JdbcDashboardSource(DataSource dataSource, DataSourceResolver resolver) {
        // The injected source is transaction-aware and refuses connections outside a transaction.
        this.dataSource = resolver.resolve(dataSource);
    }

    @Override
    public Map<String, Long> activePunishmentsBySection(long now) {
        Map<String, Long> counts = new LinkedHashMap<>();
        String expiryKey = Expirable.META_DATA_KEY_EXPIRATION_DATE;
        query(ACTIVE_BY_SECTION, statement -> {
            statement.setString(1, expiryKey);
            statement.setString(2, expiryKey);
            statement.setLong(3, now);
        }, result -> counts.put(result.getString(1), result.getLong(2)));
        return counts;
    }

    @Override
    public long openReports() {
        return count(OPEN_REPORTS, ReportStatus.OPEN.ordinal(), ReportStatus.UNDER_REVIEW.ordinal());
    }

    @Override
    public long pendingAppeals() {
        return count(PENDING_APPEALS, AppealStatus.SUBMITTED.ordinal(),
                AppealStatus.ELIGIBLE_PENDING_REVIEW.ordinal(), AppealStatus.IN_REVIEW.ordinal());
    }

    @Override
    public long escalationsSince(long since) {
        return count(ESCALATIONS_SINCE, since);
    }

    @Override
    public List<DashboardDTO.Escalation> latestEscalations(int limit) {
        List<DashboardDTO.Escalation> escalations = new ArrayList<>();
        query(LATEST_ESCALATIONS, statement -> statement.setInt(1, limit), result -> escalations.add(
                new DashboardDTO.Escalation(
                        result.getString(1), result.getString(2), result.getString(3),
                        result.getLong(4), result.getBoolean(5)
                )));
        return escalations;
    }

    private long count(String sql, long... parameters) {
        long[] total = {0};
        query(sql, statement -> {
            for (int i = 0; i < parameters.length; i++) {
                statement.setLong(i + 1, parameters[i]);
            }
        }, result -> total[0] = result.getLong(1));
        return total[0];
    }

    private void query(String sql, Binder binder, RowReader reader) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    reader.read(result);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the dashboard data", e);
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface RowReader {
        void read(ResultSet result) throws SQLException;
    }
}
