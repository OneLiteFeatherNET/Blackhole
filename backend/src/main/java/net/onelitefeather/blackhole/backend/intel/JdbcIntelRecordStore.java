package net.onelitefeather.blackhole.backend.intel;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** PostgreSQL implementation of {@link IntelRecordStore}: one statement per operation, no transaction. */
@Singleton
public class JdbcIntelRecordStore implements IntelRecordStore {

    private static final String LOAD_POLICIES = "SELECT classification, policy FROM vpn_policy";
    private static final String SAVE_POLICY = """
            INSERT INTO vpn_policy (classification, policy, updated_at) VALUES (?, ?, ?)
            ON CONFLICT (classification) DO UPDATE SET policy = EXCLUDED.policy, updated_at = EXCLUDED.updated_at
            """;
    private static final String LOAD_ALLOW_LIST = "SELECT owner_hash, reason, actor_oid, expires_at, created_at FROM vpn_allow_list";
    private static final String SAVE_ALLOW_ENTRY = """
            INSERT INTO vpn_allow_list (owner_hash, reason, actor_oid, expires_at, created_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (owner_hash) DO UPDATE SET reason = EXCLUDED.reason, actor_oid = EXCLUDED.actor_oid,
                expires_at = EXCLUDED.expires_at, created_at = EXCLUDED.created_at
            """;
    private static final String INSERT_HIT = """
            INSERT INTO vpn_detection_hit (id, owner_hash, ip, classification, source_key, asn, organisation, verdict,
                allow_listed, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String HITS_OF = """
            SELECT id, owner_hash, ip, classification, source_key, asn, organisation, verdict, allow_listed, created_at
            FROM vpn_detection_hit WHERE owner_hash = ? ORDER BY created_at DESC LIMIT ?
            """;
    private static final String DELETE_HITS_BEFORE = "DELETE FROM vpn_detection_hit WHERE created_at < ?";
    private static final String SOURCE_STATUSES = """
            SELECT source_key, last_attempt_at, last_success_at, last_error, entry_count, invalid_count FROM vpn_list_source
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcIntelRecordStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcIntelRecordStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public Map<IntelClass, IntelPolicy> loadPolicies() {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LOAD_POLICIES);
             ResultSet rows = statement.executeQuery()) {
            Map<IntelClass, IntelPolicy> policies = new EnumMap<>(IntelClass.class);
            while (rows.next()) {
                policies.put(IntelClass.valueOf(rows.getString(1)), IntelPolicy.valueOf(rows.getString(2)));
            }
            return policies;
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the intel policies", e);
        }
    }

    @Override
    public void savePolicy(IntelClass classification, IntelPolicy policy, long now) {
        update(SAVE_POLICY, classification.name(), policy.name(), now);
    }

    @Override
    public List<IntelAllowEntry> loadAllowList() {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LOAD_ALLOW_LIST);
             ResultSet rows = statement.executeQuery()) {
            List<IntelAllowEntry> entries = new ArrayList<>();
            while (rows.next()) {
                String owner = rows.getString(1);
                String reason = rows.getString(2);
                UUID actor = UUID.fromString(rows.getString(3));
                long expires = rows.getLong(4);
                Long expiresAt = rows.wasNull() ? null : expires;
                entries.add(new IntelAllowEntry(owner, reason, actor, expiresAt, rows.getLong(5)));
            }
            return entries;
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the intel allow-list", e);
        }
    }

    @Override
    public void saveAllowEntry(IntelAllowEntry entry) {
        update(SAVE_ALLOW_ENTRY, entry.ownerHash(), entry.reason(), entry.actorOid().toString(), entry.expiresAt(),
                entry.createdAt());
    }

    @Override
    public void insertHit(IntelHit hit) {
        update(INSERT_HIT, hit.id(), hit.ownerHash(), hit.ip(), hit.classification(), hit.sourceKey(), hit.asn(),
                hit.organisation(), hit.verdict().name(), hit.allowListed(), hit.createdAt());
    }

    @Override
    public List<IntelHit> hitsOf(String ownerHash, int limit) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(HITS_OF)) {
            statement.setString(1, ownerHash);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<IntelHit> hits = new ArrayList<>();
                while (rows.next()) {
                    UUID id = UUID.fromString(rows.getString(1));
                    String owner = rows.getString(2);
                    String ip = rows.getString(3);
                    String classification = rows.getString(4);
                    String sourceKey = rows.getString(5);
                    long asn = rows.getLong(6);
                    Long asnOrNull = rows.wasNull() ? null : asn;
                    String organisation = rows.getString(7);
                    IntelPolicy verdict = IntelPolicy.valueOf(rows.getString(8));
                    boolean allowListed = rows.getBoolean(9);
                    hits.add(new IntelHit(id, owner, ip, classification, sourceKey, asnOrNull, organisation, verdict,
                            allowListed, rows.getLong(10)));
                }
                return hits;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the intel hits", e);
        }
    }

    @Override
    public int deleteHitsCreatedBefore(long cutoff) {
        return update(DELETE_HITS_BEFORE, cutoff);
    }

    @Override
    public List<IntelSourceStatus> sourceStatuses() {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SOURCE_STATUSES);
             ResultSet rows = statement.executeQuery()) {
            List<IntelSourceStatus> statuses = new ArrayList<>();
            while (rows.next()) {
                String key = rows.getString(1);
                long attempt = rows.getLong(2);
                Long attemptAt = rows.wasNull() ? null : attempt;
                long success = rows.getLong(3);
                Long successAt = rows.wasNull() ? null : success;
                String error = rows.getString(4);
                statuses.add(new IntelSourceStatus(key, attemptAt, successAt, error, rows.getLong(5), rows.getLong(6)));
            }
            return statuses;
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the list source status", e);
        }
    }

    /** Runs one write statement and returns its update count. */
    private int update(String sql, Object... values) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not update the intel records", e);
        }
    }
}
