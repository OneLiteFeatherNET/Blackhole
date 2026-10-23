package net.onelitefeather.blackhole.velocity.enforcement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides when a muted player gets the full mute notice. The notice is shown once per punishment:
 * a different punishment of the same player is a new notice, the same one only becomes due again
 * after the cooldown - in between the one-line reminder is enough. The time comes from the injected
 * clock, so it needs no sleeping to test. Entries are dropped when they expire or the player leaves.
 */
public final class MuteNoticeCooldown {

    /** Above this many remembered players, expired entries are dropped on the next call. */
    private static final int PURGE_THRESHOLD = 256;

    private record Shown(String punishmentId, long at) {
    }

    private final Clock clock;
    private final long cooldownMillis;
    private final Map<String, Shown> lastFullNotice = new ConcurrentHashMap<>();

    /**
     * @param clock    the time source
     * @param cooldown how long after a full notice the reminder is used instead
     */
    public MuteNoticeCooldown(@NotNull Clock clock, @NotNull Duration cooldown) {
        this.clock = clock;
        this.cooldownMillis = cooldown.toMillis();
    }

    /**
     * Records that the player is about to get a notice and tells which kind.
     *
     * @param owner        the player's owner hash
     * @param punishmentId the identifier of the mute the notice is about
     * @return {@code true} if the full notice is due (none shown for this punishment within the
     *         cooldown), {@code false} if the one-line reminder is enough
     */
    public boolean fullNoticeDue(@NotNull String owner, @Nullable String punishmentId) {
        long now = this.clock.millis();
        String id = punishmentId == null ? "" : punishmentId;
        if (this.lastFullNotice.size() > PURGE_THRESHOLD) {
            this.lastFullNotice.values().removeIf(shown -> now - shown.at() >= this.cooldownMillis);
        }
        boolean[] due = {false};
        this.lastFullNotice.compute(owner, (key, shown) -> {
            if (shown == null || !shown.punishmentId().equals(id) || now - shown.at() >= this.cooldownMillis) {
                due[0] = true;
                return new Shown(id, now);
            }
            return shown;
        });
        return due[0];
    }

    /**
     * Drops what is remembered about a player, when they leave.
     *
     * @param owner the player's owner hash
     */
    public void forget(@NotNull String owner) {
        this.lastFullNotice.remove(owner);
    }
}
