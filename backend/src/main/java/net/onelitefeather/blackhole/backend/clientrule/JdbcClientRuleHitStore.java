package net.onelitefeather.blackhole.backend.clientrule;

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
import java.util.Optional;

/** PostgreSQL implementation of {@link ClientRuleHitStore}: the hit and the counter are one statement. */
@Singleton
public class JdbcClientRuleHitStore implements ClientRuleHitStore {

    private static final String RECORD = """
            WITH r AS (
                UPDATE client_rule SET hit_count = hit_count + 1
                WHERE rule_key = ? AND enabled
                RETURNING rule_key, kind, action, template_key
            ), h AS (
                INSERT INTO client_rule_hit (rule_key, owner_hash, at, action_taken, enforced, bypassed, server_name)
                SELECT r.rule_key, ?, ?, r.action, ?, ?, ? FROM r
                RETURNING rule_key
            )
            SELECT r.rule_key, r.kind, r.action, r.template_key FROM r JOIN h ON h.rule_key = r.rule_key
            """;

    private static final String COLUMNS = "rule_key, owner_hash, at, action_taken, enforced, bypassed, server_name";

    private final DataSource dataSource;

    @Inject
    public JdbcClientRuleHitStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcClientRuleHitStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Optional<RecordedHit> record(String ruleKey, String owner, String server, boolean enforced, boolean bypassed, long at) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(RECORD)) {
            statement.setString(1, ruleKey);
            statement.setString(2, owner);
            statement.setLong(3, at);
            statement.setBoolean(4, enforced);
            statement.setBoolean(5, bypassed);
            statement.setString(6, server);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new RecordedHit(rows.getString(1), ClientRuleKind.valueOf(rows.getString(2)),
                        ClientRuleAction.valueOf(rows.getString(3)), rows.getString(4)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not record the client rule hit", e);
        }
    }

    @Override
    public List<Hit> byRule(String ruleKey, int limit) {
        return query("SELECT " + COLUMNS + " FROM client_rule_hit WHERE rule_key = ? ORDER BY at DESC LIMIT ?", ruleKey, limit);
    }

    @Override
    public List<Hit> byOwner(String owner, int limit) {
        return query("SELECT " + COLUMNS + " FROM client_rule_hit WHERE owner_hash = ? ORDER BY at DESC LIMIT ?", owner, limit);
    }

    @Override
    public int deleteBefore(long cutoff) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM client_rule_hit WHERE at < ?")) {
            statement.setLong(1, cutoff);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not delete the expired client rule hits", e);
        }
    }

    @Override
    public int deleteByOwner(String owner) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM client_rule_hit WHERE owner_hash = ?")) {
            statement.setString(1, owner);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not delete the client rule hits of a player", e);
        }
    }

    private List<Hit> query(String sql, String key, int limit) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<Hit> hits = new ArrayList<>();
                while (rows.next()) {
                    hits.add(new Hit(rows.getString(1), rows.getString(2), rows.getLong(3), ClientRuleAction.valueOf(rows.getString(4)),
                            rows.getBoolean(5), rows.getBoolean(6), rows.getString(7)));
                }
                return hits;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the client rule hits", e);
        }
    }
}
