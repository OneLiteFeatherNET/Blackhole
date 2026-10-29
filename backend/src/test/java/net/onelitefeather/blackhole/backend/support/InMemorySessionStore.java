package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.intel.SessionRecord;
import net.onelitefeather.blackhole.backend.intel.SessionStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** A {@link SessionStore} in memory: one fixture per test, no database. */
public final class InMemorySessionStore implements SessionStore {

    private final Map<UUID, SessionRecord> rows = new LinkedHashMap<>();

    @Override
    public boolean insert(SessionRecord record) {
        return this.rows.putIfAbsent(record.sessionId(), record) == null;
    }

    @Override
    public Optional<SessionRecord> find(UUID sessionId) {
        return Optional.ofNullable(this.rows.get(sessionId));
    }

    @Override
    public void updateClient(UUID sessionId, String clientBrand, String clientVersion) {
        SessionRecord old = this.rows.get(sessionId);
        if (old == null) {
            return;
        }
        this.rows.put(sessionId, new SessionRecord(old.sessionId(), old.ownerHash(), old.playerUuid(), old.playerName(), old.ip(),
                old.ipToken(), old.asn(), old.asOrg(), old.countryCode(),
                clientBrand != null ? clientBrand : old.clientBrand(),
                clientVersion != null ? clientVersion : old.clientVersion(),
                old.protocolVersion(), old.virtualHost(), old.outcome(), old.startedAt(), old.endedAt()));
    }

    @Override
    public void markEnded(UUID sessionId, long endedAt) {
        SessionRecord old = this.rows.get(sessionId);
        if (old == null || old.endedAt() != null) {
            return;
        }
        this.rows.put(sessionId, new SessionRecord(old.sessionId(), old.ownerHash(), old.playerUuid(), old.playerName(), old.ip(),
                old.ipToken(), old.asn(), old.asOrg(), old.countryCode(), old.clientBrand(), old.clientVersion(),
                old.protocolVersion(), old.virtualHost(), old.outcome(), old.startedAt(), endedAt));
    }

    @Override
    public List<SessionRecord> findByOwner(String ownerHash, int limit, int offset) {
        List<SessionRecord> owned = new ArrayList<>(this.rows.values().stream()
                .filter(r -> r.ownerHash().equals(ownerHash))
                .sorted(Comparator.comparingLong(SessionRecord::startedAt).reversed()).toList());
        return owned.stream().skip(offset).limit(limit).toList();
    }

    @Override
    public int deleteStartedBefore(long cutoff) {
        List<UUID> expired = this.rows.values().stream().filter(r -> r.startedAt() < cutoff).map(SessionRecord::sessionId).toList();
        expired.forEach(this.rows::remove);
        return expired.size();
    }

    @Override
    public Set<String> tokensOfOwner(String ownerHash, long since) {
        Set<String> tokens = new HashSet<>();
        for (SessionRecord r : this.rows.values()) {
            if (r.ownerHash().equals(ownerHash) && r.startedAt() >= since && r.ipToken() != null) {
                tokens.add(r.ipToken());
            }
        }
        return tokens;
    }

    @Override
    public List<TokenSighting> sightingsOfTokens(Collection<String> tokens, long since) {
        return this.rows.values().stream()
                .filter(r -> r.ipToken() != null && tokens.contains(r.ipToken()) && r.startedAt() >= since)
                .map(r -> new TokenSighting(r.ipToken(), r.ownerHash(), r.startedAt())).toList();
    }

    /** Test helper: every stored session. */
    public List<SessionRecord> all() {
        return List.copyOf(this.rows.values());
    }
}
