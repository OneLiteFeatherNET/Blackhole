package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.section.ServerTargetKind;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of {@link ActivePunishmentReader}: one read statement over the active
 * slots, their punishment, section, template, case and the verified actor's display name. The end of
 * a punishment is {@code meta_data.expiration_date}; a permanent punishment has none. Plain read
 * statements, nothing is written.
 */
@Singleton
public class JdbcActivePunishmentReader implements ActivePunishmentReader {

    private static final String SELECT = """
            SELECT q.*, count(*) OVER () FROM (
              SELECT a.owner AS owner, p.identifier AS identifier, p.short_id AS short_id, a.section_key AS section_key,
                     s.display_name AS section_name, s.enforcement AS enforcement, s.target_kind AS target_kind,
                     t.template_key AS template_key, COALESCE(p.meta_data ->> 'reason', t.reason) AS reason,
                     COALESCE(p.meta_data ->> 'automatic' = 'true', FALSE) AS automatic, p.created_at AS created_at,
                     CASE WHEN NOT p.permanent AND jsonb_typeof(p.meta_data -> 'expiration_date') = 'number'
                          THEN (p.meta_data ->> 'expiration_date')::numeric::bigint END AS expires_at,
                     p.permanent AS permanent, c.status AS case_status,
                     COALESCE(p.actor_oid, p.recorded_by_oid) AS staff_oid, st.display_name AS staff_name
              FROM active_punishments a
              JOIN punishments p ON p.identifier = a.punishment_identifier
              LEFT JOIN punishment_sections s ON s.section_key = a.section_key
              LEFT JOIN punishment_templates t ON t.identifier = p.template_identifier
              LEFT JOIN punishment_case c ON c.punishment_identifier = p.identifier
              LEFT JOIN staff_actor st ON st.oid = COALESCE(p.actor_oid, p.recorded_by_oid)
              WHERE NOT p.lifted AND p.kind = 'PUNISHMENT'
            ) q WHERE (q.expires_at IS NULL OR q.expires_at > ?)""";

    private final DataSource dataSource;

    @Inject
    public JdbcActivePunishmentReader(DataSource dataSource, DataSourceResolver resolver) {
        // The injected source is transaction-aware and refuses connections outside a transaction.
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcActivePunishmentReader(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Page list(Query query, long now) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(now);
        StringBuilder sql = new StringBuilder(SELECT);
        filter(sql, parameters, "q.enforcement = ?", query.kind() == null ? null : query.kind().name());
        filter(sql, parameters, "q.section_key = ?", query.section());
        filter(sql, parameters, "q.target_kind = ?", query.targetKind() == null ? null : query.targetKind().name());
        if (query.permanent() != null) {
            sql.append(query.permanent() ? " AND q.permanent" : " AND NOT q.permanent");
        }
        if (query.expiresBefore() != null) {
            sql.append(" AND q.expires_at < ?");
            parameters.add(query.expiresBefore());
        }
        if (query.automatic() != null) {
            sql.append(query.automatic() ? " AND q.automatic" : " AND NOT q.automatic");
        }
        filter(sql, parameters, "q.staff_oid = ?", query.actor());
        boolean descending = query.direction() == SortDirection.DESC;
        // A permanent punishment has no end: it sorts as the latest, so last when ascending and first when descending.
        String column = query.sort() == ActivePunishmentSort.EXPIRES ? "q.expires_at" : "q.created_at";
        sql.append(" ORDER BY ").append(column).append(descending ? " DESC NULLS FIRST" : " ASC NULLS LAST")
                .append(", q.identifier LIMIT ? OFFSET ?");
        parameters.add(query.size());
        parameters.add((long) query.page() * query.size());

        long[] total = {0};
        List<Row> rows = read(sql.toString(), parameters, result -> {
            total[0] = result.getLong(17);
            return new Row(
                    result.getString(1), result.getString(2), PunishmentShortId.display(result.getString(3)), result.getString(4),
                    result.getString(5), result.getString(6) == null ? null : EnforcementKind.valueOf(result.getString(6)),
                    result.getString(7) == null ? null : ServerTargetKind.valueOf(result.getString(7)), result.getString(8),
                    result.getString(9), result.getBoolean(10), result.getLong(11), result.getObject(12, Long.class),
                    result.getBoolean(13), result.getString(14), result.getString(16));
        });
        return new Page(rows, total[0]);
    }

    private static void filter(StringBuilder sql, List<Object> parameters, String condition, Object value) {
        if (value != null) {
            sql.append(" AND ").append(condition);
            parameters.add(value);
        }
    }

    private interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    private <T> List<T> read(String sql, List<Object> parameters, RowMapper<T> mapper) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i));
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(mapper.map(rows));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the active punishments", e);
        }
    }
}
