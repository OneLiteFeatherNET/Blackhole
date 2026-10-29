package net.onelitefeather.blackhole.backend.firewall;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** A clock the test moves by hand, so expiry is tested without the wall clock. */
final class FeedTestClock extends Clock {

    private Instant now;

    FeedTestClock(long epochMillis) {
        this.now = Instant.ofEpochMilli(epochMillis);
    }

    void advanceTo(long epochMillis) {
        this.now = Instant.ofEpochMilli(epochMillis);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return this.now;
    }
}
