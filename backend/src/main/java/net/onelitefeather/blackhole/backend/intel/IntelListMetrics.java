package net.onelitefeather.blackhole.backend.intel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Gauges and a counter of the list sources. The gauges read the source status when Prometheus scrapes, never on the
 * login path; a read that fails reports NaN instead of failing the scrape.
 */
@Singleton
public class IntelListMetrics {

    static final String ENTRIES = "blackhole.intel.lists.entries";
    static final String REFRESH_FAILURES = "blackhole.intel.lists.refresh_failures";
    static final String LAST_REFRESH = "blackhole.intel.lists.last_refresh_seconds";
    private static final Logger LOGGER = LoggerFactory.getLogger(IntelListMetrics.class);

    private final IntelRecordStore store;
    private final MeterRegistry meters;

    public IntelListMetrics(IntelRecordStore store, List<IntelListSourceConfig> sources, MeterRegistry meters) {
        this.store = store;
        this.meters = meters;
        for (IntelListSourceConfig source : sources) {
            String key = source.getKey();
            Gauge.builder(ENTRIES, this, metrics -> metrics.statusValue(key, IntelSourceStatus::entryCount))
                    .description("Entries of the active generation of a list source").tag("source", key).register(meters);
            Gauge.builder(LAST_REFRESH, this, metrics -> metrics.statusValue(key, status -> status.lastSuccessAt() == null
                            ? Double.NaN : status.lastSuccessAt() / 1000.0))
                    .description("Epoch seconds of the last successful refresh of a list source").tag("source", key).register(meters);
        }
    }

    /** Counts one failed refresh of a source. */
    public void refreshFailed(String sourceKey) {
        Counter.builder(REFRESH_FAILURES).description("Failed refreshes of a list source").tag("source", sourceKey)
                .register(this.meters).increment();
    }

    private double statusValue(String sourceKey, java.util.function.ToDoubleFunction<IntelSourceStatus> value) {
        try {
            Optional<IntelSourceStatus> status = this.store.sourceStatuses().stream()
                    .filter(row -> row.sourceKey().equals(sourceKey)).findFirst();
            return status.map(value::applyAsDouble).orElse(Double.NaN);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read the status of list source {} for the metrics", sourceKey);
            return Double.NaN;
        }
    }
}
