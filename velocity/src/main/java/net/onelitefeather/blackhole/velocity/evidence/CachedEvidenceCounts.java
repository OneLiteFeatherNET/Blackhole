package net.onelitefeather.blackhole.velocity.evidence;

import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link EvidenceCounts} over the backend's count endpoint. The call is asynchronous and waited for
 * at most the timeout, so a slow backend delays a denial screen by that much at most; the answer,
 * <em>including a failed or timed-out one</em>, is cached for 30 seconds so a player who keeps
 * retrying (or chatting while muted) does not trigger a call each time and a failing backend is not
 * asked again and again. A failure simply yields no count and the screen omits the line.
 */
public final class CachedEvidenceCounts implements EvidenceCounts {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachedEvidenceCounts.class);

    /** How long an answer is reused. */
    public static final Duration TTL = Duration.ofSeconds(30);

    private static final int PURGE_ABOVE = 256;
    private static final long UNKNOWN = -1;

    private record Cached(long count, long expiresAt) {
    }

    private final BackendGateway backend;
    private final Clock clock;
    private final Duration timeout;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public CachedEvidenceCounts(@NotNull BackendGateway backend, @NotNull Clock clock, @NotNull Duration timeout) {
        this.backend = backend;
        this.clock = clock;
        this.timeout = timeout;
    }

    @Override
    public OptionalLong of(@Nullable String punishmentIdentifier) {
        if (punishmentIdentifier == null || punishmentIdentifier.isBlank()) {
            return OptionalLong.empty();
        }
        long now = this.clock.millis();
        Cached cached = this.cache.get(punishmentIdentifier);
        if (cached != null && cached.expiresAt() > now) {
            return asOptional(cached.count());
        }
        long count = fetch(punishmentIdentifier);
        if (this.cache.size() > PURGE_ABOVE) {
            this.cache.values().removeIf(entry -> entry.expiresAt() <= now);
        }
        this.cache.put(punishmentIdentifier, new Cached(count, now + TTL.toMillis()));
        return asOptional(count);
    }

    private long fetch(String punishmentIdentifier) {
        try {
            Long count = this.backend.countEvidence(punishmentIdentifier).get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
            return count == null || count < 0 ? UNKNOWN : count;
        } catch (TimeoutException e) {
            LOGGER.debug("The evidence count was not available in time, leaving it out");
        } catch (ExecutionException e) {
            LOGGER.debug("The evidence count could not be loaded, leaving it out: {}", e.getCause() == null ? "unknown" : e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOGGER.debug("The evidence count could not be requested, leaving it out: {}", e.getMessage());
        }
        return UNKNOWN;
    }

    private static OptionalLong asOptional(long count) {
        return count == UNKNOWN ? OptionalLong.empty() : OptionalLong.of(count);
    }
}
