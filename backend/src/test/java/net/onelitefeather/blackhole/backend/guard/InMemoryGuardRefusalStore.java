package net.onelitefeather.blackhole.backend.guard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A {@link GuardRefusalStore} held in memory, with the same sliding window as the sorted set. */
public class InMemoryGuardRefusalStore implements GuardRefusalStore {

    private final Map<Long, Long> counts = new HashMap<>();
    private final Map<Long, String> organisations = new HashMap<>();
    private final Map<Long, String> countries = new HashMap<>();
    private final Map<Long, List<Long>> times = new HashMap<>();

    @Override
    public synchronized void recordRefusal(long asn, String organisation, String countryCode) {
        this.counts.merge(asn, 1L, Long::sum);
        this.organisations.put(asn, organisation);
        this.countries.put(asn, countryCode);
    }

    @Override
    public synchronized long addBurstRefusal(long asn, long nowMillis, long windowMillis) {
        List<Long> window = this.times.computeIfAbsent(asn, key -> new ArrayList<>());
        window.add(nowMillis);
        window.removeIf(time -> time < nowMillis - windowMillis);
        return window.size();
    }

    @Override
    public synchronized List<SummaryRow> drainSummary() {
        List<SummaryRow> rows = new ArrayList<>();
        this.counts.forEach((asn, count) ->
                rows.add(new SummaryRow(asn, count, this.organisations.get(asn), this.countries.get(asn))));
        this.counts.clear();
        this.organisations.clear();
        this.countries.clear();
        return rows;
    }
}
