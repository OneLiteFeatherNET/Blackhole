package net.onelitefeather.blackhole.backend.integration;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retention sweep must not scan the whole detection table. A seeded table is analysed and the sweep's DELETE is
 * explained; the plan has to use the partial index on unattached rows. The table is built in a fresh schema, so no other
 * test sees these rows.
 */
@Tag("integration")
class IntegrationDetectionSweepPlanIntegrationTest extends PostgresIntegrationTest {

    private static final String SWEEP_INDEX = "integration_detection_sweep_idx";
    private static final int ROWS = 100_000;

    @Test
    void theSweepDeleteUsesThePartialIndexOnUnattachedRows() throws SQLException {
        String schema = "integration_sweep_" + Long.toHexString(System.nanoTime());
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            statement.execute("INSERT INTO integration (source_system, entra_client_id, display_name, enabled, allowed_kinds, "
                    + "allowed_tasks, allowed_static_servers, created_at, updated_at, created_by_oid, updated_by_oid) VALUES ("
                    + "'vulcan', '" + UUID.randomUUID() + "', 'Vulcan', true, ARRAY['DETECTION:1'], ARRAY['Lobby'], '{}', 1, 1, "
                    + "'" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "')");
            statement.execute("INSERT INTO integration_detection (integration_id, detection_id, kind, schema_version, owner, server, "
                    + "occurred_at, received_at, summary, payload, attached_evidence_id) "
                    + "SELECT 1, gen_random_uuid(), 'DETECTION', 1, repeat('a', 128), 'Lobby-1', i, i, 'seeded', '{}'::jsonb, "
                    + "CASE WHEN i % 10 = 0 THEN gen_random_uuid() END "
                    + "FROM generate_series(1, " + ROWS + ") AS i");
            statement.execute("ANALYZE integration_detection");

            String plan = explain(statement, "DELETE FROM integration_detection WHERE attached_evidence_id IS NULL AND occurred_at < 1000");

            assertTrue(plan.contains(SWEEP_INDEX), "the sweep must use " + SWEEP_INDEX + ", plan was:\n" + plan);
            assertFalse(plan.contains("Seq Scan on integration_detection"), "the sweep must not scan the table, plan was:\n" + plan);
        }
    }

    private static String explain(Statement statement, String query) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (ResultSet rows = statement.executeQuery("EXPLAIN " + query)) {
            while (rows.next()) {
                plan.append(rows.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }
}
