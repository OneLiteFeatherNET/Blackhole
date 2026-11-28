package net.onelitefeather.blackhole.backend.areaban;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * The unlifted area bans of this replica, held in memory so that a login check reads no database. The service reloads
 * it after every write, every replica reloads it on the snapshot interval, and it loads once at start-up. A failed
 * reload keeps the last good snapshot.
 */
@Singleton
public class AreaBanCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(AreaBanCache.class);

    private final AreaBanStore store;
    private volatile List<AreaBan> snapshot = List.of();

    public AreaBanCache(AreaBanStore store) {
        this.store = store;
    }

    /**
     * The ban that applies to a login from this ASN or country at {@code nowMillis}.
     *
     * @param asn         the autonomous system of the address, or null when unknown
     * @param countryCode the ISO country of the address, or null when unknown
     */
    public Optional<AreaBan> applying(Long asn, String countryCode, long nowMillis) {
        String asnValue = asn == null ? null : Long.toString(asn);
        return this.snapshot.stream()
                .filter(ban -> ban.isActiveAt(nowMillis))
                .filter(ban -> ban.kind() == AreaBan.Kind.ASN ? ban.value().equals(asnValue) : ban.value().equals(countryCode))
                .findFirst();
    }

    /** The bans of the snapshot that apply at {@code nowMillis}: not lifted, and not past their expiry. */
    public List<AreaBan> active(long nowMillis) {
        return this.snapshot.stream().filter(ban -> ban.isActiveAt(nowMillis)).toList();
    }

    /** Reads the unlifted bans again. A failure keeps the snapshot in use. */
    public synchronized void reload() {
        try {
            this.snapshot = List.copyOf(this.store.listUnlifted());
        } catch (RuntimeException e) {
            LOGGER.warn("Could not reload the area bans; keeping the last ones");
        }
    }

    /** Loads at start-up, then periodically, so that a change made on another replica is seen. */
    @Scheduled(
            fixedDelay = "${blackhole.guard.snapshot-interval:PT2M}",
            initialDelay = "PT0S"
    )
    void poll() {
        reload();
    }
}
