package net.onelitefeather.blackhole.backend.intel.names;

import java.util.List;
import java.util.UUID;

/** Persistence of the name history. Every operation is one statement, so none needs a transaction. */
public interface NameHistoryStore {

    /**
     * Adds the name of an owner, or moves its entry on: the first and last seen times widen, and the casing of the
     * newest login is kept. Names are matched without case.
     */
    void upsert(String ownerHash, UUID playerUuid, String playerName, long seenAt);

    /** The names of one owner, newest last seen first. */
    List<NameRecord> findByOwner(String ownerHash, int limit, int offset);

    /** The owners that used this name (matched without case), newest last seen first. */
    List<OwnerRecord> findOwnersOfName(String name, int limit);

    /** @return how many names were deleted */
    int deleteLastSeenBefore(long cutoff);

    /** Erasure of an owner: its names are removed and no other owner's are touched. @return how many were deleted */
    int deleteByOwner(String ownerHash);
}
