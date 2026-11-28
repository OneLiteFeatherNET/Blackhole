package net.onelitefeather.blackhole.backend.analytics;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A session takes the reporting role with SET ROLE, which applies its privileges (the test user is the superuser, and a superuser
 * drops that status once the role is set). Permission errors carry SQLSTATE 42501.
 */
class AnalyticsReaderRoleTest extends AnalyticsDatabaseTest {

    private static final String PERMISSION_DENIED = "42501";

    @Test
    void theReaderSelectsEveryView() throws SQLException {
        try (Connection reader = asReader(); Statement statement = reader.createStatement()) {
            for (String view : AnalyticsViews.ALL) {
                statement.executeQuery("SELECT count(*) FROM analytics." + view).close();
            }
        }
    }

    @Test
    void theReaderIsRefusedOnEveryBaseTable() throws SQLException {
        for (String table : new String[] {"punishments", "appeals", "reports", "case_event", "punishment_profiles", "elo_scores"}) {
            assertDenied("SELECT count(*) FROM " + table, "the reader must not read the base table " + table);
        }
    }

    @Test
    void theReaderCannotWriteOrCreateInTheAnalyticsSchema() throws SQLException {
        assertDenied("INSERT INTO analytics.refresh_state (view_name, refreshed_at) VALUES ('x', 1)", "no insert");
        assertDenied("UPDATE analytics.refresh_state SET refreshed_at = 2", "no update");
        assertDenied("DELETE FROM analytics.refresh_state", "no delete");
        assertDenied("CREATE TABLE analytics.reader_made (id int)", "no create");
    }

    private Connection asReader() throws SQLException {
        Connection connection = this.db.open();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE analytics_reader");
        }
        return connection;
    }

    private void assertDenied(String sql, String message) throws SQLException {
        try (Connection reader = asReader(); Statement statement = reader.createStatement()) {
            SQLException denied = assertThrows(SQLException.class, () -> statement.execute(sql), message);
            assertEquals(PERMISSION_DENIED, denied.getSQLState(), message);
        }
    }
}
