package net.onelitefeather.blackhole.backend.intel;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Persistence of list generations. A refresh writes a new generation, then moves the active pointer with one
 * statement; readers only ever see the active generation. Every operation is one statement, so none needs a transaction.
 */
public interface ListGenerationStore {

    /** One stored network of the active generation of a source. */
    record ListedCidr(String sourceKey, String cidr) {
    }

    /**
     * Claims the refresh of one source. Succeeds only when no attempt started at or after {@code now - debounceMillis},
     * so two replicas or a repeated click run one download.
     *
     * @return true when this caller may refresh the source
     */
    boolean claimRefresh(String sourceKey, long now, long debounceMillis);

    /** Opens a generation for writing: its number is one above the last one the source has. */
    long openGeneration(String sourceKey, long now);

    /** Writes a batch of canonical networks into a generation. A network already stored there is ignored. */
    void writeEntries(String sourceKey, long generation, Collection<String> cidrs);

    /** Makes a generation the active one of its source. Readers switch with this statement. */
    void activate(String sourceKey, long generation, long now);

    /** Removes every generation of the source other than the given one, with its entries. */
    void deleteOtherGenerations(String sourceKey, long keep);

    /** Removes one generation with its entries, after a failed write. */
    void discardGeneration(String sourceKey, long generation);

    /** Records a successful refresh and the counts of its list. */
    void recordSuccess(String sourceKey, long now, long entries, long invalid);

    /** Records a failed refresh. The active generation is untouched. */
    void recordFailure(String sourceKey, long now, String error);

    /** @return the entry count of the last successful refresh, 0 when none */
    long activeEntryCount(String sourceKey);

    /** @return the active generation number per source, for sources that have one */
    Map<String, Long> activeGenerations();

    /** @return every network of every active generation */
    List<ListedCidr> loadActiveEntries();

    /**
     * Removes generations that are not active and were created before the cutoff, with their entries: what a crashed
     * refresh left behind.
     *
     * @return how many generations were removed
     */
    int deleteOrphanGenerationsCreatedBefore(long cutoff);
}
