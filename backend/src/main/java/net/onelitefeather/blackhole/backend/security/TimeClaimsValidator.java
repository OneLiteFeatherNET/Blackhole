package net.onelitefeather.blackhole.backend.security;

import io.micronaut.context.annotation.Value;
import io.micronaut.security.token.Claims;
import io.micronaut.security.token.jwt.validator.GenericJwtClaimsValidator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Checks {@code exp} and {@code nbf} with an explicit, bounded clock skew (RFC 8725). A token must
 * carry {@code exp}; {@code nbf} is optional. The library's own expiration and not-before validators
 * are switched off in {@code application.yml} because they have no leeway and (for {@code nbf}) are
 * off by default. The skew is configurable only downwards: a value above {@link #MAX_SKEW_SECONDS}
 * or below zero stops the application from starting.
 */
@Singleton
public class TimeClaimsValidator implements GenericJwtClaimsValidator<Object> {

    /** The largest clock skew the application will ever accept. */
    public static final long MAX_SKEW_SECONDS = 60;

    private final Clock clock;
    private final Duration skew;

    @Inject
    public TimeClaimsValidator(@Value("${blackhole.security.clock-skew-seconds:60}") long skewSeconds) {
        this(skewSeconds, Clock.systemUTC());
    }

    TimeClaimsValidator(long skewSeconds, Clock clock) {
        if (skewSeconds < 0 || skewSeconds > MAX_SKEW_SECONDS) {
            throw new IllegalStateException("blackhole.security.clock-skew-seconds must be between 0 and "
                    + MAX_SKEW_SECONDS + " but is " + skewSeconds);
        }
        this.skew = Duration.ofSeconds(skewSeconds);
        this.clock = clock;
    }

    @Override
    public boolean validate(Claims claims, Object request) {
        Instant now = this.clock.instant();
        Instant expiry = instant(claims.get(Claims.EXPIRATION_TIME));
        if (expiry == null || !now.minus(this.skew).isBefore(expiry)) {
            return false;
        }
        Object notBeforeClaim = claims.get(Claims.NOT_BEFORE);
        if (notBeforeClaim == null) {
            return true;
        }
        Instant notBefore = instant(notBeforeClaim);
        return notBefore != null && !now.plus(this.skew).isBefore(notBefore);
    }

    private static Instant instant(Object claim) {
        if (claim instanceof Date date) {
            return date.toInstant();
        }
        if (claim instanceof Number number) {
            return Instant.ofEpochSecond(number.longValue());
        }
        return null;
    }
}
