package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.intel.ListGenerationStore;
import net.onelitefeather.blackhole.backend.intel.ListGenerationStore.ListedCidr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** A {@link ListGenerationStore} in memory: one fixture per test, no database. Writes can be made to fail per source. */
public final class InMemoryListGenerationStore implements ListGenerationStore {

    private record Generation(long createdAt, Set<String> cidrs) {
    }

    private final Map<String, Long> lastAttempt = new HashMap<>();
    private final Map<String, Long> entryCount = new HashMap<>();
    private final Map<String, String> lastError = new HashMap<>();
    private final Map<String, TreeMap<Long, Generation>> generations = new HashMap<>();
    private final Map<String, Long> active = new HashMap<>();
    private final Set<String> failingWrites = new HashSet<>();
    private int activations;

    @Override
    public boolean claimRefresh(String sourceKey, long now, long debounceMillis) {
        Long last = this.lastAttempt.get(sourceKey);
        if (last != null && last > now - debounceMillis) {
            return false;
        }
        this.lastAttempt.put(sourceKey, now);
        return true;
    }

    @Override
    public long openGeneration(String sourceKey, long now) {
        TreeMap<Long, Generation> of = this.generations.computeIfAbsent(sourceKey, k -> new TreeMap<>());
        long next = of.isEmpty() ? 1 : of.lastKey() + 1;
        of.put(next, new Generation(now, new LinkedHashSet<>()));
        return next;
    }

    @Override
    public void writeEntries(String sourceKey, long generation, Collection<String> cidrs) {
        if (this.failingWrites.contains(sourceKey)) {
            throw new IllegalStateException("could not write the list entries");
        }
        this.generations.get(sourceKey).get(generation).cidrs().addAll(cidrs);
    }

    @Override
    public void activate(String sourceKey, long generation, long now) {
        this.active.put(sourceKey, generation);
        this.activations++;
    }

    @Override
    public void deleteOtherGenerations(String sourceKey, long keep) {
        this.generations.getOrDefault(sourceKey, new TreeMap<>()).keySet().removeIf(g -> g != keep);
    }

    @Override
    public void discardGeneration(String sourceKey, long generation) {
        this.generations.getOrDefault(sourceKey, new TreeMap<>()).remove(generation);
    }

    @Override
    public void recordSuccess(String sourceKey, long now, long entries, long invalid) {
        this.entryCount.put(sourceKey, entries);
        this.lastError.remove(sourceKey);
    }

    @Override
    public void recordFailure(String sourceKey, long now, String error) {
        this.lastError.put(sourceKey, error);
    }

    @Override
    public long activeEntryCount(String sourceKey) {
        return this.entryCount.getOrDefault(sourceKey, 0L);
    }

    @Override
    public Map<String, Long> activeGenerations() {
        return Map.copyOf(this.active);
    }

    @Override
    public List<ListedCidr> loadActiveEntries() {
        List<ListedCidr> rows = new ArrayList<>();
        for (Map.Entry<String, Long> entry : this.active.entrySet()) {
            Generation generation = this.generations.get(entry.getKey()).get(entry.getValue());
            generation.cidrs().forEach(cidr -> rows.add(new ListedCidr(entry.getKey(), cidr)));
        }
        return rows;
    }

    @Override
    public int deleteOrphanGenerationsCreatedBefore(long cutoff) {
        int removed = 0;
        for (Map.Entry<String, TreeMap<Long, Generation>> source : this.generations.entrySet()) {
            Long activeGeneration = this.active.get(source.getKey());
            var iterator = source.getValue().entrySet().iterator();
            while (iterator.hasNext()) {
                var generation = iterator.next();
                if (!generation.getKey().equals(activeGeneration) && generation.getValue().createdAt() < cutoff) {
                    iterator.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Test helper: makes every write of this source fail until cleared. */
    public void failWritesFor(String sourceKey) {
        this.failingWrites.add(sourceKey);
    }

    /** Test helper: how many generations were switched to active, over all sources. */
    public int activations() {
        return this.activations;
    }

    /** Test helper: the numbers of the generations the store still holds for a source. */
    public Set<Long> generationNumbers(String sourceKey) {
        return Set.copyOf(this.generations.getOrDefault(sourceKey, new TreeMap<>()).keySet());
    }

    /** Test helper: the last failure message of a source, or null when the last refresh did not fail. */
    public String lastError(String sourceKey) {
        return this.lastError.get(sourceKey);
    }
}
