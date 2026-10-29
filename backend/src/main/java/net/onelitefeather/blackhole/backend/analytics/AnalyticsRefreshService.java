package net.onelitefeather.blackhole.backend.analytics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Refreshes every analytics view in a fixed order. Only one replica refreshes at a time: when another holds the lock the run does
 * nothing. A failing view is counted and logged, keeps its previous data and refresh time, and does not stop the others.
 */
@Singleton
public class AnalyticsRefreshService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyticsRefreshService.class);

    private final RefreshSessions sessions;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Map<String, Timer> durations = new ConcurrentHashMap<>();
    private final Map<String, Counter> failures = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastSuccess = new ConcurrentHashMap<>();

    public AnalyticsRefreshService(RefreshSessions sessions, Clock clock, MeterRegistry meters) {
        this.sessions = sessions;
        this.clock = clock;
        this.meters = meters;
        for (String view : AnalyticsViews.ALL) {
            this.durations.put(view, Timer.builder("blackhole.analytics.refresh.duration").tag("view", view).register(meters));
            this.failures.put(view, meters.counter("blackhole.analytics.refresh.failures", "view", view));
            AtomicLong last = new AtomicLong();
            this.lastSuccess.put(view, last);
            Gauge.builder("blackhole.analytics.refresh.last_success", last, AtomicLong::get).tag("view", view).register(meters);
        }
    }

    /** The outcome of one run: how many views were refreshed and how many failed, or whether it was skipped. */
    public record RefreshRun(int refreshed, int failed, boolean skipped) {
    }

    public RefreshRun refreshAll() {
        Optional<RefreshSession> started = this.sessions.tryStart();
        if (started.isEmpty()) {
            LOGGER.debug("analytics refresh skipped: another replica holds the refresh lock");
            return new RefreshRun(0, 0, true);
        }
        int refreshed = 0;
        int failed = 0;
        try (RefreshSession session = started.get()) {
            for (String view : AnalyticsViews.ALL) {
                if (refreshView(session, view)) {
                    refreshed++;
                } else {
                    failed++;
                }
            }
        }
        LOGGER.info("analytics refresh finished: {} view(s) refreshed, {} failed", refreshed, failed);
        return new RefreshRun(refreshed, failed, false);
    }

    private boolean refreshView(RefreshSession session, String view) {
        Timer.Sample sample = Timer.start(this.meters);
        try {
            boolean populated = session.isPopulated(view);
            session.refresh(view, populated);
            session.recordRefreshed(view, this.clock.millis());
            this.lastSuccess.get(view).set(this.clock.instant().getEpochSecond());
            return true;
        } catch (RuntimeException e) {
            this.failures.get(view).increment();
            LOGGER.warn("analytics view {} was not refreshed and keeps its previous data: {}", view, e.getClass().getSimpleName());
            return false;
        } finally {
            sample.stop(this.durations.get(view));
        }
    }
}
