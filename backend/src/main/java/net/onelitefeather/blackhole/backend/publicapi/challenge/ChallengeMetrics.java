package net.onelitefeather.blackhole.backend.publicapi.challenge;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;

/**
 * The counters {@code blackhole.public.challenge{outcome}}. The only tag is the outcome, so nothing about the player,
 * the nonce or the payload can reach a metric.
 */
@Singleton
public class ChallengeMetrics {

    /** The counter name. */
    public static final String METER = "blackhole.public.challenge";

    private final MeterRegistry meters;

    public ChallengeMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    /** A challenge was returned. */
    public void issued() {
        count("issued");
    }

    /** A verified solution was accepted by the reserve. */
    public void solved() {
        count("solved");
    }

    /** A solution was refused: a parse error, a bad signature, an expiry, a key prefix or a purpose that does not match. */
    public void rejected() {
        count("rejected");
    }

    /** A verified solution was already used. */
    public void replayed() {
        count("replayed");
    }

    /** The reserve could not answer; the request got 503. */
    public void unavailable() {
        count("unavailable");
    }

    private void count(String outcome) {
        this.meters.counter(METER, "outcome", outcome).increment();
    }
}
