package net.onelitefeather.blackhole.backend.intel;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence of player sessions. Every operation is one statement, so none needs a transaction. */
public interface SessionStore {

    /** @return false when the session id is already stored; nothing is changed then */
    boolean insert(SessionRecord record);

    Optional<SessionRecord> find(UUID sessionId);

    /** A null argument keeps the stored value. */
    void updateClient(UUID sessionId, String clientBrand, String clientVersion);

    /** Sets the end time only while none is stored: the first end report wins. */
    void markEnded(UUID sessionId, long endedAt);

    /** The sessions of one owner, newest first. */
    List<SessionRecord> findByOwner(String ownerHash, int limit, int offset);

    /** @return how many sessions were deleted */
    int deleteStartedBefore(long cutoff);

    /** The keyed IP tokens one owner used since the given time, for evasion correlation. */
    Set<String> tokensOfOwner(String ownerHash, long since);

    /** The owners that used any of these tokens since the given time, for evasion correlation. */
    List<TokenSighting> sightingsOfTokens(Collection<String> tokens, long since);

    record TokenSighting(String token, String ownerHash, long startedAt) {
    }
}
