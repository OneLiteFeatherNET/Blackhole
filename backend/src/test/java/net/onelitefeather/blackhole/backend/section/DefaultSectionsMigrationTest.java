package net.onelitefeather.blackhole.backend.section;

import io.micronaut.context.ApplicationContext;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
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

@Tag("integration")
class DefaultSectionsMigrationTest extends PostgresIntegrationTest {

    private record Row(String enforcement, int base, int soft, String softDuration, int permanent, int pardon, boolean enabled) {
    }

    @Test
    void freshDatabaseListsTheChatAndNetworkSectionsWithTheDefaults() throws SQLException {
        Map<String, Row> rows = new LinkedHashMap<>();
        try (ApplicationContext context = ApplicationContext.run(contextProperties());
             Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select section_key, enforcement, base_score, soft_threshold, soft_duration, permanent_threshold, pardon_score, enabled "
                             + "from punishment_sections order by section_key")) {
            while (resultSet.next()) {
                rows.put(resultSet.getString("section_key"), new Row(
                        resultSet.getString("enforcement"), resultSet.getInt("base_score"),
                        resultSet.getInt("soft_threshold"), resultSet.getString("soft_duration"),
                        resultSet.getInt("permanent_threshold"), resultSet.getInt("pardon_score"),
                        resultSet.getBoolean("enabled")));
            }
        }

        assertEquals(2, rows.size(), "a fresh installation seeds exactly the chat and network sections, got " + rows.keySet());
        assertEquals(new Row("MUTE", 1000, 700, "PT24H", 300, 700, true), rows.get("chat"), "chat section defaults");
        assertEquals(new Row("BAN", 1000, 700, "P3D", 300, 700, true), rows.get("network"), "network section defaults");
    }
}
