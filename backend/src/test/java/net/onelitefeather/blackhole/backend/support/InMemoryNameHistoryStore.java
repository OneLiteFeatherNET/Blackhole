package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.intel.names.NameHistoryStore;
import net.onelitefeather.blackhole.backend.intel.names.NameRecord;
import net.onelitefeather.blackhole.backend.intel.names.OwnerRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** A {@link NameHistoryStore} in memory: one fixture per test, no database. Mirrors the SQL of the real store. */
public final class InMemoryNameHistoryStore implements NameHistoryStore {

    private final Map<String, Row> rows = new LinkedHashMap<>();

    @Override
    public void upsert(String ownerHash, UUID playerUuid, String playerName, long seenAt) {
        String key = key(ownerHash, playerName);
        Row old = this.rows.get(key);
        if (old == null) {
            this.rows.put(key, new Row(ownerHash, playerUuid, playerName, seenAt, seenAt));
            return;
        }
        boolean newer = seenAt >= old.lastSeen();
        this.rows.put(key, new Row(ownerHash, old.playerUuid(), newer ? playerName : old.name(),
                Math.min(old.firstSeen(), seenAt), Math.max(old.lastSeen(), seenAt)));
    }

    @Override
    public List<NameRecord> findByOwner(String ownerHash, int limit, int offset) {
        return this.rows.values().stream().filter(r -> r.ownerHash().equals(ownerHash))
                .sorted(Comparator.comparingLong(Row::lastSeen).reversed().thenComparing(Row::name))
                .skip(offset).limit(limit)
                .map(r -> new NameRecord(r.ownerHash(), r.playerUuid(), r.name(), r.firstSeen(), r.lastSeen())).toList();
    }

    @Override
    public List<OwnerRecord> findOwnersOfName(String name, int limit) {
        String lower = name.toLowerCase(Locale.ROOT);
        List<OwnerRecord> owners = new ArrayList<>();
        this.rows.values().stream().filter(r -> r.name().toLowerCase(Locale.ROOT).equals(lower))
                .sorted(Comparator.comparingLong(Row::lastSeen).reversed().thenComparing(Row::ownerHash))
                .forEach(r -> owners.add(new OwnerRecord(r.ownerHash(), r.playerUuid(), currentName(r.ownerHash()),
                        r.firstSeen(), r.lastSeen())));
        return owners.stream().limit(limit).toList();
    }

    @Override
    public int deleteLastSeenBefore(long cutoff) {
        List<String> expired = this.rows.entrySet().stream().filter(e -> e.getValue().lastSeen() < cutoff)
                .map(Map.Entry::getKey).toList();
        expired.forEach(this.rows::remove);
        return expired.size();
    }

    @Override
    public int deleteByOwner(String ownerHash) {
        List<String> owned = this.rows.entrySet().stream().filter(e -> e.getValue().ownerHash().equals(ownerHash))
                .map(Map.Entry::getKey).toList();
        owned.forEach(this.rows::remove);
        return owned.size();
    }

    /** Test helper: every stored name. */
    public List<NameRecord> all() {
        return this.rows.values().stream()
                .map(r -> new NameRecord(r.ownerHash(), r.playerUuid(), r.name(), r.firstSeen(), r.lastSeen())).toList();
    }

    private String currentName(String ownerHash) {
        return this.rows.values().stream().filter(r -> r.ownerHash().equals(ownerHash))
                .max(Comparator.comparingLong(Row::lastSeen)).map(Row::name).orElseThrow();
    }

    private static String key(String ownerHash, String name) {
        return ownerHash + ":" + name.toLowerCase(Locale.ROOT);
    }

    private record Row(String ownerHash, UUID playerUuid, String name, long firstSeen, long lastSeen) {
    }
}
