package net.onelitefeather.blackhole.backend.intel;

import java.util.List;
import java.util.Map;

/**
 * Persistence of the policies, the allow-list, the hits and the list source status. Every operation is one statement,
 * so none needs a transaction ({@code @Transactional} is unusable here).
 */
public interface IntelRecordStore {

    /** @return the stored policy per classification; a class without a row is absent */
    Map<IntelClass, IntelPolicy> loadPolicies();

    /** Stores the policy of one classification, replacing the stored value. */
    void savePolicy(IntelClass classification, IntelPolicy policy, long now);

    /** @return every allow-list entry, expired ones included */
    List<IntelAllowEntry> loadAllowList();

    /** Stores an allow-list entry, replacing the entry of the same owner. */
    void saveAllowEntry(IntelAllowEntry entry);

    /** Stores one hit. */
    void insertHit(IntelHit hit);

    /** @return the newest hits of one owner, at most {@code limit} */
    List<IntelHit> hitsOf(String ownerHash, int limit);

    /**
     * Deletes the hits created before the cutoff.
     *
     * @return how many hits were deleted
     */
    int deleteHitsCreatedBefore(long cutoff);

    /** @return the refresh status of every list source that has ever been attempted */
    List<IntelSourceStatus> sourceStatuses();
}
