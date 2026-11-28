package net.onelitefeather.blackhole.backend.publicbans;

import java.time.Duration;

/**
 * The public status of a listed entry, a pure function of the stored instants and the clock. An expiry that has
 * passed is {@link #EXPIRED} without any sweep having run; a revoke always wins.
 */
public enum PublicBanStatus {
    ACTIVE,
    EXPIRED,
    REVOKED;

    /**
     * @param expiresAtMillis the expiry instant, or {@code null} for a permanent entry
     * @param revokedAtMillis the revoke instant, or {@code null} when not revoked
     * @param nowMillis       the injected clock
     */
    public static PublicBanStatus at(Long expiresAtMillis, Long revokedAtMillis, long nowMillis) {
        if (revokedAtMillis != null) {
            return REVOKED;
        }
        if (expiresAtMillis != null && expiresAtMillis <= nowMillis) {
            return EXPIRED;
        }
        return ACTIVE;
    }

    /**
     * The visibility rule, the same as the SQL condition of the reader: an active entry is always listed, an ended one
     * while {@code now < endedAt + retention}, where {@code endedAt} is the revoke instant or else the expiry instant.
     */
    public static boolean listed(Long expiresAtMillis, Long revokedAtMillis, long nowMillis, Duration retention) {
        if (at(expiresAtMillis, revokedAtMillis, nowMillis) == ACTIVE) {
            return true;
        }
        long endedAt = revokedAtMillis != null ? revokedAtMillis : expiresAtMillis;
        return nowMillis < endedAt + retention.toMillis();
    }
}
