package net.onelitefeather.blackhole.backend.clientrule;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.section.ServerTargetKind;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link ClientRuleStore}: one data-modifying CTE per write, so the rule
 * and its child rows are written together or not at all. The child tables carry a surrogate key, so
 * an update can delete and re-insert the children in the same statement.
 */
@Singleton
public class JdbcClientRuleStore implements ClientRuleStore {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private static final String CHILDREN = """
            , p AS (
                INSERT INTO client_rule_pattern (rule_key, position, pattern)
                SELECT w.rule_key, t.ord - 1, t.value FROM w, unnest(?::text[]) WITH ORDINALITY AS t(value, ord)
            ), s AS (
                INSERT INTO client_rule_server (rule_key, position, server_name)
                SELECT w.rule_key, t.ord - 1, t.value FROM w, unnest(?::text[]) WITH ORDINALITY AS t(value, ord)
            ), c AS (
                INSERT INTO client_rule_section (rule_key, position, section_key)
                SELECT w.rule_key, t.ord - 1, t.value FROM w, unnest(?::text[]) WITH ORDINALITY AS t(value, ord)
            )
            SELECT rule_key FROM w
            """;

    private static final String INSERT = """
            WITH w AS (
                INSERT INTO client_rule (rule_key, display_name, kind, protocol_min, protocol_max, negate, scope_kind, target_kind,
                                         hide_command, action, deny_message, template_key, acknowledged_spoofable, enabled,
                                         hit_count, created_at, updated_at, recorded_by_oid)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                ON CONFLICT (rule_key) DO NOTHING
                RETURNING rule_key
            )
            """ + CHILDREN;

    private static final String UPDATE = """
            WITH w AS (
                UPDATE client_rule SET display_name = ?, kind = ?, protocol_min = ?, protocol_max = ?, negate = ?, scope_kind = ?,
                                       target_kind = ?, hide_command = ?, action = ?, deny_message = ?, template_key = ?,
                                       acknowledged_spoofable = ?, enabled = ?, updated_at = ?, recorded_by_oid = ?
                WHERE rule_key = ?
                RETURNING rule_key
            ), dp AS (
                DELETE FROM client_rule_pattern WHERE rule_key IN (SELECT rule_key FROM w)
            ), ds AS (
                DELETE FROM client_rule_server WHERE rule_key IN (SELECT rule_key FROM w)
            ), dc AS (
                DELETE FROM client_rule_section WHERE rule_key IN (SELECT rule_key FROM w)
            )
            """ + CHILDREN.replaceFirst("^, ", ", ");

