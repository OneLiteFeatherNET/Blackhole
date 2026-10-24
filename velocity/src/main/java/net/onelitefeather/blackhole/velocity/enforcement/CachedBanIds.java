package net.onelitefeather.blackhole.velocity.enforcement;

import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * {@link BanIds} over the backend's reveal endpoint. The call is waited for at most the timeout, so a
 * slow backend delays a screen by that much at most; the player is denied either way. Answers live in
 * this object's memory only and expire, a failed answer sooner than a good one. The Ban ID is never
 * logged: a failure is logged without the response, and only an eight character Crockford base32 value
 * is ever accepted, so nothing else (a legacy hex short id, say) can reach a screen.
 */
public final class CachedBanIds implements BanIds {

    /** How long a fetched Ban ID is reused. */
    public static final Duration TTL = Duration.ofMinutes(5);
    /** How long a failure is remembered, so a failing backend is not asked again with every retry. */
    public static final Duration FAILURE_TTL = Duration.ofSeconds(10);

    private static final Pattern BAN_ID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{8}");
    private static final int PURGE_ABOVE = 256;

    private record Cached(Optional<String> banId, long expiresAt) {
    }

    private final BackendGateway backend;
    private final Clock clock;
    private final Duration timeout;
    private final Logger logger;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public CachedBanIds(@NotNull BackendGateway backend, @NotNull Clock clock, @NotNull Duration timeout) {
        this(backend, clock, timeout, LoggerFactory.getLogger(CachedBanIds.class));
    }

    public CachedBanIds(@NotNull BackendGateway backend, @NotNull Clock clock, @NotNull Duration timeout, @NotNull Logger logger) {
        this.backend = backend;
        this.clock = clock;
        this.timeout = timeout;
        this.logger = logger;
    }

    @Override
    public Optional<String> of(@Nullable String punishmentIdentifier) {
        if (punishmentIdentifier == null || punishmentIdentifier.isBlank()) {
            return Optional.empty();
        }
        long now = this.clock.millis();
        Cached cached = this.cache.get(punishmentIdentifier);
        if (cached != null && cached.expiresAt() > now) {
            return cached.banId();
        }
        Optional<String> banId = fetch(punishmentIdentifier);
        if (this.cache.size() > PURGE_ABOVE) {
            this.cache.values().removeIf(entry -> entry.expiresAt() <= now);
        }
        Duration ttl = banId.isPresent() ? TTL : FAILURE_TTL;
        this.cache.put(punishmentIdentifier, new Cached(banId, now + ttl.toMillis()));
        return banId;
    }

    private Optional<String> fetch(String punishmentIdentifier) {
        try {
            String value = this.backend.revealBanId(punishmentIdentifier).get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (value != null && BAN_ID.matcher(value).matches()) {
                return Optional.of(value);
            }
            this.logger.debug("The backend answered a Ban ID in an unexpected shape, leaving it out");
        } catch (TimeoutException e) {
            this.logger.debug("The Ban ID was not available in time, leaving it out");
        } catch (ExecutionException e) {
            this.logger.debug("The Ban ID could not be loaded, leaving it out ({})", e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            this.logger.debug("The Ban ID could not be requested, leaving it out ({})", e.getClass().getSimpleName());
        }
        return Optional.empty();
    }
}
