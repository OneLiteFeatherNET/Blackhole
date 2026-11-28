package net.onelitefeather.blackhole.backend.analytics;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;

/**
 * Runs the analytics refresh on the configured interval. Without {@code blackhole.analytics.refresh.enabled=true} the bean does not
 * exist and nothing is scheduled; the views then keep their last data.
 */
@Singleton
@Requires(property = "blackhole.analytics.refresh.enabled", value = "true", defaultValue = "true")
public class AnalyticsRefreshScheduler {

    private final AnalyticsRefreshService refresh;

    public AnalyticsRefreshScheduler(AnalyticsRefreshService refresh) {
        this.refresh = refresh;
    }

    @Scheduled(
            fixedDelay = "${blackhole.analytics.refresh.interval:15m}",
            initialDelay = "${blackhole.analytics.refresh.interval:15m}"
    )
    void refresh() {
        this.refresh.refreshAll();
    }
}
