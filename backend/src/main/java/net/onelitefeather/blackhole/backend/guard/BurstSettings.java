package net.onelitefeather.blackhole.backend.guard;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.time.Duration;

/**
 * The burst rule (blackhole.guard.burst.*). Off by default. When on, {@code threshold} guard refusals from one ASN
 * within {@code window} create an automatic area ban of {@code banDuration}.
 */
@Singleton
public class BurstSettings {

    private final boolean enabled;
    private final int threshold;
    private final Duration window;
    private final Duration banDuration;

    public BurstSettings(
            @Value("${blackhole.guard.burst.enabled:false}") boolean enabled,
            @Value("${blackhole.guard.burst.threshold:10}") int threshold,
            @Value("${blackhole.guard.burst.window:PT5M}") Duration window,
            @Value("${blackhole.guard.burst.ban-duration:PT2H}") Duration banDuration
    ) {
        if (threshold < 1) {
            throw new IllegalArgumentException("blackhole.guard.burst.threshold must be at least 1");
        }
        this.enabled = enabled;
        this.threshold = threshold;
        this.window = window;
        this.banDuration = banDuration;
    }

    public boolean enabled() {
        return this.enabled;
    }

    public int threshold() {
        return this.threshold;
    }

    public Duration window() {
        return this.window;
    }

    public Duration banDuration() {
        return this.banDuration;
    }
}
