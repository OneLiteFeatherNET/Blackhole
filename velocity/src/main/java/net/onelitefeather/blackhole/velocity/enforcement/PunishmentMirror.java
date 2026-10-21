package net.onelitefeather.blackhole.velocity.enforcement;

import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The proxy-local copy of the active punishments of the players connected to it, per section.
 * A player is "loaded" once their state was read (from Redis or the backend); only loaded players
 * are kept up to date from sync messages, so the mirror never grows beyond the online players.
 */
public final class PunishmentMirror {

    /** owner hash -> section key -> the active punishment; absence of the owner = not loaded. */
    private final ConcurrentMap<String, ConcurrentMap<String, PunishmentSyncMessage>> byOwner = new ConcurrentHashMap<>();

    /**
     * Replaces the player's state with {@code active} and marks them loaded.
     */
    public void load(String owner, Collection<PunishmentSyncMessage> active) {
        ConcurrentMap<String, PunishmentSyncMessage> sections = new ConcurrentHashMap<>();
        for (PunishmentSyncMessage message : active) {
            sections.put(message.section(), message);
        }
        this.byOwner.put(owner, sections);
    }

    public boolean isLoaded(String owner) {
        return this.byOwner.containsKey(owner);
    }

    /**
     * Applies a sync message to a loaded player: SET occupies the section, CLEARED empties it only
     * if it still holds the cleared punishment. Messages for players that are not loaded are ignored.
     */
    public void apply(PunishmentSyncMessage message) {
        Map<String, PunishmentSyncMessage> sections = this.byOwner.get(message.owner());
        if (sections == null) {
            return;
        }
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
        Map<String, PunishmentSyncMessage> sections = this.byOwner.get(owner);
        return sections == null ? List.of() : List.copyOf(sections.values());
    }

    public void forget(String owner) {
        this.byOwner.remove(owner);
    }
}
