package net.onelitefeather.blackhole.backend.firewall;

import java.time.Duration;

/**
 * Waiting after failed firewall runs: 30 seconds after the first failure, doubling per further consecutive failure,
 * capped at 15 minutes. One success resets it. Time comes from the caller, so the rule is testable without a clock.
 */
public final class AliasBackoff {

    static final long BASE_MILLIS = Duration.ofSeconds(30).toMillis();
    static final long CAP_MILLIS = Duration.ofMinutes(15).toMillis();

    private int failures;
    private long waitUntil;

    /** @return true while the reconciler must not contact the firewall at {@code now} */
    public boolean waiting(long now) {
        return now < this.waitUntil;
    }

    /** Records a failed run at {@code now} and extends the wait. */
    public void failed(long now) {
        this.failures++;
        long delay = this.failures >= 32 ? CAP_MILLIS : Math.min(CAP_MILLIS, BASE_MILLIS << (this.failures - 1));
        this.waitUntil = now + delay;
    }

    /** Records a successful run: the next run is not delayed. */
    public void succeeded() {
        this.failures = 0;
        this.waitUntil = 0;
    }

    /** @return the number of consecutive failures */
    public int failures() {
        return this.failures;
    }
}
