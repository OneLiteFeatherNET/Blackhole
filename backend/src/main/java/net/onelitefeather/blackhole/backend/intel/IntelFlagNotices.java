package net.onelitefeather.blackhole.backend.intel;

import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether a FLAG hit of an owner produces a staff notice. One notice per owner per 30 minutes, so a reconnect
 * loop produces one notice. Held in memory per replica: the window is short and a restart only adds one notice.
 */
@Singleton
public class IntelFlagNotices {

    static final Duration WINDOW = Duration.ofMinutes(30);
    static final int PRUNE_ABOVE = 10_000;

    private final Map<String, Long> lastNoticeAt = new ConcurrentHashMap<>();

    /**
     * @param ownerHash the owner hash; never published, only used as a key here
     * @param now       the current epoch milliseconds
     * @return true when no notice was made for the owner inside the window, and this call starts a new window
     */
    public boolean claim(String ownerHash, long now) {
        if (this.lastNoticeAt.size() > PRUNE_ABOVE) {
            prune(now);
        }
        boolean[] claimed = {false};
        this.lastNoticeAt.compute(ownerHash, (owner, last) -> {
            if (last == null || now - last >= WINDOW.toMillis()) {
                claimed[0] = true;
                return now;
            }
            return last;
        });
        return claimed[0];
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, Long>> entries = this.lastNoticeAt.entrySet().iterator();
        while (entries.hasNext()) {
            if (now - entries.next().getValue() >= WINDOW.toMillis()) {
                entries.remove();
            }
        }
    }
}
