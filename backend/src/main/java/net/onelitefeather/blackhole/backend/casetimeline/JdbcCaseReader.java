package net.onelitefeather.blackhole.backend.casetimeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.punishment.PunishmentShortId;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL implementation of {@link CaseReader}: plain read statements, nothing is written. */
@Singleton
public class JdbcCaseReader implements CaseReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    /** The punishment state from its row: lifted (revoked or lifted by an appeal), expired, or active. The parameter is the time. */
    private static final String EXPIRED = """
            COALESCE(NOT p.permanent AND jsonb_typeof(p.meta_data -> 'expiration_date') = 'number'
                     AND (p.meta_data ->> 'expiration_date')::numeric <= ?, FALSE)""";
    private static final String STATE = "CASE WHEN p.lifted THEN 'LIFTED' WHEN " + EXPIRED + " THEN 'EXPIRED' ELSE 'ACTIVE' END";

    private static final String FIND = """
            SELECT c.status, c.assignee_oid, a.display_name, c.priority, c.tags::text, c.version, c.created_at, c.updated_at
            FROM punishment_case c LEFT JOIN staff_actor a ON a.oid = c.assignee_oid
            WHERE c.punishment_identifier = ?
            """;
    private static final String RELATED = "SELECT p.identifier, p.short_id, p.section_key, " + STATE + ", COALESCE(c.status, 'OPEN'), p.created_at "
            + "FROM punishments p LEFT JOIN punishment_case c ON c.punishment_identifier = p.identifier "
            + "WHERE p.owner = ? AND p.identifier <> ? ORDER BY p.created_at DESC, p.identifier LIMIT ?";
    private static final String EVENT_COLUMNS = """
            e.identifier, e.seq, e.punishment_identifier, e.type, e.payload_version, e.occurred_at, e.origin, e.actor_trust,
            e.actor_oid, e.recorded_by_oid, e.actor_source, e.subject_id, e.visibility, e.payload::text, e.redacted_at, e.dedupe_key""";
    private static final String SUBJECT = "SELECT " + EVENT_COLUMNS + " FROM case_event e WHERE e.punishment_identifier = ? "
            + "AND e.subject_id = ? ORDER BY e.occurred_at, e.seq";

    private final DataSource dataSource;

    @Inject
    public JdbcCaseReader(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcCaseReader(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Optional<CaseRow> find(String punishmentIdentifier) {
        List<CaseRow> rows = query(FIND, List.of(punishmentIdentifier), row -> new CaseRow(
                new CaseMeta(row.getString(1), row.getObject(2, UUID.class), row.getString(4), tags(row.getString(5))),
                row.getString(3), row.getLong(6), row.getLong(7), row.getLong(8)));
        return rows.stream().findFirst();
    }

    @Override
    public List<Related> related(String owner, String excludedIdentifier, long now, int limit) {
        return query(RELATED, List.of(now, owner, excludedIdentifier, limit), row -> new Related(
                row.getString(1), PunishmentShortId.display(row.getString(2)), row.getString(3), row.getString(4), row.getString(5), row.getLong(6)));
    }

    @Override
    public List<Entry> timeline(TimelineQuery query) {
        StringBuilder sql = new StringBuilder("SELECT ").append(EVENT_COLUMNS)
                .append(", a.display_name FROM case_event e LEFT JOIN staff_actor a ON a.oid = e.actor_oid WHERE e.punishment_identifier = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(query.punishmentIdentifier());
        if (query.afterOccurredAt() != null && query.afterSeq() != null) {
            sql.append(query.ascending() ? " AND (e.occurred_at, e.seq) > (?, ?)" : " AND (e.occurred_at, e.seq) < (?, ?)");
            parameters.add(query.afterOccurredAt());
            parameters.add(query.afterSeq());
        }
        if (!query.types().isEmpty()) {
            sql.append(" AND (").append(String.join(" OR ", query.types().stream().map(type -> "e.type = ? OR starts_with(e.type, ?)").toList())).append(")");
            for (String type : query.types()) {
                parameters.add(type);
                parameters.add(type + ".");
            }
        }
        if (query.trust() != null) {
            sql.append(" AND e.actor_trust = ?");
            parameters.add(query.trust().name());
        }
        if (query.from() != null) {
            sql.append(" AND e.occurred_at >= ?");
            parameters.add(query.from());
        }
        if (query.to() != null) {
            sql.append(" AND e.occurred_at <= ?");
            parameters.add(query.to());
        }
        sql.append(query.ascending() ? " ORDER BY e.occurred_at, e.seq" : " ORDER BY e.occurred_at DESC, e.seq DESC").append(" LIMIT ?");
        parameters.add(query.limit());
        return query(sql.toString(), parameters, row -> new Entry(JdbcCaseEventReader.map(row), row.getString(17)));
    }

    @Override
    public List<CaseEventRecord> subject(String punishmentIdentifier, String subjectId) {
        return query(SUBJECT, List.of(punishmentIdentifier, subjectId), JdbcCaseEventReader::map);
    }

    @Override
    public ListPage list(ListQuery query, long now) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(now);
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        filter(where, parameters, "c.status = ?", query.status());
        filter(where, parameters, "c.assignee_oid = ?", query.assignee());
        filter(where, parameters, "c.priority = ?", query.priority());
        filter(where, parameters, "p.section_key = ?", query.section());
        if (query.tag() != null) {
            where.append(" AND c.tags @> ?::jsonb");
            parameters.add("[\"" + query.tag() + "\"]");
        }
        if (query.state() != null) {
            switch (query.state()) {
                case "LIFTED" -> where.append(" AND p.lifted");
                case "EXPIRED" -> {
                    where.append(" AND NOT p.lifted AND ").append(EXPIRED);
                    parameters.add(now);
                }
                default -> {
                    where.append(" AND NOT p.lifted AND NOT ").append(EXPIRED);
                    parameters.add(now);
                }
            }
        }
        parameters.add(query.size());
        parameters.add((long) query.page() * query.size());
        String sql = "SELECT c.punishment_identifier, p.short_id, p.section_key, " + STATE + ", c.status, c.assignee_oid, a.display_name, "
                + "c.priority, c.tags::text, p.created_at, "
                + "COALESCE((SELECT max(e.occurred_at) FROM case_event e WHERE e.punishment_identifier = c.punishment_identifier), c.created_at), "
                + "count(*) OVER () FROM punishment_case c JOIN punishments p ON p.identifier = c.punishment_identifier "
                + "LEFT JOIN staff_actor a ON a.oid = c.assignee_oid" + where
                + " ORDER BY 11 DESC, c.punishment_identifier LIMIT ? OFFSET ?";
        long[] total = {0};
        List<Listed> items = query(sql, parameters, row -> {
            total[0] = row.getLong(12);
            return new Listed(row.getString(1), PunishmentShortId.display(row.getString(2)), row.getString(3), row.getString(4),
                    new CaseMeta(row.getString(5), row.getObject(6, UUID.class), row.getString(8), tags(row.getString(9))),
                    row.getString(7), row.getLong(10), row.getLong(11));
        });
        return new ListPage(items, total[0]);
    }

    private static void filter(StringBuilder where, List<Object> parameters, String condition, Object value) {
        if (value != null) {
            where.append(" AND ").append(condition);
            parameters.add(value);
        }
    }

    private static List<String> tags(String json) {
        try {
            return json == null ? List.of() : MAPPER.readValue(json, STRINGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not read the tags of a case", e);
        }
    }

    private interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    private <T> List<T> query(String sql, List<Object> parameters, RowMapper<T> mapper) {
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
            throw new IllegalStateException("could not read the case data", e);
        }
    }
}
