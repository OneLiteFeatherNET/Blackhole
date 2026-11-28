package net.onelitefeather.blackhole.backend.intel;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports DOWN only when every enabled list source has failed for longer than two refresh intervals. A DOWN indicator
 * does not block logins: the classifier keeps the last good generation of every source.
 *
 * <p>The failing duration of a source is measured from its last success. A source that never succeeded is measured
 * from the time this bean was created, because no first attempt is stored; a restart therefore delays DOWN by at most
 * two refresh intervals.</p>
 */
@Singleton
public class IntelListsHealthIndicator implements HealthIndicator {

    static final String NAME = "intel-lists";

    private final IntelRecordStore store;
    private final Clock clock;
    private final List<IntelListSourceConfig> sources;
    private final Duration refreshInterval;
    /** The creation time, the reference for a source that never succeeded. */
    private final long startedAt;

    public IntelListsHealthIndicator(
            IntelRecordStore store,
            Clock clock,
            List<IntelListSourceConfig> sources,
            @Value("${blackhole.intel.lists.refresh-interval:PT6H}") Duration refreshInterval
    ) {
        this.store = store;
        this.clock = clock;
        this.sources = sources;
        this.refreshInterval = refreshInterval;
        this.startedAt = clock.millis();
    }

    /** @return true when there is at least one enabled source and every one has failed for longer than two intervals */
    public boolean isDown() {
        Map<String, IntelSourceStatus> statuses = new HashMap<>();
        for (IntelSourceStatus status : this.store.sourceStatuses()) {
            statuses.put(status.sourceKey(), status);
        }
        long threshold = this.clock.millis() - this.refreshInterval.toMillis() * 2;
        int enabled = 0;
        for (IntelListSourceConfig source : this.sources) {
            if (!source.isEnabled()) {
                continue;
            }
            enabled++;
            if (!failingLongerThan(statuses.get(source.getKey()), threshold, this.startedAt)) {
                return false;
            }
        }
        return enabled > 0;
    }

    /** A source is failing for longer than the threshold when its last refresh failed and its reference point is older. */
    private static boolean failingLongerThan(IntelSourceStatus status, long threshold, long startedAt) {
        if (status == null || status.lastError() == null) {
            return false;
        }
        long since = status.lastSuccessAt() != null ? status.lastSuccessAt() : startedAt;
        return since < threshold;
    }

    @Override
    public Publisher<HealthResult> getResult() {
        HealthStatus status = isDown() ? HealthStatus.DOWN : HealthStatus.UP;
        return Publishers.just(HealthResult.builder(NAME, status).build());
    }
}
