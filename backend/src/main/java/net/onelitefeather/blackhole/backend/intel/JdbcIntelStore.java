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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** PostgreSQL implementation of {@link ListGenerationStore}: one statement per operation, no transaction. */
@Singleton
public class JdbcIntelStore implements ListGenerationStore {

    private static final int BATCH_SIZE = 1000;
    private static final int ERROR_MAX = 255;

    private static final String CLAIM = """
            INSERT INTO vpn_list_source (source_key, last_attempt_at, entry_count, invalid_count) VALUES (?, ?, 0, 0)
            ON CONFLICT (source_key) DO UPDATE SET last_attempt_at = EXCLUDED.last_attempt_at
            WHERE vpn_list_source.last_attempt_at IS NULL OR vpn_list_source.last_attempt_at <= ?
            """;
    private static final String OPEN_GENERATION = """
            INSERT INTO vpn_list_generation (source_key, generation, created_at)
            SELECT ?, COALESCE(MAX(generation), 0) + 1, ? FROM vpn_list_generation WHERE source_key = ?
            RETURNING generation
            """;
    private static final String WRITE_ENTRY = """
            INSERT INTO vpn_list_entry (source_key, generation, cidr) VALUES (?, ?, ?) ON CONFLICT DO NOTHING
            """;
    private static final String ACTIVATE = """
            INSERT INTO vpn_list_state (source_key, active_generation, activated_at) VALUES (?, ?, ?)
            ON CONFLICT (source_key) DO UPDATE SET active_generation = EXCLUDED.active_generation,
                activated_at = EXCLUDED.activated_at
            """;
    private static final String DELETE_OTHER_ENTRIES = "DELETE FROM vpn_list_entry WHERE source_key = ? AND generation <> ?";
    private static final String DELETE_OTHER_GENERATIONS = "DELETE FROM vpn_list_generation WHERE source_key = ? AND generation <> ?";
    private static final String DELETE_ENTRIES_OF = "DELETE FROM vpn_list_entry WHERE source_key = ? AND generation = ?";
    private static final String DELETE_GENERATION_OF = "DELETE FROM vpn_list_generation WHERE source_key = ? AND generation = ?";
    private static final String RECORD_SUCCESS = """
            UPDATE vpn_list_source SET last_success_at = ?, last_error = NULL, entry_count = ?, invalid_count = ?
            WHERE source_key = ?
            """;
    private static final String RECORD_FAILURE = "UPDATE vpn_list_source SET last_error = ? WHERE source_key = ?";
    private static final String ENTRY_COUNT = "SELECT entry_count FROM vpn_list_source WHERE source_key = ?";
    private static final String ACTIVE_GENERATIONS = "SELECT source_key, active_generation FROM vpn_list_state";
    private static final String ACTIVE_ENTRIES = """
            SELECT e.source_key, e.cidr FROM vpn_list_state s
            JOIN vpn_list_entry e ON e.source_key = s.source_key AND e.generation = s.active_generation
            """;
    private static final String ORPHAN_ENTRIES = """
            DELETE FROM vpn_list_entry e USING vpn_list_generation g
            WHERE e.source_key = g.source_key AND e.generation = g.generation AND g.created_at < ?
              AND NOT EXISTS (SELECT 1 FROM vpn_list_state s
                              WHERE s.source_key = g.source_key AND s.active_generation = g.generation)
            """;
    private static final String ORPHAN_GENERATIONS = """
            DELETE FROM vpn_list_generation g WHERE g.created_at < ?
              AND NOT EXISTS (SELECT 1 FROM vpn_list_state s
                              WHERE s.source_key = g.source_key AND s.active_generation = g.generation)
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcIntelStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcIntelStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public boolean claimRefresh(String sourceKey, long now, long debounceMillis) {
        return update(CLAIM, sourceKey, now, now - debounceMillis) == 1;
    }

    @Override
    public long openGeneration(String sourceKey, long now) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(OPEN_GENERATION)) {
            statement.setString(1, sourceKey);
            statement.setLong(2, now);
            statement.setString(3, sourceKey);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not open a list generation", e);
        }
    }

    @Override
    public void writeEntries(String sourceKey, long generation, Collection<String> cidrs) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(WRITE_ENTRY)) {
            int pending = 0;
            for (String cidr : cidrs) {
                statement.setString(1, sourceKey);
                statement.setLong(2, generation);
                statement.setString(3, cidr);
                statement.addBatch();
                if (++pending == BATCH_SIZE) {
                    statement.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                statement.executeBatch();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not write the list entries", e);
        }
    }

    @Override
    public void activate(String sourceKey, long generation, long now) {
        update(ACTIVATE, sourceKey, generation, now);
    }

    @Override
    public void deleteOtherGenerations(String sourceKey, long keep) {
        update(DELETE_OTHER_ENTRIES, sourceKey, keep);
        update(DELETE_OTHER_GENERATIONS, sourceKey, keep);
    }

    @Override
    public void discardGeneration(String sourceKey, long generation) {
        update(DELETE_ENTRIES_OF, sourceKey, generation);
        update(DELETE_GENERATION_OF, sourceKey, generation);
    }

    @Override
    public void recordSuccess(String sourceKey, long now, long entries, long invalid) {
        update(RECORD_SUCCESS, now, entries, invalid, sourceKey);
    }

    @Override
    public void recordFailure(String sourceKey, long now, String error) {
        String message = error == null ? "refresh failed" : error;
        update(RECORD_FAILURE, message.length() > ERROR_MAX ? message.substring(0, ERROR_MAX) : message, sourceKey);
    }

    @Override
    public long activeEntryCount(String sourceKey) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(ENTRY_COUNT)) {
            statement.setString(1, sourceKey);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the list entry count", e);
        }
    }

    @Override
    public Map<String, Long> activeGenerations() {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(ACTIVE_GENERATIONS);
             ResultSet rows = statement.executeQuery()) {
            Map<String, Long> active = new HashMap<>();
            while (rows.next()) {
                active.put(rows.getString(1), rows.getLong(2));
            }
            return active;
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the active list generations", e);
        }
    }

    @Override
    public List<ListedCidr> loadActiveEntries() {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(ACTIVE_ENTRIES);
             ResultSet rows = statement.executeQuery()) {
            List<ListedCidr> entries = new ArrayList<>();
            while (rows.next()) {
                entries.add(new ListedCidr(rows.getString(1), rows.getString(2)));
            }
            return entries;
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the list entries", e);
        }
    }

    @Override
    public int deleteOrphanGenerationsCreatedBefore(long cutoff) {
        update(ORPHAN_ENTRIES, cutoff);
        return update(ORPHAN_GENERATIONS, cutoff);
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
            throw new IllegalStateException("could not update the list store", e);
        }
    }
}
