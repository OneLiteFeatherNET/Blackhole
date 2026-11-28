package net.onelitefeather.blackhole.backend.analytics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AnalyticsSchemaMigrationTest extends AnalyticsDatabaseTest {

    @Test
    void theAnalyticsSchemaHoldsTheSevenViewsInTheirRefreshOrder() {
        List<Object> views = this.db.column("SELECT matviewname FROM pg_matviews WHERE schemaname = 'analytics' ORDER BY matviewname");

        assertEquals(List.of("appeals_daily", "client_rule_hits_daily", "elo_distribution", "punishments_daily", "report_reporters",
                "reports_daily", "staff_activity_daily"), views);
    }

    @Test
    void theRefreshStateTableExistsForTheDataAge() {
        List<Object> tables = this.db.column("SELECT table_name FROM information_schema.tables WHERE table_schema = 'analytics' "
                + "AND table_name = 'refresh_state'");

        assertEquals(List.of("refresh_state"), tables, "Metabase reads the age of its data from this table");
    }

    @Test
    void theReportingRoleIsAGroupRoleWithoutLogin() {
        List<Object> login = this.db.column("SELECT rolcanlogin FROM pg_roles WHERE rolname = 'analytics_reader'");

        assertEquals(List.of(false), login, "the reporting role carries no login and no password");
    }

    @Test
    void theRoleBlockRunsAgainWithoutError() {
        // Roles are cluster-wide and a test container is reused, so the migration's role creation must be idempotent.
        assertDoesNotThrow(() -> this.db.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'analytics_reader') THEN
                        CREATE ROLE analytics_reader NOLOGIN;
                    END IF;
                END
                $$"""));
    }
}
