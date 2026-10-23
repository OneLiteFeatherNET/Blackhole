package net.onelitefeather.blackhole.backend.cache;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * JSON values in the shared Redis-protocol cache (Dragonfly in production), read and written over
 * the existing Lettuce connection.
 *
 * <p>Fail-open: every call is bounded by {@code blackhole.cache.timeout}, and any error or timeout
 * on read is a miss, while on write or evict it is logged and swallowed. The cache is never a
 * source of truth, so a cache outage must never fail a request.</p>
 */
@Singleton
public class SharedCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(SharedCache.class);

    private final StatefulRedisConnection<String, String> connection;
    private final JsonMapper jsonMapper;
    private final long timeoutMillis;

    public SharedCache(
            StatefulRedisConnection<String, String> connection,
            JsonMapper jsonMapper,
            @Value("${blackhole.cache.timeout:PT0.2S}") Duration timeout
    ) {
        this.connection = connection;
        this.jsonMapper = jsonMapper;
        this.timeoutMillis = timeout.toMillis();
    }

    /**
     * Reads and deserializes the value at {@code key}.
     *
     * @return the cached value, or empty on a miss, timeout or any error
     */
    public <T> Optional<T> get(String key, Argument<T> type) {
        try {
            String json = this.connection.async().get(key).get(this.timeoutMillis, TimeUnit.MILLISECONDS);
            return json == null ? Optional.empty() : Optional.ofNullable(this.jsonMapper.readValue(json, type));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Shared cache read interrupted for {}", key);
        } catch (Exception e) {
            LOGGER.warn("Shared cache read failed for {}, treating as a miss: {}", key, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Serializes {@code value} as {@code type} and stores it at {@code key} for {@code ttl}. The type
     * is explicit so that generic values such as lists keep their element type.
     */
    public <T> void put(String key, Argument<T> type, T value, Duration ttl) {
        try {
            String json = new String(this.jsonMapper.writeValueAsBytes(type, value), StandardCharsets.UTF_8);
            this.connection.async().psetex(key, ttl.toMillis(), json).get(this.timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Shared cache write interrupted for {}", key);
        } catch (Exception e) {
            LOGGER.error("Shared cache write failed for {}: {}", key, e.getMessage());
        }
    }

    /**
     * Like {@link #put} but reports whether the write reached the cache, for callers that must tell
     * their client to retry when it did not.
     */
    public <T> boolean tryPut(String key, Argument<T> type, T value, Duration ttl) {
        try {
            String json = new String(this.jsonMapper.writeValueAsBytes(type, value), StandardCharsets.UTF_8);
            this.connection.async().psetex(key, ttl.toMillis(), json).get(this.timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Shared cache write interrupted for {}", key);
        } catch (Exception e) {
            LOGGER.error("Shared cache write failed for {}: {}", key, e.getMessage());
        }
        return false;
    }

    /**
     * Lists the keys matching a glob with SCAN (never KEYS).
     *
     * @return the keys, or empty if the cache could not be reached; an empty list means "no match"
     */
    public Optional<List<String>> keys(String pattern) {
        try {
            ScanArgs args = ScanArgs.Builder.matches(pattern).limit(1000);
            List<String> found = new ArrayList<>();
            KeyScanCursor<String> cursor = this.connection.async().scan(args).get(this.timeoutMillis, TimeUnit.MILLISECONDS);
            found.addAll(cursor.getKeys());
            while (!cursor.isFinished()) {
                cursor = this.connection.async().scan(cursor, args).get(this.timeoutMillis, TimeUnit.MILLISECONDS);
                found.addAll(cursor.getKeys());
            }
            return Optional.of(found);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Shared cache scan interrupted for {}", pattern);
        } catch (Exception e) {
            LOGGER.warn("Shared cache scan failed for {}: {}", pattern, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Deletes the given keys. Missing keys are ignored.
     */
    public void evict(String... keys) {
        try {
            this.connection.async().del(keys).get(this.timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Shared cache evict interrupted for {}", Arrays.toString(keys));
        } catch (Exception e) {
            LOGGER.error("Shared cache evict failed for {}: {}", Arrays.toString(keys), e.getMessage());
        }
    }
}
