package net.onelitefeather.blackhole.discord.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the test moves by hand; each test creates its own, so nothing is shared.
 */
public final class MutableClock extends Clock {

    private Instant now = Instant.parse("2026-01-01T12:00:00Z");

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
