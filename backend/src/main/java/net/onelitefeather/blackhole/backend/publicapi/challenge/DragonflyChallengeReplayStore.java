package net.onelitefeather.blackhole.backend.publicapi.challenge;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one-time reserve of a solution: {@code SET key 1 NX PX ttl} in Dragonfly, so two concurrent requests with the same
 * solution see exactly one {@code OK}. It is its own class and not {@code SharedCache}, which is fail-open: here a failed
 * write would let a solution be used twice, so every error, timeout and missing answer is {@code UNAVAILABLE} (fail closed).
 * The key holds only the random nonce; it expires on its own.
 */
@Singleton
public class DragonflyChallengeReplayStore implements ChallengeReplayStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(DragonflyChallengeReplayStore.class);

    /** The key prefix; the nonce follows it. */
    static final String KEY_PREFIX = "blackhole:challenge:used:";

    /** Clock skew between replicas: a key outlives its challenge by this much. */
    static final Duration MARGIN = Duration.ofSeconds(60);

    /** The one Redis call the store makes, so a test can stand in for the connection. */
    @FunctionalInterface
    public interface SetIfAbsent {

        /** {@code SET key value NX PX ttl}: completes with {@code OK} when the key was stored, {@code null} when it existed. */
        CompletionStage<String> set(String key, String value, Duration ttl);
    }

    private final SetIfAbsent setIfAbsent;
    private final Duration timeout;

    public DragonflyChallengeReplayStore(StatefulRedisConnection<String, String> connection, PublicChallengeProperties properties) {
        this((key, value, ttl) -> connection.async().set(key, value, SetArgs.Builder.nx().px(ttl.toMillis())), properties.storeTimeout());
    }

    /** For a test that stands in for the connection. */
    DragonflyChallengeReplayStore(SetIfAbsent setIfAbsent, Duration timeout) {
        this.setIfAbsent = setIfAbsent;
        this.timeout = timeout;
    }

    @Override
    public Reservation reserve(String nonce, Duration remaining) {
        Duration ttl = (remaining.isNegative() ? Duration.ZERO : remaining).plus(MARGIN);
        try {
            String reply = this.setIfAbsent.set(KEY_PREFIX + nonce, "1", ttl).toCompletableFuture().get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
            if ("OK".equals(reply)) {
                return Reservation.RESERVED;
            }
            return reply == null ? Reservation.REPLAYED : Reservation.UNAVAILABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Challenge reserve interrupted; the request is refused");
        } catch (TimeoutException e) {
            LOGGER.warn("Challenge reserve timed out; the request is refused");
        } catch (ExecutionException | RuntimeException e) {
            LOGGER.warn("Challenge reserve failed ({}); the request is refused", e.getClass().getSimpleName());
        }
        return Reservation.UNAVAILABLE;
    }
}
