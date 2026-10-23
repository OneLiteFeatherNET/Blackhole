package net.onelitefeather.blackhole.backend.section;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runs V11 over sections that exist at V10: every one of them becomes a PATTERN section. */
@Tag("integration")
class SectionTargetKindMigrationIntegrationTest extends PostgresIntegrationTest {

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("section_target_kind")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void existingSectionsBecomePatternSectionsAndKeepTheirPatterns() throws SQLException {
        flyway("10").migrate();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO section_target_kind");
            statement.execute("""
                    INSERT INTO punishment_sections
                        (section_key, display_name, enforcement, base_score, permanent_threshold, pardon_score, permanent_reason)
                    VALUES ('bedwars', 'Bedwars', 'SERVER', 1000, 300, 700, 'banned')""");
            statement.execute("INSERT INTO punishment_section_servers (section_key, pattern) VALUES ('bedwars', 'bedwars-*')");

            flyway("11").migrate();

            Map<String, String> kinds = new LinkedHashMap<>();
            try (ResultSet rows = statement.executeQuery("SELECT section_key, target_kind FROM punishment_sections ORDER BY section_key")) {
                while (rows.next()) {
                    kinds.put(rows.getString(1), rows.getString(2));
                }
            }
            assertEquals(Map.of("bedwars", "PATTERN", "chat", "PATTERN", "network", "PATTERN"), kinds,
                    "the seeded and the custom section all read as PATTERN");
            try (ResultSet patterns = statement.executeQuery("SELECT pattern FROM punishment_section_servers WHERE section_key = 'bedwars'")) {
                patterns.next();
                assertEquals("bedwars-*", patterns.getString(1), "the patterns are untouched");
            }
        }
    }
}
