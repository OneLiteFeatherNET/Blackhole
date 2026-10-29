package net.onelitefeather.blackhole.backend.guard;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the refusal counters of the last window into one {@code guard.denials.summary} event: the total and the ten ASNs
 * with the most refusals. Nothing is published for an empty window. The payload carries an ASN, an organisation and a
 * country, never an address, an owner hash or a player name.
 */
@Singleton
public class GuardSummaryService {

    static final String SUMMARY_EVENT = "guard.denials.summary";
    static final int TOP = 10;

    private final GuardRefusalStore store;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final MeterRegistry meters;
    private long windowStart;

    public GuardSummaryService(GuardRefusalStore store, DomainEventPublisher events, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.events = events;
        this.clock = clock;
        this.meters = meters;
        this.windowStart = clock.millis();
    }

    /** Publishes the window that ends now, if it had any refusals, and starts the next window. */
    public synchronized void publish() {
        long now = this.clock.millis();
        List<GuardRefusalStore.SummaryRow> rows = this.store.drainSummary();
        long start = this.windowStart;
        this.windowStart = now;
        if (rows.isEmpty()) {
            return;
        }
        long total = rows.stream().mapToLong(GuardRefusalStore.SummaryRow::count).sum();
        List<Map<String, Object>> top = new ArrayList<>();
        rows.stream()
                .sorted(Comparator.comparingLong(GuardRefusalStore.SummaryRow::count).reversed()
                        .thenComparingLong(GuardRefusalStore.SummaryRow::asn))
                .limit(TOP)
                .forEach(row -> top.add(entry(row)));
        Map<String, Object> payload = new HashMap<>();
        payload.put("windowStart", start);
        payload.put("windowEnd", now);
        payload.put("total", total);
        payload.put("top", top);
        this.events.publish(SUMMARY_EVENT, payload);
        this.meters.counter("blackhole.guard.summary.published").increment();
    }

    private static Map<String, Object> entry(GuardRefusalStore.SummaryRow row) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("asn", Long.toString(row.asn()));
        entry.put("count", row.count());
        if (row.organisation() != null) {
            entry.put("organisation", row.organisation());
        }
        if (row.countryCode() != null) {
            entry.put("countryCode", row.countryCode());
        }
        return entry;
    }
}
