package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.utils.IdGenerator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Runs V7 over rows that exist at V6 and compares the SQL backfill with the Java implementation. */
@Tag("integration")
class PunishmentShortIdMigrationIntegrationTest extends PostgresIntegrationTest {

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("short_id_backfill")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void theBackfillMatchesTheJavaImplementationForRandomAndLegacyIdentifiers() throws SQLException {
        Random random = new Random(42);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(IdGenerator.generateId(UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001")), "A1B2C3D4");
        expected.put("a1b2c3d4-0000-4000-8000-000000000002", "A1B2C3D4");
        expected.put("A1B2C3D4-0000-4000-8000-000000000003", "A1B2C3D4");
        expected.put("legacy-identifier", PunishmentShortId.storageKey("legacy-identifier"));
        for (int i = 0; i < 200; i++) {
            UUID uuid = new UUID(random.nextLong(), random.nextLong());
            expected.put(IdGenerator.generateId(uuid), PunishmentShortId.storageKey(uuid.toString()));
        }

        flyway("6").migrate();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.createStatement().execute("SET search_path TO short_id_backfill");
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO punishments (identifier) VALUES (?)")) {
                for (String identifier : expected.keySet()) {
                    insert.setString(1, identifier);
                    insert.addBatch();
                }
                insert.executeBatch();
            }

            flyway("7").migrate();

            try (ResultSet rows = connection.createStatement().executeQuery("SELECT identifier, short_id FROM punishments")) {
                int seen = 0;
                while (rows.next()) {
                    String identifier = rows.getString(1);
                    assertEquals(expected.get(identifier), rows.getString(2), "short id of " + identifier);
                    seen++;
                }
                assertEquals(expected.size(), seen);
            }
            try (ResultSet nullable = connection.createStatement().executeQuery(
                    "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'short_id_backfill' "
                            + "AND table_name = 'punishments' AND column_name = 'short_id'")) {
                assertFalse(nullable.next() && "YES".equals(nullable.getString(1)), "short_id is NOT NULL after the backfill");
            }
        }
    }
}
