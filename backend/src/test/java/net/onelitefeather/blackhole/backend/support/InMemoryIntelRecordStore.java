package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.intel.IntelAllowEntry;
import net.onelitefeather.blackhole.backend.intel.IntelClass;
import net.onelitefeather.blackhole.backend.intel.IntelHit;
import net.onelitefeather.blackhole.backend.intel.IntelPolicy;
import net.onelitefeather.blackhole.backend.intel.IntelRecordStore;
import net.onelitefeather.blackhole.backend.intel.IntelSourceStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An in-memory {@link IntelRecordStore}. Reads and writes can be made to fail, as a database outage would. */
public final class InMemoryIntelRecordStore implements IntelRecordStore {

    private final Map<IntelClass, IntelPolicy> policies = new EnumMap<>(IntelClass.class);
    private final Map<String, IntelAllowEntry> allowList = new LinkedHashMap<>();
    private final List<IntelHit> hits = new ArrayList<>();
    private final List<IntelSourceStatus> sources = new ArrayList<>();
    private boolean failingReads;

    /** Makes every read fail from now on. */
    public void failReads() {
        this.failingReads = true;
    }

    /** Seeds the source status a refresh would have left. */
    public void putSourceStatus(IntelSourceStatus status) {
        this.sources.add(status);
    }

    private void checkReadable() {
        if (this.failingReads) {
            throw new IllegalStateException("database is down");
        }
    }

    @Override
    public synchronized Map<IntelClass, IntelPolicy> loadPolicies() {
        checkReadable();
        return new EnumMap<>(this.policies);
    }

    @Override
    public synchronized void savePolicy(IntelClass classification, IntelPolicy policy, long now) {
        this.policies.put(classification, policy);
    }

    @Override
    public synchronized List<IntelAllowEntry> loadAllowList() {
        checkReadable();
        return List.copyOf(this.allowList.values());
    }

    @Override
    public synchronized void saveAllowEntry(IntelAllowEntry entry) {
        this.allowList.put(entry.ownerHash(), entry);
    }

    @Override
    public synchronized List<IntelAllowEntry> allowListPage(int limit, int offset) {
        checkReadable();
        return this.allowList.values().stream()
                .sorted(Comparator.comparingLong(IntelAllowEntry::createdAt).reversed()
                        .thenComparing(IntelAllowEntry::ownerHash))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized Optional<IntelAllowEntry> findAllowEntry(String ownerHash) {
        checkReadable();
        return Optional.ofNullable(this.allowList.get(ownerHash));
    }

    @Override
    public synchronized boolean deleteAllowEntry(String ownerHash) {
        return this.allowList.remove(ownerHash) != null;
    }

    @Override
    public synchronized void insertHit(IntelHit hit) {
        this.hits.add(hit);
    }

    @Override
    public synchronized List<IntelHit> hitsOf(String ownerHash, int limit) {
        checkReadable();
        return this.hits.stream()
                .filter(hit -> hit.ownerHash().equals(ownerHash))
                .sorted(Comparator.comparingLong(IntelHit::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized int deleteHitsCreatedBefore(long cutoff) {
        int before = this.hits.size();
        this.hits.removeIf(hit -> hit.createdAt() < cutoff);
        return before - this.hits.size();
    }

    @Override
    public synchronized List<IntelSourceStatus> sourceStatuses() {
        checkReadable();
        return List.copyOf(this.sources);
    }

    /** @return every stored hit, oldest first */
    public synchronized List<IntelHit> allHits() {
        return List.copyOf(this.hits);
    }
}
