package net.onelitefeather.blackhole.backend.kick;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Duration;

/**
 * When a kick stops waiting for a proxy to answer. The backend never waits in a request: the deadline
 * is written with the kick and the status is derived from it when the kick is read, so no thread is
 * held and the injected {@link Clock} is the only source of time.
 */
@Singleton
public class KickDeadline {

    private final Clock clock;
    private final Duration ackTimeout;

    /**
     * @param ackTimeout how long a proxy has to acknowledge a kick ({@code blackhole.kick.ack-timeout})
     */
    public KickDeadline(Clock clock, @Value("${blackhole.kick.ack-timeout:PT5S}") Duration ackTimeout) {
        this.clock = clock;
        this.ackTimeout = ackTimeout;
    }

    /** The current time in epoch milliseconds. */
    public long now() {
        return this.clock.millis();
    }

    /** The deadline of a kick written now. */
    public long deadlineAt() {
        return now() + this.ackTimeout.toMillis();
    }

    /** How long the proxies get to answer. */
    public Duration ackTimeout() {
        return this.ackTimeout;
    }
}
