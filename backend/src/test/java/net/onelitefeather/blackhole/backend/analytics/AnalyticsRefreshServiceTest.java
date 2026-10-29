package net.onelitefeather.blackhole.backend.analytics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The refresh run without a database: a recording session stands in for the lock and the views. */
class AnalyticsRefreshServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void refreshesEveryViewInTheDeclaredOrderAndClosesTheSession() {
        RecordingSession session = new RecordingSession();

        AnalyticsRefreshService.RefreshRun run = service(new FakeSessions(session), new SimpleMeterRegistry()).refreshAll();

        List<String> expected = AnalyticsViews.ALL.stream().map(view -> view + " concurrently").toList();
        assertEquals(expected, session.refreshes, "the views are refreshed in the declared order");
        assertEquals(new AnalyticsRefreshService.RefreshRun(7, 0, false), run);
        assertTrue(session.closed, "the session, and with it the lock, is closed after the run");
    }

    @Test
    void aFailingViewIsCountedAndTheOtherViewsStillRun() {
        RecordingSession session = new RecordingSession();
        session.failing.add("reports_daily");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        AnalyticsRefreshService.RefreshRun run = service(new FakeSessions(session), meters).refreshAll();

        assertEquals(new AnalyticsRefreshService.RefreshRun(6, 1, false), run);
        assertTrue(session.refreshes.contains("staff_activity_daily concurrently"), "a later view still runs after a failure");
        assertEquals(1.0, meters.counter("blackhole.analytics.refresh.failures", "view", "reports_daily").count(),
                "the failure is counted for its view");
    }

    @Test
    void aFailingViewIsNotRecordedAsRefreshed() {
        RecordingSession session = new RecordingSession();
        session.failing.add("report_reporters");

        service(new FakeSessions(session), new SimpleMeterRegistry()).refreshAll();

        assertFalse(session.recorded.containsKey("report_reporters"), "a failed view keeps its previous refresh time");
        assertEquals(NOW.toEpochMilli(), session.recorded.get("appeals_daily"));
    }

    @Test
    void anUnpopulatedViewIsRefreshedOnceWithoutConcurrency() {
        RecordingSession session = new RecordingSession();
        session.unpopulated.add("elo_distribution");

        service(new FakeSessions(session), new SimpleMeterRegistry()).refreshAll();

        assertTrue(session.refreshes.contains("elo_distribution plain"), "an unpopulated view cannot be refreshed concurrently");
        assertFalse(session.refreshes.contains("elo_distribution concurrently"));
    }

    @Test
    void aRunWhileAnotherReplicaHoldsTheLockRefreshesNothing() {
        RecordingSession session = new RecordingSession();
        FakeSessions sessions = new FakeSessions(session);
        sessions.locked = true;

        AnalyticsRefreshService.RefreshRun run = service(sessions, new SimpleMeterRegistry()).refreshAll();

        assertTrue(run.skipped());
        assertEquals(List.of(), session.refreshes, "a skipped run touches no view");
    }

    private static AnalyticsRefreshService service(RefreshSessions sessions, SimpleMeterRegistry meters) {
        return new AnalyticsRefreshService(sessions, CLOCK, meters);
    }

    private static final class FakeSessions implements RefreshSessions {

        private final RecordingSession session;
        boolean locked;

        FakeSessions(RecordingSession session) {
            this.session = session;
        }

        @Override
        public Optional<RefreshSession> tryStart() {
            return this.locked ? Optional.empty() : Optional.of(this.session);
        }
    }

    private static final class RecordingSession implements RefreshSession {

        final Set<String> failing = new HashSet<>();
        final Set<String> unpopulated = new HashSet<>();
        final List<String> refreshes = new ArrayList<>();
        final Map<String, Long> recorded = new LinkedHashMap<>();
        boolean closed;

        @Override
        public boolean isPopulated(String view) {
            return !this.unpopulated.contains(view);
        }

        @Override
        public void refresh(String view, boolean concurrently) {
            if (this.failing.contains(view)) {
                throw new IllegalStateException("refresh of " + view + " failed");
            }
            this.refreshes.add(view + (concurrently ? " concurrently" : " plain"));
        }

        @Override
        public void recordRefreshed(String view, long refreshedAtMillis) {
            this.recorded.put(view, refreshedAtMillis);
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }
}
