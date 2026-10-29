package net.onelitefeather.blackhole.backend.analytics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The single-runner lock, tested with a second session that holds it. */
class AnalyticsRefreshLockTest extends AnalyticsDatabaseTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void aRunDoesNothingWhileAnotherSessionHoldsTheLock() throws SQLException {
        insertPunishment("p-locked", "PUNISHMENT", false, STAFF_SOURCE, null, DAY);
        try (Connection holder = this.db.open(); Statement statement = holder.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + JdbcRefreshSessions.LOCK_KEY + ")");

            AnalyticsRefreshService.RefreshRun run = service().refreshAll();

            assertTrue(run.skipped(), "the second replica must skip the run instead of waiting");
        }
        assertEquals(List.of(0L), this.db.column("SELECT count(*) FROM analytics.punishments_daily"),
                "a skipped run leaves the views unchanged");
    }

    @Test
    void theLockIsFreeAfterARunEvenWhenOneViewFails() {
        this.db.execute("DROP MATERIALIZED VIEW analytics.report_reporters");

        AnalyticsRefreshService.RefreshRun run = service().refreshAll();

        assertEquals(new AnalyticsRefreshService.RefreshRun(6, 1, false), run);
        assertEquals(List.of(true), this.db.column("SELECT pg_try_advisory_lock(" + JdbcRefreshSessions.LOCK_KEY + ")"),
                "the lock is released when the run ends, also after a failed view");
    }

    private AnalyticsRefreshService service() {
        return new AnalyticsRefreshService(new JdbcRefreshSessions(this.db.dataSource()), CLOCK, new SimpleMeterRegistry());
    }
}
