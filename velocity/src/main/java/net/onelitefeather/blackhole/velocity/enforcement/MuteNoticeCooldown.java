package net.onelitefeather.blackhole.velocity.enforcement;

import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides when a muted player who keeps chatting gets the full mute notice again. Every attempt
 * would otherwise print the whole notice; within the cooldown the one-line reminder is enough. The
 * time comes from the injected clock, so it needs no sleeping to test.
 */
public final class MuteNoticeCooldown {

    /** Above this many remembered players, expired entries are dropped on the next call. */
    private static final int PURGE_THRESHOLD = 256;

    private final Clock clock;
    private final long cooldownMillis;
    private final Map<String, Long> lastFullNotice = new ConcurrentHashMap<>();

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
     * @param owner the player's owner hash
     * @return {@code true} if the full notice is due (none shown within the cooldown),
     *         {@code false} if the one-line reminder is enough
     */
    public boolean fullNoticeDue(@NotNull String owner) {
        long now = this.clock.millis();
        if (this.lastFullNotice.size() > PURGE_THRESHOLD) {
            this.lastFullNotice.values().removeIf(shown -> now - shown >= this.cooldownMillis);
        }
        boolean[] due = {false};
        this.lastFullNotice.compute(owner, (key, shown) -> {
            if (shown == null || now - shown >= this.cooldownMillis) {
                due[0] = true;
                return now;
            }
            return shown;
        });
        return due[0];
    }
}
