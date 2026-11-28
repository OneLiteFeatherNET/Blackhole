package net.onelitefeather.blackhole.backend.analytics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The data age: every successful refresh writes its clock time, and a failed view keeps the time of its last success. */
class AnalyticsRefreshStateTest extends AnalyticsDatabaseTest {

    private static final long FIRST = Instant.parse("2026-10-01T10:00:00Z").toEpochMilli();
    private static final long SECOND = Instant.parse("2026-10-01T10:15:00Z").toEpochMilli();

    @Test
    void aSuccessfulRefreshRecordsTheClockTimeOfEveryView() {
        service(FIRST).refreshAll();

        List<List<Object>> rows = this.db.rows("SELECT view_name, refreshed_at FROM analytics.refresh_state ORDER BY view_name");

        assertEquals(AnalyticsViews.ALL.size(), rows.size(), "every view has a refresh time");
        for (List<Object> row : rows) {
            assertEquals(FIRST, row.get(1), "the refresh time of " + row.get(0) + " is the injected clock's time");
        }
    }

    @Test
    void aFailedViewKeepsTheRefreshTimeOfItsLastSuccess() {
        service(FIRST).refreshAll();
        this.db.execute("DROP MATERIALIZED VIEW analytics.report_reporters");

        service(SECOND).refreshAll();

        assertEquals(List.of(FIRST), this.db.column("SELECT refreshed_at FROM analytics.refresh_state WHERE view_name = 'report_reporters'"),
                "a failed view must not claim fresh data");
        assertEquals(List.of(SECOND), this.db.column("SELECT refreshed_at FROM analytics.refresh_state WHERE view_name = 'reports_daily'"));
    }

    private AnalyticsRefreshService service(long epochMillis) {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
        return new AnalyticsRefreshService(new JdbcRefreshSessions(this.db.dataSource()), clock, new SimpleMeterRegistry());
    }
}
