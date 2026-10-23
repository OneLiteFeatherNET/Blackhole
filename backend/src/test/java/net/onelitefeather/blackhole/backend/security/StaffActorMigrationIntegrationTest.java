package net.onelitefeather.blackhole.backend.security;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs V9 over a database that already holds V1-V8 rows: the legacy actor values must survive and
 * the new {@code recorded_by_oid} columns must be NULL (unverified, not backfilled). That Hibernate
 * {@code validate} accepts the new schema is proven by every other integration test, which boots the
 * application against the fully migrated database.
 */
@Tag("integration")
class StaffActorMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final String SCHEMA = "staff_actor_migration";
    private static final UUID LEGACY_ACTOR = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static void execute(Statement statement, String sql) throws SQLException {
        statement.execute(sql.replace("{actor}", LEGACY_ACTOR.toString()));
    }

    @Test
    void legacyRowsKeepTheirActorAndGetNoRecordedByOid() throws SQLException {
        flyway("8").migrate();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            execute(statement, "SET search_path TO " + SCHEMA);
            execute(statement, "INSERT INTO punishments (identifier, source, short_id) VALUES ('legacy-1', '{actor}', 'AAAAAAAA')");
            execute(statement, "INSERT INTO reports (identifier, created_at, updated_at, resolved_by) "
                    + "VALUES ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 1, 1, '{actor}')");
            execute(statement, "INSERT INTO appeals (identifier, created_at, updated_at, decided_by, punishment_identifier) "
                    + "VALUES ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 1, 1, '{actor}', 'legacy-1')");
            execute(statement, "INSERT INTO evidence_items (identifier, owner, kind, source_system, created_at, created_by) "
                    + "VALUES ('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'o', 1, 'it', 1, '{actor}')");
            execute(statement, "INSERT INTO evidence_audit (identifier, evidence_identifier, owner, action, actor, occurred_at) "
                    + "VALUES ('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'o', 0, '{actor}', 1)");

            flyway("9").migrate();

            assertLegacy(statement, "punishments", "source");
            assertLegacy(statement, "reports", "resolved_by");
            assertLegacy(statement, "appeals", "decided_by");
            assertLegacy(statement, "evidence_items", "created_by");
            assertLegacy(statement, "evidence_audit", "actor");
            try (ResultSet staffActors = statement.executeQuery("SELECT count(*) FROM staff_actor")) {
                assertTrue(staffActors.next());
                assertEquals(0, staffActors.getInt(1), "the table exists and starts empty: legacy actors are not backfilled");
            }
        }
    }

    private static void assertLegacy(Statement statement, String table, String actorColumn) throws SQLException {
        try (ResultSet row = statement.executeQuery("SELECT " + actorColumn + ", recorded_by_oid FROM " + table)) {
            assertTrue(row.next(), table + " keeps its row");
            assertEquals(LEGACY_ACTOR, row.getObject(1, UUID.class), table + "." + actorColumn + " keeps the legacy value");
            assertNull(row.getObject(2), table + ".recorded_by_oid is NULL for a legacy row");
            assertFalse(row.next(), table + " has exactly one row");
        }
    }
}
