package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the VPN detection migration on a fresh schema and checks the list store against real PostgreSQL. */
@Tag("integration")
class JdbcIntelStoreIntegrationTest extends PostgresIntegrationTest {

    private static final String KEY = "x4bnet-vpn";

    private JdbcIntelStore store;
    private PGSimpleDataSource dataSource;

    @BeforeEach
    void freshSchema() {
        String schema = "vpn_" + Long.toHexString(System.nanoTime());
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        this.dataSource = new PGSimpleDataSource();
        this.dataSource.setUrl(POSTGRES.getJdbcUrl());
        this.dataSource.setUser(POSTGRES.getUsername());
        this.dataSource.setPassword(POSTGRES.getPassword());
        this.dataSource.setCurrentSchema(schema);
        this.store = new JdbcIntelStore(this.dataSource);
    }

    private long countEntries() throws SQLException {
        try (Connection connection = this.dataSource.getConnection();
             ResultSet rows = connection.createStatement().executeQuery("SELECT count(*) FROM vpn_list_entry")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void aSecondClaimInsideTheDebounceWindowIsRefused() {
        assertTrue(this.store.claimRefresh(KEY, 1_000_000L, 600_000L), "the first claim succeeds");

        assertFalse(this.store.claimRefresh(KEY, 1_060_000L, 600_000L), "a claim one minute later is inside the window");
        assertTrue(this.store.claimRefresh(KEY, 1_700_000L, 600_000L), "a claim after the window succeeds again");
    }

    @Test
    void activatingANewGenerationReplacesTheOldOneForReaders() {
        long first = this.store.openGeneration(KEY, 1_000L);
        this.store.writeEntries(KEY, first, List.of("198.51.100.0/24"));
        this.store.activate(KEY, first, 1_000L);

        long second = this.store.openGeneration(KEY, 2_000L);
        this.store.writeEntries(KEY, second, List.of("203.0.113.0/24"));
        this.store.activate(KEY, second, 2_000L);
        this.store.deleteOtherGenerations(KEY, second);

        assertEquals(Map.of(KEY, second), this.store.activeGenerations(), "the second generation is active");
        assertEquals(List.of(new ListGenerationStore.ListedCidr(KEY, "203.0.113.0/24")), this.store.loadActiveEntries(),
                "readers see only the new entries");
    }

    @Test
    void anOrphanedGenerationIsRemovedWithItsEntries() throws SQLException {
        long orphan = this.store.openGeneration(KEY, 1_000L);
        this.store.writeEntries(KEY, orphan, List.of("198.51.100.0/24", "198.51.101.0/24"));
        assertEquals(2, countEntries(), "the two entries are written");

        int removed = this.store.deleteOrphanGenerationsCreatedBefore(5_000L);

        assertEquals(1, removed, "the orphaned generation is removed");
        assertEquals(0, countEntries(), "its entries are removed with it");
    }
}