    private static final String SELECT = """
            SELECT r.rule_key, r.display_name, r.kind, r.protocol_min, r.protocol_max, r.negate, r.scope_kind, r.target_kind,
                   r.hide_command, r.action, r.deny_message, r.template_key, r.acknowledged_spoofable, r.enabled,
                   r.hit_count, r.created_at, r.updated_at, r.recorded_by_oid,
                   coalesce((SELECT array_agg(p.pattern ORDER BY p.position) FROM client_rule_pattern p WHERE p.rule_key = r.rule_key), '{}') AS patterns,
                   coalesce((SELECT array_agg(s.server_name ORDER BY s.position) FROM client_rule_server s WHERE s.rule_key = r.rule_key), '{}') AS servers,
                   coalesce((SELECT array_agg(c.section_key ORDER BY c.position) FROM client_rule_section c WHERE c.rule_key = r.rule_key), '{}') AS sections
            FROM client_rule r
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcClientRuleStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcClientRuleStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Write insert(ClientRuleSpec spec, UUID recordedByOid, long now) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            int i = 1;
            statement.setString(i++, spec.key());
            statement.setString(i++, spec.displayName());
            statement.setString(i++, spec.kind().name());
            setInt(statement, i++, spec.protocolMin());
            setInt(statement, i++, spec.protocolMax());
            statement.setBoolean(i++, spec.negate());
            statement.setString(i++, spec.scope().name());
            statement.setString(i++, spec.targetKind().name());
            statement.setBoolean(i++, spec.hideCommand());
            statement.setString(i++, spec.action().name());
            statement.setString(i++, spec.denyMessage());
            statement.setString(i++, spec.templateKey());
            statement.setBoolean(i++, spec.acknowledgedSpoofable());
            statement.setBoolean(i++, spec.enabled());
            statement.setLong(i++, now);
            statement.setLong(i++, now);
            statement.setObject(i++, recordedByOid);
            setChildren(connection, statement, i, spec);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Write.DONE : Write.TAKEN;
            }
        } catch (SQLException e) {
            return failed(e, "insert");
        }
    }

    @Override
    public Write update(ClientRuleSpec spec, UUID recordedByOid, long now) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE)) {
            int i = 1;
            statement.setString(i++, spec.displayName());
            statement.setString(i++, spec.kind().name());
            setInt(statement, i++, spec.protocolMin());
            setInt(statement, i++, spec.protocolMax());
            statement.setBoolean(i++, spec.negate());
            statement.setString(i++, spec.scope().name());
            statement.setString(i++, spec.targetKind().name());
            statement.setBoolean(i++, spec.hideCommand());
            statement.setString(i++, spec.action().name());
            statement.setString(i++, spec.denyMessage());
            statement.setString(i++, spec.templateKey());
            statement.setBoolean(i++, spec.acknowledgedSpoofable());
            statement.setBoolean(i++, spec.enabled());
            statement.setLong(i++, now);
            statement.setObject(i++, recordedByOid);
            statement.setString(i++, spec.key());
            setChildren(connection, statement, i, spec);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Write.DONE : Write.ABSENT;
            }
        } catch (SQLException e) {
            return failed(e, "update");
        }
    }

    @Override
    public Optional<Stored> find(String key) {
        return query(SELECT + " WHERE r.rule_key = ?", key).stream().findFirst();
    }

    @Override
    public List<Stored> list() {
        return query(SELECT + " ORDER BY r.rule_key", null);
    }

    @Override
    public boolean delete(String key) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM client_rule WHERE rule_key = ?")) {
            statement.setString(1, key);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("could not delete the client rule", e);
        }
    }

    @Override
    public boolean templateInUse(String templateKey) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM client_rule WHERE template_key = ? LIMIT 1")) {
            statement.setString(1, templateKey);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not check the client rules for a template", e);
        }
    }

    private static Write failed(SQLException e, String what) {
        if (FOREIGN_KEY_VIOLATION.equals(e.getSQLState())) {
            return Write.MISSING_TEMPLATE;
        }
        if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
            return Write.TAKEN;
        }
        throw new IllegalStateException("could not " + what + " the client rule", e);
    }

    private static void setInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setChildren(Connection connection, PreparedStatement statement, int from, ClientRuleSpec spec) throws SQLException {
        statement.setArray(from, connection.createArrayOf("text", spec.patterns().toArray()));
        statement.setArray(from + 1, connection.createArrayOf("text", spec.servers().toArray()));
        statement.setArray(from + 2, connection.createArrayOf("text", spec.sectionKeys().toArray()));
    }

    private List<Stored> query(String sql, String key) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (key != null) {
                statement.setString(1, key);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<Stored> rules = new ArrayList<>();
                while (rows.next()) {
                    rules.add(read(rows));
                }
                return rules;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the client rules", e);
        }
    }

    private static Stored read(ResultSet rows) throws SQLException {
        int protocolMin = rows.getInt("protocol_min");
        Integer min = rows.wasNull() ? null : protocolMin;
        int protocolMax = rows.getInt("protocol_max");
        Integer max = rows.wasNull() ? null : protocolMax;
        ClientRuleSpec spec = new ClientRuleSpec(
                rows.getString("rule_key"), rows.getString("display_name"), ClientRuleKind.valueOf(rows.getString("kind")),
                rows.getBoolean("enabled"), strings(rows.getArray("patterns")), min, max, rows.getBoolean("negate"),
                ClientRuleScope.valueOf(rows.getString("scope_kind")), strings(rows.getArray("sections")),
                ServerTargetKind.valueOf(rows.getString("target_kind")), strings(rows.getArray("servers")),
                ClientRuleAction.valueOf(rows.getString("action")), rows.getString("deny_message"), rows.getString("template_key"),
                rows.getBoolean("acknowledged_spoofable"), rows.getBoolean("hide_command"));
        return new Stored(spec, rows.getLong("hit_count"), rows.getLong("created_at"), rows.getLong("updated_at"),
                rows.getObject("recorded_by_oid", UUID.class));
    }

    private static List<String> strings(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }
}
