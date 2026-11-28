package net.onelitefeather.blackhole.velocity.enforcement;


import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * The proxy-local copy of the active punishments of the players connected to it, per section.
 * A player is "loaded" once their state was read from the backend; only loaded players are kept up
 * to date from event-bus messages, so the mirror never grows beyond the online players. Every load is
 * time-stamped: an entry older than the maximum age is not {@linkplain #isFresh fresh} and is read
 * again, which bounds how long a lost event can leave the proxy with stale state.
 * <p>
 * The mirror holds at most {@code maxOwners} players. A load beyond the bound evicts offline players
 * first (oldest first), then the oldest entry of all. An evicted player is simply read again at the next
 * check, so the bound costs backend reads, never correctness.
 */
public final class PunishmentMirror {

    /** The bound used when none is given. */
    public static final int DEFAULT_MAX_OWNERS = 4096;

    private record Entry(ConcurrentMap<String, PunishmentSyncMessage> sections, long loadedAtMillis) {
    }

    /** owner hash -> the player's active punishments per section key; absence of the owner = not loaded. */
    private final ConcurrentMap<String, Entry> byOwner = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxOwners;
    private final Supplier<Collection<String>> onlineOwners;

    public PunishmentMirror() {
        this(Clock.systemUTC());
    }

    public PunishmentMirror(Clock clock) {
        this(clock, DEFAULT_MAX_OWNERS, Set::of);
    }

    /**
     * @param maxOwners   the most players the mirror holds at once
     * @param onlineOwners the owner hashes of the players connected to this proxy, asked only when the bound is exceeded
     */
    public PunishmentMirror(Clock clock, int maxOwners, Supplier<Collection<String>> onlineOwners) {
        this.clock = clock;
        this.maxOwners = maxOwners;
        this.onlineOwners = onlineOwners;
    }

    /**
     * Replaces the player's state with {@code active} and marks them loaded.
     */
    public void load(String owner, Collection<PunishmentSyncMessage> active) {
        ConcurrentMap<String, PunishmentSyncMessage> sections = new ConcurrentHashMap<>();
        for (PunishmentSyncMessage message : active) {
            sections.put(message.section(), message);
        }
        this.byOwner.put(owner, new Entry(sections, this.clock.millis()));
        if (this.byOwner.size() > this.maxOwners) {
            evictBeyondBound(owner);
        }
    }

    /** Evicts entries until the bound holds again: offline owners first, then the oldest; never the owner just loaded. */
    private void evictBeyondBound(String justLoaded) {
        Set<String> online = Set.copyOf(this.onlineOwners.get());
        List<Map.Entry<String, Entry>> candidates = this.byOwner.entrySet().stream()
                .filter(candidate -> !candidate.getKey().equals(justLoaded))
                .sorted(Comparator.comparing((Map.Entry<String, Entry> candidate) -> online.contains(candidate.getKey()))
                        .thenComparingLong(candidate -> candidate.getValue().loadedAtMillis()))
                .toList();
        for (Map.Entry<String, Entry> candidate : candidates) {
            if (this.byOwner.size() <= this.maxOwners) {
                return;
            }
            this.byOwner.remove(candidate.getKey());
        }
    }

    public boolean isLoaded(String owner) {
        return this.byOwner.containsKey(owner);
    }

    /**
     * @return whether the player is loaded and was loaded less than {@code maxAge} ago
     */
    public boolean isFresh(String owner, Duration maxAge) {
        Entry entry = this.byOwner.get(owner);
        return entry != null && this.clock.millis() - entry.loadedAtMillis() < maxAge.toMillis();
    }

    /** Forgets every player, so each one is read from the backend again at the next check. */
    public void clear() {
        this.byOwner.clear();
    }

    /**
     * Applies a sync message to a loaded player: SET occupies the section, CLEARED empties it only
     * if it still holds the cleared punishment. Messages for players that are not loaded are ignored.
     */
    public void apply(PunishmentSyncMessage message) {
        Entry entry = this.byOwner.get(message.owner());
        if (entry == null) {
            return;
        }
        Map<String, PunishmentSyncMessage> sections = entry.sections();
        if (message.isSet()) {
            sections.put(message.section(), message);
        } else {
            sections.computeIfPresent(message.section(), (key, held) ->
                    message.punishmentIdentifier() == null || message.punishmentIdentifier().equals(held.punishmentIdentifier()) ? null : held);
        }
    }

    /**
     * @return what the mirror holds for the player; empty if not loaded
     */
    public List<PunishmentSyncMessage> active(String owner) {
        Entry entry = this.byOwner.get(owner);
        return entry == null ? List.of() : List.copyOf(entry.sections().values());
    }

    public void forget(String owner) {
        this.byOwner.remove(owner);
    }
}
