package net.onelitefeather.blackhole.backend.kick;

import io.micronaut.serde.annotation.Serdeable;

/**
 * How a kick went. Never stored: it is derived each time the kick is read from the deadline, the
 * current time and the acknowledgements the proxies stored, so {@link #NOT_ONLINE} can never appear
 * before the deadline and two backend replicas always agree.
 */
@Serdeable
public enum KickStatus {

    /** No proxy has found the player yet and the deadline has not passed. */
    PENDING,

    /** At least one proxy moved the player (a mass kick: at least one proxy answered, or moved players). */
    DELIVERED,

    /** A proxy found the player, but not on the target, so nothing was moved. */
    NOT_ON_TARGET,

    /** The deadline passed and no proxy found the player. Not proof that the player is offline. */
    NOT_ONLINE,

    /** A mass kick whose deadline passed with no proxy answering at all. */
    NO_PROXY_ANSWER;

    /**
     * Derives the status.
     *
     * @param kind        a kick of one player or a mass kick
     * @param deadlineAt  when the proxies stop being waited for, epoch milliseconds
     * @param now         the current time, epoch milliseconds
     * @param deliveries  what the proxies reported
     */
    public static KickStatus derive(KickKind kind, long deadlineAt, long now, KickDeliveries deliveries) {
        boolean passed = now >= deadlineAt;
        if (deliveries.moved() > 0) {
            return DELIVERED;
        }
        if (kind == KickKind.MASS) {
            if (!passed) {
                return PENDING;
            }
            return deliveries.acknowledged() > 0 ? DELIVERED : NO_PROXY_ANSWER;
        }
        if (deliveries.found() > 0) {
            return NOT_ON_TARGET;
        }
        return passed ? NOT_ONLINE : PENDING;
    }
}
