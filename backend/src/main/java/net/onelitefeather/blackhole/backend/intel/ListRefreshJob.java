package net.onelitefeather.blackhole.backend.intel;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Refreshes every enabled list source on its interval. Each replica runs this job, and the debounce claim in the store
 * lets only one of them download a given source inside the window.
 */
@Singleton
public class ListRefreshJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListRefreshJob.class);

    private final List<IntelListSourceConfig> sources;
    private final ListRefreshService refresh;
    private final IntelListMetrics metrics;

    public ListRefreshJob(List<IntelListSourceConfig> sources, ListRefreshService refresh, IntelListMetrics metrics) {
        this.sources = sources;
        this.refresh = refresh;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelay = "${blackhole.intel.lists.refresh-interval:PT6H}",
            initialDelay = "${blackhole.intel.lists.refresh-initial-delay:PT1M}"
    )
    void refreshEnabledSources() {
        refreshAll();
    }

    /** @return how many sources were refreshed, not counting those skipped by the debounce or failed */
    public int refreshAll() {
        int refreshed = 0;
        for (IntelListSourceConfig source : this.sources) {
            if (!source.isEnabled()) {
                continue;
            }
            try {
                ListRefreshService.Outcome outcome = this.refresh.refresh(source);
                if (outcome == ListRefreshService.Outcome.REFRESHED) {
                    refreshed++;
                } else if (outcome == ListRefreshService.Outcome.FAILED) {
                    this.metrics.refreshFailed(source.getKey());
                }
            } catch (RuntimeException e) {
                LOGGER.warn("List source {} could not be refreshed", source.getKey());
            }
        }
        return refreshed;
    }
}
