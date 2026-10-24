package net.onelitefeather.blackhole.backend.live;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.annotation.PostConstruct;

import java.time.Duration;

/**
 * The {@code blackhole.chat.live.*} limits of the live streams of one replica. They bound held state (open streams, how long one lasts,
 * how often it is kept busy), not request rates. Documented next to their defaults in {@code application.yml}.
 */
@ConfigurationProperties("blackhole.chat.live")
public class LiveProperties {

    private int maxStreams = 2000;
    private Duration keepAlive = Duration.ofSeconds(15);
    private Duration maxLifetime = Duration.ofMinutes(30);

    @PostConstruct
    void validate() {
        if (this.maxStreams < 1) {
            throw new IllegalArgumentException("blackhole.chat.live.max-streams must be positive");
        }
        if (this.keepAlive.isZero() || this.keepAlive.isNegative()) {
            throw new IllegalArgumentException("blackhole.chat.live.keep-alive must be positive");
        }
        if (this.maxLifetime.isZero() || this.maxLifetime.isNegative()) {
            throw new IllegalArgumentException("blackhole.chat.live.max-lifetime must be positive");
        }
    }

    /** The most streams this replica holds; beyond it a new stream is refused with 503 and the client polls. */
    public int getMaxStreams() {
        return this.maxStreams;
    }

    public void setMaxStreams(int maxStreams) {
        this.maxStreams = maxStreams;
    }

    /** How often a keep-alive is sent, and the session is checked, on an open stream. */
    public Duration getKeepAlive() {
        return this.keepAlive;
    }

    public void setKeepAlive(Duration keepAlive) {
        this.keepAlive = keepAlive;
    }

    /** How long a stream may stay open before the client reconnects. */
    public Duration getMaxLifetime() {
        return this.maxLifetime;
    }

    public void setMaxLifetime(Duration maxLifetime) {
        this.maxLifetime = maxLifetime;
    }
}
