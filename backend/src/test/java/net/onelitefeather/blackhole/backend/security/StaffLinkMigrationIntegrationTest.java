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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs V10 over a database that already holds V1-V9 rows: the rows keep their data, the new
 * {@code actor_oid} columns are NULL (no backfill) and the link tables start empty and enforce the
 * one-to-one rule on both sides.
 */
@Tag("integration")
class StaffLinkMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final String SCHEMA = "staff_link_migration";
    private static final String CONSTRAINT_SCHEMA = "staff_link_constraints";
    private static final UUID RECORDER = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID MINECRAFT = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    private static Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static void execute(Statement statement, String sql) throws SQLException {
        statement.execute(sql.replace("{recorder}", RECORDER.toString()).replace("{mc}", MINECRAFT.toString()));
    }

    @Test
    void rowsFromBeforeTheLinkKeepTheirDataAndGetNoActorOid() throws SQLException {
        flyway(SCHEMA, "9").migrate();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            execute(statement, "SET search_path TO " + SCHEMA);
            execute(statement, "INSERT INTO punishments (identifier, source, short_id, recorded_by_oid) VALUES ('old-1', '{mc}', 'AAAAAAAA', '{recorder}')");
            execute(statement, "INSERT INTO reports (identifier, created_at, updated_at, recorded_by_oid) "
                    + "VALUES ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 1, 1, '{recorder}')");
            execute(statement, "INSERT INTO evidence_items (identifier, owner, kind, source_system, created_at, created_by, recorded_by_oid) "
                    + "VALUES ('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'o', 1, 'it', 1, '{mc}', '{recorder}')");
            execute(statement, "INSERT INTO evidence_audit (identifier, evidence_identifier, owner, action, actor, recorded_by_oid, occurred_at) "
                    + "VALUES ('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'o', 0, '{mc}', '{recorder}', 1)");

            flyway(SCHEMA, "10").migrate();

            assertKept(statement, "punishments", "source");
            assertKept(statement, "reports", "recorded_by_oid");
            assertKept(statement, "evidence_items", "created_by");
            assertKept(statement, "evidence_audit", "actor");
            for (String table : new String[]{"staff_link", "staff_link_code"}) {
                try (ResultSet count = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    assertTrue(count.next());
                    assertEquals(0, count.getInt(1), table + " starts empty: nothing is linked retroactively");
                }
            }
        }
    }

    @Test
    void theDatabaseEnforcesTheOneToOneLinkOnBothSides() throws SQLException {
        flyway(CONSTRAINT_SCHEMA, "10").migrate();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            execute(statement, "SET search_path TO " + CONSTRAINT_SCHEMA);
            execute(statement, "INSERT INTO staff_link (oid, minecraft_uuid, linked_at, linked_by_oid) VALUES ('{recorder}', '{mc}', 1, '{recorder}')");

            assertThrows(SQLException.class, () -> execute(statement,
                    "INSERT INTO staff_link (oid, minecraft_uuid, linked_at, linked_by_oid) VALUES ('{recorder}', gen_random_uuid(), 1, '{recorder}')"),
                    "one account per person");
            assertThrows(SQLException.class, () -> execute(statement,
                    "INSERT INTO staff_link (oid, minecraft_uuid, linked_at, linked_by_oid) VALUES (gen_random_uuid(), '{mc}', 1, '{recorder}')"),
                    "one person per account");
        }
    }

    private static void assertKept(Statement statement, String table, String column) throws SQLException {
        try (ResultSet row = statement.executeQuery("SELECT " + column + ", actor_oid FROM " + table)) {
            assertTrue(row.next(), table + " keeps its row");
            assertTrue(row.getObject(1, UUID.class) != null, table + "." + column + " keeps its value");
            assertNull(row.getObject(2), table + ".actor_oid is NULL for an old row");
            assertFalse(row.next(), table + " has exactly one row");
        }
    }
}
