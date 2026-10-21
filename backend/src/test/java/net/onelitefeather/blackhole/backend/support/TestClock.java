package net.onelitefeather.blackhole.backend.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A {@link Clock} a test moves by hand, so no test reads or waits for the real time.
 */
public final class TestClock extends Clock {

    /** A fixed, arbitrary start so expected timestamps are easy to read. */
    public static final Instant START = Instant.parse("2030-01-01T00:00:00Z");

    private Instant now;

    public TestClock() {
        this(START);
    }

    public TestClock(Instant now) {
        this.now = now;
    }

    public void advance(Duration duration) {
        this.now = this.now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
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
